package dev.patocommit.github.installation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.patocommit.github.app.GitHubApi;
import dev.patocommit.github.app.InstallationTokenProvider;
import dev.patocommit.github.installation.InstallationPayload.Installation;
import dev.patocommit.github.installation.InstallationPayload.Repository;

/**
 * Reconciles installations and repositories against the GitHub API.
 *
 * <p>Webhooks keep the data current; this makes it correct. It catches what no
 * webhook reported: events lost while Pato Commit was down, the installation
 * that existed before the App was first pointed here, and the rare reordering
 * the handlers cannot resolve on their own.
 *
 * <p>The two removals are treated differently on purpose. A repository missing
 * from a complete listing is marked removed straight away, since the next
 * listing that includes it again undoes that. An installation missing from the
 * listing is removed only after GitHub answers 404 for it by id, because removal
 * of an installation is final and an ambiguous listing must not cause it.
 */
@Component
class InstallationSync {

	static final String INSTALLATIONS_PATH = "/app/installations?per_page=100";

	static final String REPOSITORIES_PATH = "/installation/repositories?per_page=100";

	private static final Logger log = LoggerFactory.getLogger(InstallationSync.class);

	private final GitHubApi api;

	private final InstallationStore store;

	private final InstallationService service;

	private final InstallationTokenProvider tokens;

	InstallationSync(GitHubApi api, InstallationStore store, InstallationService service,
			InstallationTokenProvider tokens) {
		this.api = api;
		this.store = store;
		this.service = service;
		this.tokens = tokens;
	}

	/**
	 * One full pass. A failure on one installation is logged and the pass moves
	 * on; a failure to list installations at all aborts it, and changes nothing.
	 */
	void syncAll() {
		if (!api.isConfigured()) {
			log.debug("installation sync skipped: no GitHub App configured");
			return;
		}
		Instant listedAt = store.databaseNow();
		List<Installation> listed = api.getAllAsApp(INSTALLATIONS_PATH, Installation.class);

		Set<Long> listedIds = new HashSet<>();
		for (Installation installation : listed) {
			listedIds.add(installation.id());
			store.refreshFromSync(installation, listedAt);
		}
		removeConfirmedGone(listedIds);

		int failed = 0;
		for (Installation installation : listed) {
			if (installation.suspendedAt() != null) {
				// GitHub refuses tokens for a suspended installation. Its repositories
				// are kept as they were and synced again after unsuspend.
				continue;
			}
			try {
				syncRepositories(installation.id());
			}
			catch (RuntimeException e) {
				failed++;
				log.warn("repository sync failed for installation {}; the others continue", installation.id(), e);
			}
		}
		log.info("installation sync done: {} installations listed, {} failed", listed.size(), failed);
	}

	private void removeConfirmedGone(Set<Long> listedIds) {
		for (long installationId : store.liveInstallationIds()) {
			if (listedIds.contains(installationId)) {
				continue;
			}
			if (api.installationExists(installationId)) {
				// Listed nowhere but still there: a listing GitHub served stale, or one
				// that changed while it was paged. Not evidence enough to act on.
				log.warn("installation {} missing from the listing but still exists; left as is", installationId);
				continue;
			}
			store.markRemoved(installationId);
			tokens.evict(installationId);
			log.info("installation {} no longer exists on GitHub; removed", installationId);
		}
	}

	private void syncRepositories(long installationId) {
		// Stamped before the listing, not after: anything written later than this
		// is newer than what the listing can show.
		Instant listedAt = store.databaseNow();
		List<Repository> repositories = api.getAllAsInstallation(installationId, REPOSITORIES_PATH, "repositories",
				Repository.class);
		service.reconcileRepositories(installationId, repositories, listedAt);
	}

}
