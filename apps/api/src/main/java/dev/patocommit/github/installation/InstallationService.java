package dev.patocommit.github.installation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.patocommit.github.app.InstallationTokenProvider;

/**
 * Applies installation events to the database.
 *
 * <p>Each event is one transaction: an event that adds five repositories adds
 * all five or none. A half-applied event would be retried anyway, and the
 * upserts would converge, but in between the inbox would show a state GitHub
 * never had.
 *
 * <p>Webhooks are treated as timely hints, not as the source of truth. They can
 * be delayed, reordered, or lost when the App is down. The repository sync is
 * what reconciles against GitHub; these handlers keep the data current between
 * syncs.
 */
@Service
class InstallationService {

	private static final Logger log = LoggerFactory.getLogger(InstallationService.class);

	private final InstallationStore store;

	private final InstallationTokenProvider tokens;

	InstallationService(InstallationStore store, InstallationTokenProvider tokens) {
		this.store = store;
		this.tokens = tokens;
	}

	/**
	 * {@code installation}: {@code created}, {@code deleted}, {@code suspend},
	 * {@code unsuspend}, {@code new_permissions_accepted}. Anything other than
	 * {@code deleted} carries the installation's current state, so all of them
	 * are the same upsert.
	 */
	@Transactional
	void applyInstallationEvent(InstallationPayload payload) {
		long installationId = payload.installation().id();
		if ("deleted".equals(payload.action())) {
			store.markRemoved(payload.installation());
			// A token for a deleted installation is useless; one for a suspended
			// one is refused by GitHub and replaced on the next use.
			tokens.evict(installationId);
			log.info("installation {} removed", installationId);
			return;
		}
		if (!store.upsertLive(payload.installation())) {
			log.info("installation {} already removed; late '{}' event ignored", installationId, payload.action());
			return;
		}
		if ("created".equals(payload.action())) {
			payload.repositories().forEach(repository -> store.upsertRepository(installationId, repository));
			log.info("installation {} created with {} repositories", installationId, payload.repositories().size());
		}
		else {
			log.info("installation {} updated by '{}'", installationId, payload.action());
		}
	}

	/** {@code installation_repositories}: {@code added} and {@code removed}. */
	@Transactional
	void applyRepositoryChanges(InstallationPayload payload) {
		long installationId = payload.installation().id();
		if (!store.upsertLive(payload.installation())) {
			log.info("installation {} already removed; late repository change ignored", installationId);
			return;
		}
		payload.repositoriesAdded().forEach(repository -> store.upsertRepository(installationId, repository));
		payload.repositoriesRemoved().forEach(repository -> store.markRepositoryRemoved(installationId, repository.id()));
		log.info("installation {}: {} repositories added, {} removed", installationId,
				payload.repositoriesAdded().size(), payload.repositoriesRemoved().size());
	}

	/**
	 * Makes the installation's live repositories match a complete listing taken at
	 * {@code listedAt}. Rows a webhook changed after that moment are left alone:
	 * the webhook saw a later state of GitHub than the listing did.
	 *
	 * <p>Unlike removing an installation, removing a repository here is safe to
	 * get wrong: the next webhook or sync that lists it again undoes it.
	 */
	@Transactional
	void reconcileRepositories(long installationId, List<InstallationPayload.Repository> listed, Instant listedAt) {
		if (!store.lockLive(installationId)) {
			return;
		}
		Set<Long> listedIds = new HashSet<>();
		for (InstallationPayload.Repository repository : listed) {
			listedIds.add(repository.id());
			store.upsertRepositoryFromSync(installationId, repository, listedAt);
		}
		int removed = 0;
		for (long repositoryId : store.liveRepositoryIds(installationId)) {
			if (!listedIds.contains(repositoryId)) {
				store.markRepositoryRemovedFromSync(installationId, repositoryId, listedAt);
				removed++;
			}
		}
		log.info("installation {} synced: {} repositories listed, {} no longer granted", installationId, listed.size(),
				removed);
	}

}
