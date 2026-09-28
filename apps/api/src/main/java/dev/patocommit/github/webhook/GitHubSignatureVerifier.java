package dev.patocommit.github.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} header.
 *
 * <p>The HMAC is always computed over the raw request bytes. Deserialising the
 * body and re-serialising it would change the key order, the whitespace and the
 * number formatting, and the digest would not match a body GitHub never signed.
 */
@Component
class GitHubSignatureVerifier {

	private static final Logger log = LoggerFactory.getLogger(GitHubSignatureVerifier.class);

	private static final String PREFIX = "sha256=";

	private static final int SHA256_LENGTH = 32;

	private static final HexFormat HEX = HexFormat.of();

	/** {@code null} until the webhook secret is configured. */
	private final byte[] secret;

	GitHubSignatureVerifier(WebhookProperties properties) {
		String configured = properties.secret();
		this.secret = (configured == null || configured.isBlank()) ? null : configured.getBytes(StandardCharsets.UTF_8);
		if (this.secret == null) {
			log.error("No pato-commit.webhook.secret configured: every webhook delivery will be rejected. "
					+ "Set PATO_COMMIT_GITHUB_WEBHOOK_SECRET before pointing a GitHub App at this endpoint.");
		}
	}

	boolean isValid(byte[] rawBody, String signatureHeader) {
		if (secret == null || signatureHeader == null) {
			return false;
		}
		byte[] provided = decode(signatureHeader);
		if (provided == null) {
			return false;
		}
		byte[] expected = sign(rawBody);
		// Both operands are always 32 bytes, so isEqual does not leak the length.
		return MessageDigest.isEqual(expected, provided);
	}

	/**
	 * Returns the 32 signature bytes, or {@code null} when the header is not a
	 * well-formed {@code sha256=} hex digest. Never throws: a malformed header is
	 * an unauthenticated caller, and it gets the same answer as a wrong signature.
	 */
	private static byte[] decode(String header) {
		if (!header.startsWith(PREFIX)) {
			return null;
		}
		byte[] bytes;
		try {
			bytes = HEX.parseHex(header.substring(PREFIX.length()));
		}
		catch (IllegalArgumentException malformed) {
			return null;
		}
		return bytes.length == SHA256_LENGTH ? bytes : null;
	}

	private byte[] sign(byte[] rawBody) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(rawBody);
		}
		catch (GeneralSecurityException e) {
			// HmacSHA256 is required of every JRE; this is not a runtime condition.
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

}
