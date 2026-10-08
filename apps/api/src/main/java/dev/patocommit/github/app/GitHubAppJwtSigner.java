package dev.patocommit.github.app;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signs the JWT a GitHub App uses to authenticate as itself.
 *
 * <p>This token proves "I am App N" and nothing more: it can list installations
 * and mint installation tokens, but it cannot read a single repository. Every
 * repository call goes through an installation token, which is what least
 * privilege looks like for a GitHub App.
 *
 * <p>Hand-rolled rather than a JWT library. The token has a fixed header, three
 * numeric claims and one algorithm, and every part of it is checked by GitHub on
 * every call, so there is nothing a library would add except a dependency.
 *
 * @see <a href="https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-json-web-token-jwt-for-a-github-app">Generating a JWT for a GitHub App</a>
 */
@Component
class GitHubAppJwtSigner {

	/**
	 * Backdates {@code iat}. GitHub rejects a token issued "in the future", and the
	 * local clock and GitHub's are never exactly in step.
	 */
	static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

	/** GitHub's ceiling is ten minutes; one minute of margin for the same skew. */
	static final Duration LIFETIME = Duration.ofMinutes(9);

	private static final Logger log = LoggerFactory.getLogger(GitHubAppJwtSigner.class);

	private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

	private static final String HEADER = BASE64URL
			.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

	private final Clock clock;

	/** {@code null} until the App is configured. */
	private final Long appId;

	/** {@code null} until the App is configured. */
	private final RSAPrivateKey privateKey;

	GitHubAppJwtSigner(GitHubAppProperties properties, Clock clock) {
		this.clock = clock;
		if (properties.isConfigured()) {
			this.appId = properties.appId();
			// Parsed once, at startup: a broken key fails the boot instead of the
			// first webhook that needs to call GitHub.
			this.privateKey = PrivateKeyPem.parse(properties.privateKey());
		}
		else {
			this.appId = null;
			this.privateKey = null;
			log.warn("No GitHub App configured: calls to the GitHub API are disabled. "
					+ "Set PATO_COMMIT_GITHUB_APP_ID and PATO_COMMIT_GITHUB_PRIVATE_KEY to enable them.");
		}
	}

	boolean isConfigured() {
		return privateKey != null;
	}

	/** A fresh token on every call. Signing is cheap; caching it would buy nothing. */
	String sign() {
		if (!isConfigured()) {
			throw new IllegalStateException("GitHub App is not configured");
		}
		Instant now = clock.instant();
		// appId is a Long, so formatting it into JSON cannot inject anything.
		String claims = "{\"iat\":%d,\"exp\":%d,\"iss\":\"%d\"}".formatted(
				now.minus(CLOCK_SKEW).getEpochSecond(), now.plus(LIFETIME).getEpochSecond(), appId);
		String signingInput = HEADER + "." + BASE64URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
		return signingInput + "." + BASE64URL.encodeToString(rs256(signingInput));
	}

	private byte[] rs256(String signingInput) {
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initSign(privateKey);
			signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
			return signature.sign();
		}
		catch (GeneralSecurityException e) {
			// SHA256withRSA is required of every JRE, and the key was validated at
			// startup; reaching this is a bug, not a runtime condition.
			throw new IllegalStateException("RS256 signing failed", e);
		}
	}

}
