package dev.patocommit.github.app;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Read access to the GitHub REST API, for other modules.
 *
 * <p>Callers name the credential, not the header: {@code AsApp} methods use the
 * App JWT, {@code AsInstallation} methods use that installation's token. The
 * item type is the caller's, so each module maps GitHub's JSON into its own
 * records and nothing here grows a model of every GitHub object.
 *
 * <p>List methods return every page or throw. There is no partial result: a
 * caller reconciling against a list must be able to trust that something
 * missing from it is missing from GitHub, not from page three.
 */
@Component
public class GitHubApi {

	/**
	 * At {@code per_page=100}, ten thousand items. A {@code next} link that never
	 * ends is a GitHub bug or a loop in this code, and either way it must stop.
	 */
	static final int MAX_PAGES = 100;

	private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");

	private final RestClient restClient;

	private final GitHubAppJwtSigner signer;

	private final InstallationTokenProvider tokens;

	private final ObjectMapper objectMapper;

	private final URI apiUrl;

	GitHubApi(RestClient gitHubRestClient, GitHubAppJwtSigner signer, InstallationTokenProvider tokens,
			ObjectMapper objectMapper, GitHubAppProperties properties) {
		this.restClient = gitHubRestClient;
		this.signer = signer;
		this.tokens = tokens;
		this.objectMapper = objectMapper;
		this.apiUrl = properties.apiUrl();
	}

	/** {@code false} until the App ID and private key are set; every other method then throws. */
	public boolean isConfigured() {
		return signer.isConfigured();
	}

	/** Every item of a list endpoint whose body is a JSON array, authenticated as the App. */
	public <T> List<T> getAllAsApp(String path, Class<T> itemType) {
		return getAll(path, () -> "Bearer " + signer.sign(), null, itemType);
	}

	/**
	 * Every item of a list endpoint whose body is an object holding the array in
	 * {@code itemsField}, as {@code /installation/repositories} does, authenticated
	 * as the installation.
	 */
	public <T> List<T> getAllAsInstallation(long installationId, String path, String itemsField, Class<T> itemType) {
		try {
			return getAll(path, () -> "Bearer " + tokens.tokenFor(installationId), itemsField, itemType);
		}
		catch (HttpClientErrorException.Unauthorized rejected) {
			// GitHub can revoke a token before its expiry. Drop it so the next call
			// mints a new one instead of failing until the cached one runs out.
			tokens.evict(installationId);
			throw rejected;
		}
	}

	/**
	 * Whether GitHub still knows the installation. Only a 404 means "no": any other
	 * failure throws, because the caller acts irreversibly on a {@code false}.
	 */
	public boolean installationExists(long installationId) {
		try {
			restClient.get()
				.uri("/app/installations/{installationId}", installationId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + signer.sign())
				.retrieve()
				.toBodilessEntity();
			return true;
		}
		catch (HttpClientErrorException.NotFound gone) {
			return false;
		}
	}

	private <T> List<T> getAll(String path, Supplier<String> authorization, String itemsField, Class<T> itemType) {
		List<T> items = new ArrayList<>();
		URI next = null;
		for (int page = 1; page <= MAX_PAGES; page++) {
			RestClient.RequestHeadersSpec<?> request = (next == null) ? restClient.get().uri(path)
					: restClient.get().uri(next);
			ResponseEntity<JsonNode> response = request.header(HttpHeaders.AUTHORIZATION, authorization.get())
				.retrieve()
				.toEntity(JsonNode.class);

			JsonNode body = response.getBody();
			JsonNode array = (body == null || itemsField == null) ? body : body.get(itemsField);
			if (array == null || !array.isArray()) {
				throw new IllegalStateException("GitHub returned an unexpected shape for " + path);
			}
			for (JsonNode item : array) {
				items.add(objectMapper.treeToValue(item, itemType));
			}

			next = nextPage(response.getHeaders().getFirst(HttpHeaders.LINK));
			if (next == null) {
				return items;
			}
		}
		throw new IllegalStateException("more than " + MAX_PAGES + " pages for " + path);
	}

	/**
	 * The {@code rel="next"} target of a {@code Link} header, or {@code null} on the
	 * last page. Refused unless it points at the configured API: the request that
	 * follows carries a bearer credential, and a link to any other host would hand
	 * that credential to it.
	 */
	private URI nextPage(String linkHeader) {
		if (linkHeader == null) {
			return null;
		}
		Matcher matcher = NEXT_LINK.matcher(linkHeader);
		if (!matcher.find()) {
			return null;
		}
		URI next = URI.create(matcher.group(1));
		if (!sameOrigin(next, apiUrl)) {
			// The URI is not quoted: it came from a response, and goes to a log.
			throw new IllegalStateException("GitHub pagination link points outside the configured API");
		}
		return next;
	}

	private static boolean sameOrigin(URI a, URI b) {
		return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
				&& a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
				&& effectivePort(a) == effectivePort(b);
	}

	private static int effectivePort(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

}
