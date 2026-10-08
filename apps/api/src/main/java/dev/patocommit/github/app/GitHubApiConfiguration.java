package dev.patocommit.github.app;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The HTTP client every call to the GitHub REST API goes through. */
@Configuration(proxyBeanMethods = false)
class GitHubApiConfiguration {

	/**
	 * Explicit, because the JDK default is to wait forever. A GitHub that stops
	 * answering would otherwise hold a worker thread, and the delivery it is
	 * processing, until the sweeper decides it is stale.
	 */
	static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

	static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

	/** Pinned so a GitHub-side default change cannot alter response shapes under us. */
	static final String API_VERSION = "2022-11-28";

	@Bean
	RestClient gitHubRestClient(RestClient.Builder builder, GitHubAppProperties properties) {
		// clone(): the auto-configured builder is shared, and this client's base URL
		// and headers must not leak into anyone else's.
		return configure(builder.clone(), properties).build();
	}

	/** Shared with the tests, which bind a mock server to the same configuration. */
	static RestClient.Builder configure(RestClient.Builder builder, GitHubAppProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return builder.baseUrl(properties.apiUrl().toString())
			.requestFactory(requestFactory)
			.defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
			.defaultHeader("X-GitHub-Api-Version", API_VERSION)
			// GitHub rejects requests without a User-Agent.
			.defaultHeader(HttpHeaders.USER_AGENT, "pato-commit");
	}

}
