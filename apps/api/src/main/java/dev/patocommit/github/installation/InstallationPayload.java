package dev.patocommit.github.installation;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The part of an {@code installation} or {@code installation_repositories}
 * payload that Pato Commit reads. Everything else GitHub sends is ignored, so a
 * field GitHub adds tomorrow cannot break parsing.
 *
 * <p>Every one of these payloads carries the full {@code installation} object,
 * which is what makes each event self-sufficient: a handler can create the
 * installation row from whichever event happens to be processed first.
 *
 * @param repositories set on {@code installation.created} and {@code .deleted}
 * @param repositoriesAdded set on {@code installation_repositories.added}
 * @param repositoriesRemoved set on {@code installation_repositories.removed}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record InstallationPayload(String action,
		Installation installation,
		List<Repository> repositories,
		@JsonProperty("repositories_added") List<Repository> repositoriesAdded,
		@JsonProperty("repositories_removed") List<Repository> repositoriesRemoved) {

	InstallationPayload {
		// Malformed rather than empty: without the installation there is nothing to
		// key any write on. The delivery fails, and stays visible as FAILED.
		if (installation == null || installation.id() <= 0 || installation.account() == null
				|| installation.account().login() == null || installation.account().type() == null) {
			throw new IllegalArgumentException("payload has no usable installation object");
		}
		repositories = repositories == null ? List.of() : repositories;
		repositoriesAdded = repositoriesAdded == null ? List.of() : repositoriesAdded;
		repositoriesRemoved = repositoriesRemoved == null ? List.of() : repositoriesRemoved;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Installation(long id,
			Account account,
			@JsonProperty("repository_selection") String repositorySelection,
			@JsonProperty("suspended_at") Instant suspendedAt) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Account(long id, String login, String type) {

	}

	/** The short repository object installation events carry: no default branch, no archived flag. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Repository(long id,
			@JsonProperty("full_name") String fullName,
			@JsonProperty("private") boolean isPrivate) {

	}

}
