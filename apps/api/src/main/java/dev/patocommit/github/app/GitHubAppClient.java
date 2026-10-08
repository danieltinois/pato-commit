package dev.patocommit.github.app;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls GitHub as the App itself, authenticated by the App JWT.
 *
 * <p>Deliberately small: the App identity only ever needs to be used to obtain
 * an installation token. Everything that touches a repository happens with that
 * token instead, so a bug elsewhere cannot accidentally act with App-wide reach.
 */
@Component
class GitHubAppClient {

	private final RestClient restClient;

	private final GitHubAppJwtSigner signer;

	GitHubAppClient(RestClient gitHubRestClient, GitHubAppJwtSigner signer) {
		this.restClient = gitHubRestClient;
		this.signer = signer;
	}

	/**
	 * Mints a new installation token. Each call is a round trip to GitHub and a new
	 * token, so callers go through {@link InstallationTokenProvider}, which caches.
	 *
	 * @throws org.springframework.web.client.RestClientResponseException on any
	 * non-2xx answer, notably 404 when the installation no longer exists.
	 */
	InstallationToken createInstallationToken(long installationId) {
		AccessTokenResponse response = restClient.post()
			.uri("/app/installations/{installationId}/access_tokens", installationId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + signer.sign())
			.retrieve()
			.body(AccessTokenResponse.class);
		if (response == null || response.token() == null || response.expiresAt() == null) {
			throw new IllegalStateException("GitHub returned an incomplete installation token response");
		}
		return new InstallationToken(response.token(), response.expiresAt());
	}

	/**
	 * The two fields used out of GitHub's response; {@code permissions} and
	 * {@code repository_selection} are there too, and ignored for now.
	 */
	private record AccessTokenResponse(String token, @JsonProperty("expires_at") Instant expiresAt) {

		@Override
		public String toString() {
			return "AccessTokenResponse[token=<redacted>, expiresAt=" + expiresAt + "]";
		}

	}

}
