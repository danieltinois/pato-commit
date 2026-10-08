package dev.patocommit.github.installation;

import java.time.Instant;
import java.util.List;

/**
 * What the API returns for an installation. A separate type from the webhook
 * payload records on purpose: those follow GitHub's JSON, this follows what a
 * client of Pato Commit needs, and each can change without the other.
 *
 * @param suspendedAt set while the installation is suspended; its repositories
 * are then not synced.
 * @param repositories only the ones still granted, ordered by name.
 */
public record InstallationView(long installationId,
		String accountLogin,
		String accountType,
		String repositorySelection,
		Instant suspendedAt,
		List<RepositoryView> repositories) {

	public record RepositoryView(long repositoryId, String fullName, boolean isPrivate) {

	}

}
