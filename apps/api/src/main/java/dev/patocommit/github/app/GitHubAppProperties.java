package dev.patocommit.github.app;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param appId the numeric GitHub App ID, shown on the App's settings page. Bound
 * as a number on purpose: it goes into the JWT {@code iss} claim, and a value
 * that is not a number fails the boot instead of reaching a signed token.
 * @param privateKey the App's private key, as the PEM text GitHub hands out
 * ({@code BEGIN RSA PRIVATE KEY}) or PKCS#8 ({@code BEGIN PRIVATE KEY}). Comes
 * from {@code PATO_COMMIT_GITHUB_PRIVATE_KEY} and is never defaulted. Literal
 * {@code \n} sequences are accepted, because a multi-line environment variable is
 * awkward in most shells and {@code .env} files.
 * @param apiUrl the REST API root. Only differs from the default on GitHub
 * Enterprise Server.
 */
@ConfigurationProperties("pato-commit.github-app")
record GitHubAppProperties(Long appId,
		@DefaultValue("") String privateKey,
		@DefaultValue("https://api.github.com") URI apiUrl) {

	/** Both credentials present. Until then the App cannot authenticate, and says so. */
	boolean isConfigured() {
		return appId != null && privateKey != null && !privateKey.isBlank();
	}

}
