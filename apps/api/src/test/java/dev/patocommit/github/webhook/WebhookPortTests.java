package dev.patocommit.github.webhook;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both ports of a real running server. MockMvc cannot test this: it has no
 * sockets, so there is no port for the filter to look at.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class WebhookPortTests {

	private static final String SECRET = "It's a Secret to Everybody";

	private static final int WEBHOOK_PORT = freePort();

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("pato-commit.webhook.port", () -> WEBHOOK_PORT);
	}

	@LocalServerPort
	private int mainPort;

	private final HttpClient http = HttpClient.newHttpClient();

	// --- the webhook port: what a tunnel would expose ---------------------------

	@Test
	void theWebhookPortAcceptsASignedDelivery() throws Exception {
		byte[] body = "{\"zen\":\"Quack.\",\"hook_id\":1}".getBytes(StandardCharsets.UTF_8);

		HttpResponse<String> response = http.send(HttpRequest.newBuilder(webhookUri("/api/webhooks/github"))
			.header("X-GitHub-Delivery", UUID.randomUUID().toString())
			.header("X-GitHub-Event", "ping")
			.header("X-Hub-Signature-256", sign(body))
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(202);
	}

	@Test
	void theWebhookPortStillRequiresTheSignature() throws Exception {
		// Reaching the controller is not reaching the data: unsigned is still 401.
		assertThat(post(webhookUri("/api/webhooks/github")).statusCode()).isEqualTo(401);
	}

	@Test
	void theWebhookPortHidesEverythingElse() throws Exception {
		for (String path : new String[] { "/actuator/health", "/actuator/prometheus", "/api/installations",
				"/v3/api-docs", "/swagger-ui.html", "/" }) {
			HttpResponse<String> response = get(webhookUri(path));
			assertThat(response.statusCode()).as(path).isEqualTo(404);
			assertThat(response.body()).as(path).isEmpty();
		}
	}

	@Test
	void theWebhookPortRefusesVariantsOfTheWebhookPath() throws Exception {
		assertThat(get(webhookUri("/api/webhooks/github")).statusCode()).as("GET").isEqualTo(404);
		assertThat(post(webhookUri("/api/webhooks/github?x=1")).statusCode()).as("query").isEqualTo(404);
		assertThat(post(webhookUri("/api/webhooks/%67ithub")).statusCode()).as("encoded").isEqualTo(404);
		assertThat(post(webhookUri("/api/webhooks/github/")).statusCode()).as("trailing slash").isEqualTo(404);
	}

	// --- the main port: local only, everything served -----------------------

	@Test
	void theMainPortServesTheReadApiAndSwagger() throws Exception {
		HttpResponse<String> installations = get(mainUri("/api/installations"));
		assertThat(installations.statusCode()).isEqualTo(200);
		assertThat(installations.body()).startsWith("[");

		HttpResponse<String> apiDocs = get(mainUri("/v3/api-docs"));
		assertThat(apiDocs.statusCode()).isEqualTo(200);
		assertThat(apiDocs.body()).contains("/api/installations").contains("Pato Commit API");

		assertThat(get(mainUri("/swagger-ui/index.html")).statusCode()).isEqualTo(200);
	}

	// --- helpers --------------------------------------------------------------

	private URI webhookUri(String path) {
		return URI.create("http://127.0.0.1:" + WEBHOOK_PORT + path);
	}

	private URI mainUri(String path) {
		return URI.create("http://127.0.0.1:" + mainPort + path);
	}

	private HttpResponse<String> get(URI uri) throws Exception {
		return http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(URI uri) throws Exception {
		return http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private static String sign(byte[] body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
	}

	private static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

}
