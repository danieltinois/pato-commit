package dev.patocommit.github.app;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubApiTests {

	private static final String API = "https://api.github.com";

	private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

	private MockRestServiceServer github;

	private GitHubApi api;

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Item(long id) {

	}

	@BeforeEach
	void setUp() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----";
		GitHubAppProperties properties = new GitHubAppProperties(123456L, pem, URI.create(API));
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

		RestClient.Builder builder = GitHubApiConfiguration.configure(RestClient.builder(), properties);
		github = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		GitHubAppJwtSigner signer = new GitHubAppJwtSigner(properties, clock);
		InstallationTokenProvider tokens = new InstallationTokenProvider(new GitHubAppClient(restClient, signer), clock);
		api = new GitHubApi(restClient, signer, tokens, JsonMapper.builder().build(), properties);
	}

	private static ResponseCreator page(String json, String next) {
		HttpHeaders headers = new HttpHeaders();
		if (next != null) {
			headers.add(HttpHeaders.LINK, "<" + next + ">; rel=\"next\", <" + API + "/x?page=9>; rel=\"last\"");
		}
		return withSuccess(json, MediaType.APPLICATION_JSON).headers(headers);
	}

	private void expectToken(String token) {
		github.expect(once(), requestTo(API + "/app/installations/42/access_tokens"))
			.andRespond(withSuccess("""
					{"token":"%s","expires_at":"%s"}""".formatted(token, NOW.plus(Duration.ofHours(1))),
					MediaType.APPLICATION_JSON));
	}

	@Test
	void followsNextLinksUntilTheLastPage() {
		github.expect(once(), requestTo(API + "/app/installations?per_page=100"))
			.andRespond(page("[{\"id\":1},{\"id\":2}]", API + "/app/installations?per_page=100&page=2"));
		github.expect(once(), requestTo(API + "/app/installations?per_page=100&page=2"))
			.andRespond(page("[{\"id\":3}]", API + "/app/installations?per_page=100&page=3"));
		github.expect(once(), requestTo(API + "/app/installations?per_page=100&page=3"))
			.andRespond(page("[{\"id\":4,\"ignored\":true}]", null));

		assertThat(api.getAllAsApp("/app/installations?per_page=100", Item.class)).extracting(Item::id)
			.containsExactly(1L, 2L, 3L, 4L);
		github.verify();
	}

	@Test
	void readsItemsFromAFieldWithTheInstallationToken() {
		expectToken("ghs_one");
		github.expect(once(), requestTo(API + "/installation/repositories?per_page=100"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ghs_one"))
			.andRespond(page("{\"total_count\":2,\"repositories\":[{\"id\":7},{\"id\":8}]}", null));

		assertThat(api.getAllAsInstallation(42, "/installation/repositories?per_page=100", "repositories", Item.class))
			.extracting(Item::id)
			.containsExactly(7L, 8L);
		github.verify();
	}

	@Test
	void refusesToFollowANextLinkToAnotherHost() {
		// The credential would travel with the request. Nothing is sent: the mock
		// would reject any request it was not told to expect.
		github.expect(once(), requestTo(API + "/app/installations?per_page=100"))
			.andRespond(page("[{\"id\":1}]", "https://api.github.com.evil.example/app/installations?page=2"));

		assertThatIllegalStateException().isThrownBy(() -> api.getAllAsApp("/app/installations?per_page=100", Item.class))
			.withMessageNotContaining("evil");
		github.verify();
	}

	@Test
	void refusesADowngradeToPlainHttp() {
		github.expect(once(), requestTo(API + "/app/installations?per_page=100"))
			.andRespond(page("[{\"id\":1}]", "http://api.github.com/app/installations?page=2"));

		assertThatIllegalStateException()
			.isThrownBy(() -> api.getAllAsApp("/app/installations?per_page=100", Item.class));
	}

	@Test
	void aFailedPageFailsTheWholeListing() {
		github.expect(once(), requestTo(API + "/app/installations?per_page=100"))
			.andRespond(page("[{\"id\":1}]", API + "/app/installations?per_page=100&page=2"));
		github.expect(once(), requestTo(API + "/app/installations?per_page=100&page=2"))
			.andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		// No partial list: page one alone would look like "everything else is gone".
		assertThatExceptionOfType(HttpServerErrorException.class)
			.isThrownBy(() -> api.getAllAsApp("/app/installations?per_page=100", Item.class));
	}

	@Test
	void stopsAtThePageCap() {
		github.expect(manyTimes(), requestTo(API + "/app/installations?page=loop"))
			.andRespond(page("[]", API + "/app/installations?page=loop"));

		assertThatIllegalStateException().isThrownBy(() -> api.getAllAsApp("/app/installations?page=loop", Item.class))
			.withMessageContaining(String.valueOf(GitHubApi.MAX_PAGES));
	}

	@Test
	void aRejectedTokenIsEvictedSoTheNextCallMintsANewOne() {
		expectToken("ghs_revoked");
		github.expect(once(), requestTo(API + "/installation/repositories"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		expectToken("ghs_fresh");
		github.expect(once(), requestTo(API + "/installation/repositories"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ghs_fresh"))
			.andRespond(page("{\"repositories\":[]}", null));

		assertThatExceptionOfType(HttpClientErrorException.Unauthorized.class)
			.isThrownBy(() -> api.getAllAsInstallation(42, "/installation/repositories", "repositories", Item.class));
		assertThat(api.getAllAsInstallation(42, "/installation/repositories", "repositories", Item.class)).isEmpty();
		github.verify();
	}

	@Test
	void anInstallationExistsUnlessGitHubSays404() {
		github.expect(once(), requestTo(API + "/app/installations/1")).andRespond(withSuccess());
		github.expect(once(), requestTo(API + "/app/installations/2")).andRespond(withStatus(HttpStatus.NOT_FOUND));
		github.expect(once(), requestTo(API + "/app/installations/3")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

		assertThat(api.installationExists(1)).isTrue();
		assertThat(api.installationExists(2)).isFalse();
		// Anything but 404 is not an answer. The caller would remove the
		// installation for good on a false, so an outage must not produce one.
		assertThatExceptionOfType(HttpServerErrorException.class).isThrownBy(() -> api.installationExists(3));
		github.verify();
	}

	@Test
	void reportsWhetherTheAppIsConfigured() {
		assertThat(api.isConfigured()).isTrue();
	}

}
