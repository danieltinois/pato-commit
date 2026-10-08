package dev.patocommit.github.app;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The provider and the client together, against a mock GitHub. The RestClient is
 * built by the production {@link GitHubApiConfiguration#configure}, so the base
 * URL and default headers asserted here are the ones that ship.
 */
class InstallationTokenProviderTests {

	private static final long INSTALLATION_ID = 42L;

	private static final String TOKEN_URL = "https://api.github.com/app/installations/42/access_tokens";

	private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

	private final MutableClock clock = new MutableClock(NOW);

	private MockRestServiceServer github;

	private InstallationTokenProvider provider;

	@BeforeEach
	void setUp() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String pem = "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----";
		GitHubAppProperties properties = new GitHubAppProperties(123456L, pem, URI.create("https://api.github.com"));

		RestClient.Builder builder = GitHubApiConfiguration.configure(RestClient.builder(), properties);
		// Bound after configure(): binding replaces the request factory, so the
		// mock, not the real JDK client, receives the calls.
		github = MockRestServiceServer.bindTo(builder).build();
		GitHubAppClient client = new GitHubAppClient(builder.build(), new GitHubAppJwtSigner(properties, clock));
		provider = new InstallationTokenProvider(client, clock);
	}

	private static String tokenResponse(String token, Instant expiresAt) {
		return """
				{"token":"%s","expires_at":"%s","permissions":{"metadata":"read"},"repository_selection":"all"}
				""".formatted(token, expiresAt);
	}

	private void expectTokenRequest(String token, Instant expiresAt) {
		github.expect(once(), requestTo(TOKEN_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(request -> assertThat(request.getHeaders().getFirst("Authorization")).startsWith("Bearer "))
			.andExpect(header("Accept", "application/vnd.github+json"))
			.andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
			.andRespond(withSuccess(tokenResponse(token, expiresAt), MediaType.APPLICATION_JSON));
	}

	@Test
	void mintsATokenAuthenticatedAsTheApp() {
		github.expect(once(), requestTo(TOKEN_URL))
			.andExpect(method(HttpMethod.POST))
			// An App JWT: three base64url segments behind the Bearer scheme.
			.andExpect(request -> assertThat(request.getHeaders().getFirst("Authorization"))
				.matches("Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
			.andRespond(withSuccess(tokenResponse("ghs_first", NOW.plus(Duration.ofHours(1))),
					MediaType.APPLICATION_JSON));

		assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs_first");
		github.verify();
	}

	@Test
	void reusesATokenWhileItIsComfortablyValid() {
		expectTokenRequest("ghs_first", NOW.plus(Duration.ofHours(1)));

		provider.tokenFor(INSTALLATION_ID);
		clock.advance(Duration.ofMinutes(54));

		// once() above: a second request would fail the test.
		assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs_first");
		github.verify();
	}

	@Test
	void replacesATokenInsideTheRefreshMargin() {
		expectTokenRequest("ghs_first", NOW.plus(Duration.ofHours(1)));
		expectTokenRequest("ghs_second", NOW.plus(Duration.ofHours(2)));

		provider.tokenFor(INSTALLATION_ID);
		// 4 minutes left on the token, inside the 5-minute margin.
		clock.advance(Duration.ofMinutes(56));

		assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs_second");
		github.verify();
	}

	@Test
	void mintsANewTokenAfterAnEviction() {
		expectTokenRequest("ghs_first", NOW.plus(Duration.ofHours(1)));
		expectTokenRequest("ghs_second", NOW.plus(Duration.ofHours(1)));

		provider.tokenFor(INSTALLATION_ID);
		provider.evict(INSTALLATION_ID);

		assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs_second");
		github.verify();
	}

	@Test
	void concurrentCallersShareOneRefresh() throws Exception {
		expectTokenRequest("ghs_shared", NOW.plus(Duration.ofHours(1)));

		int callers = 16;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
			for (int i = 0; i < callers; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return provider.tokenFor(INSTALLATION_ID);
				}));
			}
			start.countDown();
			for (Future<String> result : results) {
				assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("ghs_shared");
			}
		}
		// Any second token request would have been rejected by the mock and failed
		// one of the futures above.
		github.verify();
	}

	@Test
	void propagatesAMissingInstallationAndCachesNothing() {
		github.expect(once(), requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		expectTokenRequest("ghs_after", NOW.plus(Duration.ofHours(1)));

		assertThatExceptionOfType(HttpClientErrorException.NotFound.class)
			.isThrownBy(() -> provider.tokenFor(INSTALLATION_ID));
		assertThat(provider.tokenFor(INSTALLATION_ID)).isEqualTo("ghs_after");
		github.verify();
	}

	@Test
	void neverPrintsTheTokenValue() {
		InstallationToken token = new InstallationToken("ghs_secret", NOW);
		assertThat(token.toString()).doesNotContain("ghs_secret").contains("<redacted>");
	}

	/** A clock the test moves forward by hand. */
	private static final class MutableClock extends Clock {

		private volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

	}

}
