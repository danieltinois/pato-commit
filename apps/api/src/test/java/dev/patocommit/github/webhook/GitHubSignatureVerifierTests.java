package dev.patocommit.github.webhook;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubSignatureVerifierTests {

	/**
	 * The secret, payload and expected signature are the test vector published by
	 * GitHub for exactly this purpose:
	 * https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries
	 */
	private static final String SECRET = "It's a Secret to Everybody";

	private static final byte[] PAYLOAD = "Hello, World!".getBytes(StandardCharsets.UTF_8);

	private static final String EXPECTED = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

	private static GitHubSignatureVerifier verifierWith(String secret) {
		return new GitHubSignatureVerifier(new WebhookProperties(secret, Duration.ofMinutes(5), 3, 50));
	}

	@Test
	void matchesTheSignaturePublishedByGitHub() {
		assertThat(verifierWith(SECRET).isValid(PAYLOAD, EXPECTED)).isTrue();
	}

	@Test
	void rejectsABodyChangedAfterItWasSigned() {
		byte[] tampered = "Hello, World?".getBytes(StandardCharsets.UTF_8);
		assertThat(verifierWith(SECRET).isValid(tampered, EXPECTED)).isFalse();
	}

	@Test
	void rejectsAnAbsentSignature() {
		assertThat(verifierWith(SECRET).isValid(PAYLOAD, null)).isFalse();
	}

	@Test
	void rejectsASignatureMadeWithADifferentSecret() {
		assertThat(verifierWith("a different secret").isValid(PAYLOAD, EXPECTED)).isFalse();
	}

	@Test
	void rejectsMalformedSignatureHeaders() {
		GitHubSignatureVerifier verifier = verifierWith(SECRET);

		assertThat(verifier.isValid(PAYLOAD, "")).isFalse();
		assertThat(verifier.isValid(PAYLOAD, "757107ea")).isFalse();                            // no sha256= prefix
		assertThat(verifier.isValid(PAYLOAD, "sha1=757107ea")).isFalse();                       // legacy SHA-1
		assertThat(verifier.isValid(PAYLOAD, "sha256=zzzz")).isFalse();                         // not hex
		assertThat(verifier.isValid(PAYLOAD, "sha256=" + "00".repeat(31))).isFalse();           // 31 bytes
		assertThat(verifier.isValid(PAYLOAD, "sha256=" + "00".repeat(33))).isFalse();           // 33 bytes
	}

	@Test
	void rejectsEverythingWhenNoSecretIsConfigured() {
		// Fails closed. The app boots before the GitHub App exists in M2, and an
		// unconfigured secret must never mean an accepted delivery.
		assertThat(verifierWith("").isValid(PAYLOAD, EXPECTED)).isFalse();
		assertThat(verifierWith(null).isValid(PAYLOAD, EXPECTED)).isFalse();
	}

}
