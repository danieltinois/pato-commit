package dev.patocommit.github.installation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.patocommit.github.webhook.WebhookDelivery;
import dev.patocommit.github.webhook.WebhookDeliveryStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The installation handlers against a real PostgreSQL. Most tests call the
 * handlers directly, which keeps them deterministic; one goes through the signed
 * endpoint to prove the handlers are wired into dispatch.
 *
 * <p>Payloads are trimmed to the fields the handlers read, plus one field they
 * do not, to keep "unknown fields are ignored" exercised everywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class InstallationWebhookTests {

	private static final String SECRET = "It's a Secret to Everybody";

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

	/** Wired by hand for the same reason as in {@code WebhookIntakeTests}. */
	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private InstallationWebhookHandler installationHandler;

	@Autowired
	private InstallationRepositoriesWebhookHandler repositoriesHandler;

	// --- the installation lifecycle ------------------------------------------

	@Nested
	class Lifecycle {

		@Test
		void createdRecordsTheInstallationAndItsRepositories() {
			long installation = randomId();
			long first = randomId();
			long second = randomId();

			installation(installation, "created", null, repo(first, "pato/commit", true), repo(second, "pato/pond", false));

			assertThat(jdbc.sql("SELECT account_login FROM github_installation WHERE installation_id = :id")
				.param("id", installation)
				.query(String.class)
				.single()).isEqualTo("pato");
			assertThat(liveRepositories(installation)).containsExactlyInAnyOrder(first, second);
		}

		@Test
		void aRedeliveredCreatedChangesNothing() {
			long installation = randomId();
			long repository = randomId();

			installation(installation, "created", null, repo(repository, "pato/commit", true));
			installation(installation, "created", null, repo(repository, "pato/commit", true));

			assertThat(liveRepositories(installation)).containsExactly(repository);
		}

		@Test
		void suspendAndUnsuspendFollowThePayload() {
			long installation = randomId();
			installation(installation, "created", null);

			installation(installation, "suspend", Instant.parse("2026-10-08T12:00:00Z"));
			assertThat(suspendedAt(installation)).isEqualTo(Instant.parse("2026-10-08T12:00:00Z"));

			installation(installation, "unsuspend", null);
			assertThat(suspendedAt(installation)).isNull();
		}

		@Test
		void deletedRemovesTheInstallationAndEveryRepository() {
			long installation = randomId();
			installation(installation, "created", null, repo(randomId(), "pato/commit", true));

			installation(installation, "deleted", null);

			assertThat(isRemoved(installation)).isTrue();
			assertThat(liveRepositories(installation)).isEmpty();
		}

		@Test
		void repositoriesAddedAndRemovedFollowTheSelection() {
			long installation = randomId();
			long kept = randomId();
			long dropped = randomId();
			long added = randomId();
			installation(installation, "created", null, repo(kept, "pato/kept", true), repo(dropped, "pato/dropped", true));

			repositories(installation, List.of(repo(added, "pato/added", false)), List.of(repo(dropped, "pato/dropped", true)));

			assertThat(liveRepositories(installation)).containsExactlyInAnyOrder(kept, added);
		}

	}

	// --- GitHub does not guarantee delivery order ----------------------------

	@Nested
	class OutOfOrder {

		@Test
		void aLateCreatedCannotResurrectADeletedInstallation() {
			long installation = randomId();
			long repository = randomId();
			installation(installation, "created", null, repo(repository, "pato/commit", true));
			installation(installation, "deleted", null);

			installation(installation, "created", null, repo(repository, "pato/commit", true));

			assertThat(isRemoved(installation)).isTrue();
			assertThat(liveRepositories(installation)).isEmpty();
		}

		@Test
		void aDeletedSeenFirstStillBlocksTheCreatedThatFollows() {
			long installation = randomId();

			installation(installation, "deleted", null);
			installation(installation, "created", null, repo(randomId(), "pato/commit", true));

			assertThat(isRemoved(installation)).isTrue();
			assertThat(liveRepositories(installation)).isEmpty();
		}

		@Test
		void aLateAdditionToADeletedInstallationIsIgnored() {
			long installation = randomId();
			installation(installation, "created", null);
			installation(installation, "deleted", null);

			repositories(installation, List.of(repo(randomId(), "pato/late", true)), List.of());

			assertThat(liveRepositories(installation)).isEmpty();
		}

		@Test
		void anAdditionBeforeCreatedIsAppliedFromItsOwnInstallationObject() {
			long installation = randomId();
			long repository = randomId();

			// No 'created' processed yet: the installation row comes from this event.
			repositories(installation, List.of(repo(repository, "pato/early", true)), List.of());

			assertThat(liveRepositories(installation)).containsExactly(repository);
		}

		@Test
		void aReinstallMovesTheRepositoryAndALateRemovalFromTheOldOneDoesNotDetachIt() {
			long oldInstallation = randomId();
			long newInstallation = randomId();
			long repository = randomId();
			installation(oldInstallation, "created", null, repo(repository, "pato/commit", true));
			installation(oldInstallation, "deleted", null);
			installation(newInstallation, "created", null, repo(repository, "pato/commit", true));

			// The old installation is removed, so this is refused before it reaches
			// the repository; and even past that, the removal is scoped to the
			// installation the repository no longer belongs to.
			repositories(oldInstallation, List.of(), List.of(repo(repository, "pato/commit", true)));

			assertThat(liveRepositories(newInstallation)).containsExactly(repository);
		}

	}

	// --- malformed and end to end --------------------------------------------

	@Test
	void aPayloadWithoutAnInstallationFailsTheDelivery() {
		// Thrown, so the processor retries and finally parks it as FAILED, where
		// it is visible, instead of being recorded as handled.
		assertThatException().isThrownBy(() -> installationHandler.handle(delivery("installation", """
				{"action":"created","repositories":[]}
				""")));
	}

	@Test
	void aSignedInstallationDeliveryIsDispatchedToTheHandler() throws Exception {
		long installation = randomId();
		UUID deliveryId = UUID.randomUUID();
		byte[] body = installationJson(installation, "created", null, List.of(repo(randomId(), "pato/commit", true)))
			.getBytes(StandardCharsets.UTF_8);

		mockMvc.perform(post("/api/webhooks/github")
			.header("X-GitHub-Delivery", deliveryId.toString())
			.header("X-GitHub-Event", "installation")
			.header("X-Hub-Signature-256", sign(body))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isAccepted());

		assertThat(awaitStatus(deliveryId)).isEqualTo(WebhookDeliveryStatus.PROCESSED.name());
		assertThat(liveRepositories(installation)).hasSize(1);
	}

	// --- payloads -------------------------------------------------------------

	private record Repo(long id, String fullName, boolean isPrivate) {

		String json() {
			return """
					{"id":%d,"node_id":"R_x","name":"%s","full_name":"%s","private":%b}"""
				.formatted(id, fullName.substring(fullName.indexOf('/') + 1), fullName, isPrivate);
		}

	}

	private static Repo repo(long id, String fullName, boolean isPrivate) {
		return new Repo(id, fullName, isPrivate);
	}

	private static String installationObject(long installationId, Instant suspendedAt) {
		return """
				{"id":%d,"account":{"id":1001,"login":"pato","type":"User"},"repository_selection":"selected",\
				"suspended_at":%s,"app_slug":"pato-commit"}"""
			.formatted(installationId, suspendedAt == null ? "null" : "\"" + suspendedAt + "\"");
	}

	private static String installationJson(long installationId, String action, Instant suspendedAt, List<Repo> repos) {
		return """
				{"action":"%s","installation":%s,"repositories":[%s],"sender":{"login":"pato"}}"""
			.formatted(action, installationObject(installationId, suspendedAt), join(repos));
	}

	private void installation(long installationId, String action, Instant suspendedAt, Repo... repos) {
		installationHandler.handle(delivery("installation", installationJson(installationId, action, suspendedAt, List.of(repos))));
	}

	private void repositories(long installationId, List<Repo> added, List<Repo> removed) {
		String json = """
				{"action":"%s","installation":%s,"repository_selection":"selected",\
				"repositories_added":[%s],"repositories_removed":[%s]}"""
			.formatted(removed.isEmpty() ? "added" : "removed", installationObject(installationId, null),
					join(added), join(removed));
		repositoriesHandler.handle(delivery("installation_repositories", json));
	}

	private static String join(List<Repo> repos) {
		return String.join(",", repos.stream().map(Repo::json).toList());
	}

	private static WebhookDelivery delivery(String event, String payload) {
		return new WebhookDelivery(UUID.randomUUID(), event, null, null, payload, WebhookDeliveryStatus.PROCESSING, 1,
				null, Instant.now(), Instant.now(), null);
	}

	// --- queries --------------------------------------------------------------

	private List<Long> liveRepositories(long installationId) {
		return jdbc.sql("SELECT repository_id FROM github_repository WHERE installation_id = :id AND removed_at IS NULL")
			.param("id", installationId)
			.query(Long.class)
			.list();
	}

	private boolean isRemoved(long installationId) {
		return jdbc.sql("SELECT removed_at IS NOT NULL FROM github_installation WHERE installation_id = :id")
			.param("id", installationId)
			.query(Boolean.class)
			.single();
	}

	private Instant suspendedAt(long installationId) {
		return jdbc.sql("SELECT suspended_at FROM github_installation WHERE installation_id = :id")
			.param("id", installationId)
			.query((rs, row) -> {
				OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
				return value == null ? null : value.toInstant();
			})
			// list(), not single(): single() rejects a null value, and null is the
			// answer this asks about after unsuspend.
			.list()
			.getFirst();
	}

	private String awaitStatus(UUID deliveryId) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (Instant.now().isBefore(deadline)) {
			String status = jdbc.sql("SELECT status FROM webhook_delivery WHERE delivery_id = :id")
				.param("id", deliveryId)
				.query(String.class)
				.optional()
				.orElse(null);
			if (status != null && WebhookDeliveryStatus.valueOf(status).isTerminal()) {
				return status;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("delivery " + deliveryId + " did not reach a terminal state");
	}

	private static long randomId() {
		return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
	}

	private static String sign(byte[] body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
	}

}
