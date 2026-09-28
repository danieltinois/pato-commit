package dev.patocommit.github.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M1 end to end against a real PostgreSQL, migrated by Flyway. Nothing here is
 * mocked: the intake, the claim, the recovery sweep and the state machine are the
 * production classes and the production SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class WebhookIntakeTests {

	private static final String SECRET = "It's a Secret to Everybody";

	/** Comfortably past the test profile's stale-after, so a row is a sweeper target. */
	private static final Duration ONE_HOUR = Duration.ofHours(1);

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

	/**
	 * Wired by hand rather than with {@code @ServiceConnection}: Boot 4.1.1's
	 * {@code ServiceConnectionContextCustomizerFactory} reflects on the field and
	 * rejects a Testcontainers 2.x container, while these three properties are the
	 * whole contract either way.
	 */
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
	private WebhookDeliveryRepository repository;

	@Autowired
	private WebhookProcessor processor;

	// --- signature -----------------------------------------------------------

	@Nested
	class Signature {

		@Test
		void acceptsADeliverySignedWithTheSecretGitHubDocuments() throws Exception {
			// The official test vector is verified byte for byte in
			// GitHubSignatureVerifierTests. Here the same secret is used over a
			// real payload to prove intake accepts a correctly signed delivery.
			byte[] body = "{\"zen\":\"Keep it logically awesome.\",\"hook_id\":1}".getBytes(StandardCharsets.UTF_8);
			UUID id = UUID.randomUUID();

			mockMvc.perform(guarded(id, "ping", body, sign(body))).andExpect(status().isAccepted());

			assertThat(awaitTerminal(id).status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
		}

		@Test
		void verifiesOverTheBytesOnTheWireNotTheParsedPayload() throws Exception {
			// Odd spacing, unicode and a trailing newline. Re-serialising this body
			// would drop the whitespace and reorder nothing here but would still
			// change the bytes, so a 202 is only possible if the HMAC covered the
			// untouched request body.
			byte[] body = "{\"zen\":   \"espa\u00f1ol \u2713\",\n\"hook_id\":42,   \"b\":1, \"a\":2}\n"
					.getBytes(StandardCharsets.UTF_8);
			UUID id = UUID.randomUUID();

			mockMvc.perform(guarded(id, "ping", body, sign(body))).andExpect(status().isAccepted());

			assertThat(awaitTerminal(id).status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
		}

		@Test
		void rejectsAWrongSignature() throws Exception {
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			UUID id = UUID.randomUUID();

			mockMvc.perform(guarded(id, "ping", body, "sha256=" + "00".repeat(32)))
					.andExpect(status().isUnauthorized());

			assertThat(repository.find(id)).isEmpty();
		}

		@Test
		void rejectsAnAbsentSignature() throws Exception {
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			UUID id = UUID.randomUUID();

			mockMvc.perform(post("/api/webhooks/github")
					.contentType(MediaType.APPLICATION_JSON)
					.header(GitHubWebhookController.DELIVERY_HEADER, id.toString())
					.header(GitHubWebhookController.EVENT_HEADER, "ping")
					.content(body))
					.andExpect(status().isUnauthorized());

			assertThat(repository.find(id)).isEmpty();
		}

		@Test
		void rejectsASignatureOverADifferentBody() throws Exception {
			byte[] signedBody = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
			byte[] sentBody = "{\"a\":2}".getBytes(StandardCharsets.UTF_8);
			UUID id = UUID.randomUUID();

			mockMvc.perform(guarded(id, "ping", sentBody, sign(signedBody)))
					.andExpect(status().isUnauthorized());

			assertThat(repository.find(id)).isEmpty();
		}

	}

	// --- delivery id ---------------------------------------------------------

	@Nested
	class DeliveryId {

		@Test
		void rejectsAnAbsentDeliveryId() throws Exception {
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(post("/api/webhooks/github")
					.contentType(MediaType.APPLICATION_JSON)
					.header(GitHubWebhookController.SIGNATURE_HEADER, sign(body))
					.content(body))
					.andExpect(status().isBadRequest());
		}

		@Test
		void rejectsAMalformedDeliveryId() throws Exception {
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(guarded("not-a-guid", "ping", body, sign(body)))
					.andExpect(status().isBadRequest());
		}

		@Test
		void checksTheDeliveryIdBeforeItParsesThePayload() throws Exception {
			// A correctly signed body that is not JSON still fails on the delivery
			// id, so an unauthenticated caller learns nothing about parsing.
			byte[] notJson = "not json at all".getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(guarded("not-a-guid", "ping", notJson, sign(notJson)))
					.andExpect(status().isBadRequest());
		}

		@Test
		void rejectsASignedBodyThatIsNotJson() throws Exception {
			byte[] notJson = "not json at all".getBytes(StandardCharsets.UTF_8);
			UUID id = UUID.randomUUID();

			mockMvc.perform(guarded(id, "ping", notJson, sign(notJson)))
					.andExpect(status().isBadRequest());

			assertThat(repository.find(id)).isEmpty();
		}

	}

	// --- event handling ------------------------------------------------------

	@Nested
	class Events {

		@Test
		void handlesPing() throws Exception {
			UUID id = UUID.randomUUID();
			byte[] body = "{\"zen\":\"Keep it logically awesome.\",\"hook_id\":1}".getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(guarded(id, "ping", body, sign(body))).andExpect(status().isAccepted());

			WebhookDelivery delivery = awaitTerminal(id);
			assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
			assertThat(delivery.eventType()).isEqualTo("ping");
			assertThat(delivery.attempts()).isEqualTo(1);
		}

		@Test
		void recordsAnUnsupportedEventAsIgnoredWithoutFailing() throws Exception {
			UUID id = UUID.randomUUID();
			byte[] body = "{\"action\":\"completed\",\"workflow_run\":{\"id\":9}}"
					.getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(guarded(id, "workflow_run", body, sign(body)))
					.andExpect(status().isAccepted());

			WebhookDelivery delivery = awaitTerminal(id);
			assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.IGNORED);
			assertThat(delivery.eventType()).isEqualTo("workflow_run");
		}

		@Test
		void recordsAnAbsentEventHeaderAsIgnored() throws Exception {
			UUID id = UUID.randomUUID();
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(guarded(id, null, body, sign(body))).andExpect(status().isAccepted());

			WebhookDelivery delivery = awaitTerminal(id);
			assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.IGNORED);
			assertThat(delivery.eventType()).isEqualTo(GitHubWebhookController.UNKNOWN_EVENT);
		}

	}

	// --- persistence ---------------------------------------------------------

	@Nested
	class Persistence {

		@Test
		void storesTheEventActionAndRepositoryId() throws Exception {
			UUID id = UUID.randomUUID();
			byte[] body = "{\"action\":\"opened\",\"repository\":{\"id\":4242,\"full_name\":\"acme/widgets\"}}"
					.getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(guarded(id, "issues", body, sign(body))).andExpect(status().isAccepted());

			WebhookDelivery delivery = awaitTerminal(id);
			assertThat(delivery.action()).isEqualTo("opened");
			assertThat(delivery.repositoryId()).isEqualTo(4242L);
		}

		@Test
		void leavesRepositoryIdNullForAnEventWithoutARepository() throws Exception {
			UUID id = UUID.randomUUID();
			byte[] body = "{\"zen\":\"x\",\"hook_id\":1}".getBytes(StandardCharsets.UTF_8);

			mockMvc.perform(guarded(id, "ping", body, sign(body))).andExpect(status().isAccepted());

			WebhookDelivery delivery = awaitTerminal(id);
			assertThat(delivery.repositoryId()).isNull();
			assertThat(delivery.action()).isNull();
		}

		@Test
		void theStateCheckConstraintsRejectAnInconsistentRow() {
			// A half-finished update that moved status without the timestamp must
			// not be representable: the database refuses it.
			assertThatThrownBy(() -> jdbc.sql("""
							INSERT INTO webhook_delivery
							    (delivery_id, event_type, payload, status, processed_at)
							VALUES (:id, 'ping', '{}'::jsonb, 'PROCESSED', NULL)
							""")
					.param("id", UUID.randomUUID())
					.update()).isInstanceOf(DataIntegrityViolationException.class);

			assertThatThrownBy(() -> jdbc.sql("""
							INSERT INTO webhook_delivery
							    (delivery_id, event_type, payload, status, processing_started_at)
							VALUES (:id, 'ping', '{}'::jsonb, 'PENDING', now())
							""")
					.param("id", UUID.randomUUID())
					.update()).isInstanceOf(DataIntegrityViolationException.class);

			assertThatThrownBy(() -> jdbc.sql("""
							INSERT INTO webhook_delivery
							    (delivery_id, event_type, payload, status, attempts)
							VALUES (:id, 'ping', '{}'::jsonb, 'PENDING', -1)
							""")
					.param("id", UUID.randomUUID())
					.update()).isInstanceOf(DataIntegrityViolationException.class);
		}

	}

	// --- idempotency ---------------------------------------------------------

	@Nested
	class Idempotency {

		@Test
		void aRedeliveryOfTheSameIdDoesNotProcessAgain() throws Exception {
			byte[] body = "{\"zen\":\"x\",\"hook_id\":1}".getBytes(StandardCharsets.UTF_8);
			UUID id = UUID.randomUUID();

			mockMvc.perform(guarded(id, "ping", body, sign(body))).andExpect(status().isAccepted());
			WebhookDelivery first = awaitTerminal(id);
			Instant firstReceivedAt = first.receivedAt();

			// GitHub reuses X-GitHub-Delivery on a redelivery.
			mockMvc.perform(guarded(id, "ping", body, sign(body))).andExpect(status().isAccepted());
			mockMvc.perform(guarded(id, "ping", body, sign(body))).andExpect(status().isAccepted());

			WebhookDelivery after = repository.find(id).orElseThrow();
			assertThat(after.status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
			// The claim happens only from PENDING, so a redelivery adds no attempt.
			assertThat(after.attempts()).isEqualTo(1);
			// ON CONFLICT DO NOTHING: the original row survives untouched rather
			// than being replaced by a new intake.
			assertThat(after.receivedAt()).isEqualTo(firstReceivedAt);
		}

		@Test
		void insertReportsWhetherTheDeliveryWasNew() {
			UUID id = UUID.randomUUID();
			assertThat(repository.insert(pending(id, "ping"))).isTrue();
			assertThat(repository.insert(pending(id, "ping"))).isFalse();
		}

	}

	// --- claiming ------------------------------------------------------------

	@Nested
	class Claiming {

		@Test
		void onlyOneOfTwoConcurrentWorkersCanClaimTheSameDelivery() throws Exception {
			UUID id = UUID.randomUUID();
			repository.insert(pending(id, "ping"));

			CyclicBarrier startTogether = new CyclicBarrier(2);
			ExecutorService workers = Executors.newFixedThreadPool(2);
			try {
				List<Future<Optional<WebhookDelivery>>> results = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					results.add(workers.submit(() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return repository.claim(id);
					}));
				}

				int winners = 0;
				for (Future<Optional<WebhookDelivery>> result : results) {
					if (result.get(20, TimeUnit.SECONDS).isPresent()) {
						winners++;
					}
				}
				assertThat(winners).isEqualTo(1);
			}
			finally {
				workers.shutdownNow();
			}
		}

		@Test
		void attemptsCountsRealClaimsNotLosingAttempts() throws Exception {
			UUID id = UUID.randomUUID();
			repository.insert(pending(id, "ping"));

			CyclicBarrier startTogether = new CyclicBarrier(2);
			ExecutorService workers = Executors.newFixedThreadPool(2);
			try {
				List<Future<Optional<WebhookDelivery>>> results = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					results.add(workers.submit(() -> {
						startTogether.await(10, TimeUnit.SECONDS);
						return repository.claim(id);
					}));
				}
				for (Future<Optional<WebhookDelivery>> result : results) {
					result.get(20, TimeUnit.SECONDS);
				}
			}
			finally {
				workers.shutdownNow();
			}

			// Two callers tried, one succeeded. The loser must not have moved the row.
			WebhookDelivery delivery = repository.find(id).orElseThrow();
			assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PROCESSING);
			assertThat(delivery.attempts()).isEqualTo(1);
			assertThat(delivery.processingStartedAt()).isNotNull();
		}

		@Test
		void doesNotRecoverADeliveryThatWasClaimedRecently() {
			UUID id = UUID.randomUUID();
			repository.insert(pending(id, "ping"));
			repository.claim(id);

			assertThat(processor.recoverStale()).isZero();

			WebhookDelivery delivery = repository.find(id).orElseThrow();
			assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PROCESSING);
			assertThat(delivery.attempts()).isEqualTo(1);
		}

		@Test
		void recoversADeliveryStuckInProcessingByACrash() {
			UUID id = UUID.randomUUID();
			repository.insert(pending(id, "ping"));
			repository.claim(id);

			// Reproduce the crash: the claim is committed, the process dies before
			// the handler finishes, so the row keeps its claim timestamp.
			ageRow(id, ONE_HOUR);

			assertThat(processor.recoverStale()).isEqualTo(1);

			WebhookDelivery delivery = repository.find(id).orElseThrow();
			assertThat(delivery.status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
			assertThat(delivery.attempts()).isEqualTo(2);
		}

		@Test
		void recoversADeliveryThatWasNeverClaimed() {
			UUID id = UUID.randomUUID();
			repository.insert(pending(id, "ping"));
			ageRow(id, ONE_HOUR);

			assertThat(processor.recoverStale()).isEqualTo(1);
			assertThat(repository.find(id).orElseThrow().status()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
		}

		@Test
		void neverClaimsADeliveryThatAlreadyReachedATerminalState() {
			UUID processed = UUID.randomUUID();
			UUID ignored = UUID.randomUUID();
			UUID failed = UUID.randomUUID();

			repository.insert(pending(processed, "ping"));
			repository.insert(pending(ignored, "workflow_run"));
			repository.insert(pending(failed, "ping"));

			repository.markProcessed(processed);
			repository.markIgnored(ignored, "no handler registered");
			repository.markFailed(failed, "BoomException");

			// Already terminal, so even an aged-out row is not a recovery target.
			for (UUID id : List.of(processed, ignored, failed)) {
				ageRow(id, ONE_HOUR);
			}
			assertThat(processor.recoverStale()).isZero();

			for (UUID id : List.of(processed, ignored, failed)) {
				assertThat(repository.claim(id)).as("terminal delivery %s must not be claimable", id).isEmpty();
			}
			for (UUID id : List.of(processed, ignored, failed)) {
				assertThat(repository.find(id).orElseThrow().attempts()).isZero();
			}
		}

		@Test
		void releasingForRetryLetsTheSweeperTakeTheDeliveryAgain() {
			UUID id = UUID.randomUUID();
			repository.insert(pending(id, "ping"));
			repository.claim(id);

			repository.releaseForRetry(id, "BoomException");

			WebhookDelivery released = repository.find(id).orElseThrow();
			assertThat(released.status()).isEqualTo(WebhookDeliveryStatus.PENDING);
			assertThat(released.processingStartedAt()).isNull();
			assertThat(released.error()).isEqualTo("BoomException");

			// PENDING, so the sweeper may take it once it ages out.
			assertThat(processor.recoverStale()).isZero();
			ageRow(id, ONE_HOUR);
			assertThat(processor.recoverStale()).isEqualTo(1);
		}

	}

	// --- helpers -------------------------------------------------------------

	private WebhookDelivery pending(UUID id, String event) {
		return new WebhookDelivery(id, event, null, null, "{}", WebhookDeliveryStatus.PENDING, 0, null, Instant.now(),
				null, null);
	}

	private MockHttpServletRequestBuilder guarded(Object deliveryId, String event, byte[] body, String signature) {
		MockHttpServletRequestBuilder request = post("/api/webhooks/github")
				.contentType(MediaType.APPLICATION_JSON)
				.header(GitHubWebhookController.SIGNATURE_HEADER, signature)
				.content(body);
		if (deliveryId != null) {
			request = request.header(GitHubWebhookController.DELIVERY_HEADER, deliveryId.toString());
		}
		return (event == null) ? request : request.header(GitHubWebhookController.EVENT_HEADER, event);
	}

	private static String sign(byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Polls until the delivery reaches a terminal state, so no sleep-based flake. */
	private WebhookDelivery awaitTerminal(UUID id) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (System.nanoTime() < deadline) {
			WebhookDelivery delivery = repository.find(id).orElseThrow(() -> new AssertionError("no delivery " + id));
			if (delivery.status().isTerminal()) {
				return delivery;
			}
			try {
				Thread.sleep(25);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted", e);
			}
		}
		throw new AssertionError("delivery " + id + " never reached a terminal state");
	}

	/**
	 * Ages a row so the sweeper considers it abandoned. {@code processing_started_at}
	 * is only set when the row is actually claimed, which keeps the
	 * {@code processing_started_at} CHECK constraint satisfied either way.
	 */
	private void ageRow(UUID id, Duration age) {
		OffsetDateTime when = OffsetDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC);
		jdbc.sql("""
				UPDATE webhook_delivery
				   SET received_at = :when,
				       processing_started_at = CASE WHEN status = 'PROCESSING' THEN :when ELSE NULL END
				 WHERE delivery_id = :id
				""")
				.param("when", when)
				.param("id", id)
				.update();
	}

}
