package dev.patocommit.github.installation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.patocommit.github.installation.InstallationPayload.Installation;

/**
 * Persistence for {@code github_installation} and {@code github_repository}.
 * Named a store rather than a repository, to spare everyone a
 * {@code GitHubRepositoryRepository}.
 *
 * <p>Every write is an upsert or a conditional update, so applying the same
 * event twice leaves the same rows. Every operation also starts by writing the
 * installation row, which takes its row lock: two events for one installation
 * processed at the same moment serialise there, always in the same lock order,
 * and cannot deadlock on the repository rows.
 */
@Repository
class InstallationStore {

	private final JdbcClient jdbc;

	InstallationStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Writes the installation as the payload describes it. Returns {@code false},
	 * and changes nothing, when the installation was already removed.
	 *
	 * <p>That refusal is what makes {@code deleted} final. GitHub never reuses an
	 * installation id, so any event for a removed id that arrives late, a delayed
	 * {@code created} or {@code added}, describes a past that no longer exists.
	 * The {@code WHERE} on the conflict update is checked under the row lock, so
	 * the answer cannot go stale before this transaction commits.
	 */
	boolean upsertLive(Installation installation) {
		return !jdbc.sql("""
						INSERT INTO github_installation
						    (installation_id, account_id, account_login, account_type, repository_selection, suspended_at)
						VALUES (:id, :accountId, :accountLogin, :accountType, :selection, :suspendedAt)
						ON CONFLICT (installation_id) DO UPDATE
						   SET account_id           = EXCLUDED.account_id,
						       account_login        = EXCLUDED.account_login,
						       account_type         = EXCLUDED.account_type,
						       repository_selection = EXCLUDED.repository_selection,
						       suspended_at         = EXCLUDED.suspended_at,
						       updated_at           = now()
						 WHERE github_installation.removed_at IS NULL
						RETURNING installation_id
						""")
			.params(installationParams(installation))
			.query(Long.class)
			.list()
			.isEmpty();
	}

	/**
	 * Marks the installation and all its live repositories removed. Creates the
	 * installation row when this is the first event seen for it, so that a
	 * {@code created} delivered after the {@code deleted} is refused too.
	 * {@code COALESCE} keeps the first removal time on a redelivery.
	 */
	void markRemoved(Installation installation) {
		jdbc.sql("""
						INSERT INTO github_installation
						    (installation_id, account_id, account_login, account_type, repository_selection,
						     suspended_at, removed_at)
						VALUES (:id, :accountId, :accountLogin, :accountType, :selection, :suspendedAt, now())
						ON CONFLICT (installation_id) DO UPDATE
						   SET removed_at = COALESCE(github_installation.removed_at, now()),
						       updated_at = now()
						""")
			.params(installationParams(installation))
			.update();
		markRepositoriesRemoved(installation.id());
	}

	/**
	 * Marks a known installation and its repositories removed, for when the sync
	 * has confirmed GitHub no longer has it. Same finality as {@link #markRemoved}.
	 */
	void markRemoved(long installationId) {
		jdbc.sql("""
						UPDATE github_installation
						   SET removed_at = COALESCE(removed_at, now()),
						       updated_at = now()
						 WHERE installation_id = :id
						""")
			.param("id", installationId)
			.update();
		markRepositoriesRemoved(installationId);
	}

	private void markRepositoriesRemoved(long installationId) {
		jdbc.sql("""
						UPDATE github_repository
						   SET removed_at = now(),
						       updated_at = now()
						 WHERE installation_id = :id
						   AND removed_at IS NULL
						""")
			.param("id", installationId)
			.update();
	}

	// --- sync -------------------------------------------------------------------
	//
	// The sync reads a list from GitHub and then writes it. A webhook processed in
	// between carries newer information than that list. So every sync write is
	// conditional on the row not having changed since the listing started:
	// updated_at < listedAt. Both sides of that comparison come from the database
	// clock (see databaseNow), so there is no skew between them.

	/** The database's notion of now, for stamping when a sync listing started. */
	Instant databaseNow() {
		return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
	}

	List<Long> liveInstallationIds() {
		return jdbc.sql("SELECT installation_id FROM github_installation WHERE removed_at IS NULL")
			.query(Long.class)
			.list();
	}

	/**
	 * Like {@link #upsertLive}, for a listing taken at {@code listedAt}: inserts an
	 * installation whose {@code created} webhook was missed, and refreshes one that
	 * no webhook has touched since the listing started.
	 */
	void refreshFromSync(Installation installation, Instant listedAt) {
		Map<String, Object> params = installationParams(installation);
		params.put("listedAt", utc(listedAt));
		jdbc.sql("""
						INSERT INTO github_installation
						    (installation_id, account_id, account_login, account_type, repository_selection, suspended_at)
						VALUES (:id, :accountId, :accountLogin, :accountType, :selection, :suspendedAt)
						ON CONFLICT (installation_id) DO UPDATE
						   SET account_id           = EXCLUDED.account_id,
						       account_login        = EXCLUDED.account_login,
						       account_type         = EXCLUDED.account_type,
						       repository_selection = EXCLUDED.repository_selection,
						       suspended_at         = EXCLUDED.suspended_at,
						       updated_at           = now()
						 WHERE github_installation.removed_at IS NULL
						   AND github_installation.updated_at < :listedAt
						""")
			.params(params)
			.update();
	}

	/**
	 * Takes the installation's row lock for the rest of the transaction, the same
	 * lock every webhook write starts with. Returns {@code false} when it is
	 * removed, or unknown.
	 */
	boolean lockLive(long installationId) {
		return jdbc.sql("""
						SELECT installation_id
						  FROM github_installation
						 WHERE installation_id = :id
						   AND removed_at IS NULL
						   FOR UPDATE
						""")
			.param("id", installationId)
			.query(Long.class)
			.optional()
			.isPresent();
	}

	List<Long> liveRepositoryIds(long installationId) {
		return jdbc.sql("SELECT repository_id FROM github_repository WHERE installation_id = :id AND removed_at IS NULL")
			.param("id", installationId)
			.query(Long.class)
			.list();
	}

	/** {@link #upsertRepository}, unless a webhook changed the row after {@code listedAt}. */
	void upsertRepositoryFromSync(long installationId, InstallationPayload.Repository repository, Instant listedAt) {
		jdbc.sql("""
						INSERT INTO github_repository (repository_id, installation_id, full_name, private)
						VALUES (:id, :installationId, :fullName, :private)
						ON CONFLICT (repository_id) DO UPDATE
						   SET installation_id = EXCLUDED.installation_id,
						       full_name       = EXCLUDED.full_name,
						       private         = EXCLUDED.private,
						       removed_at      = NULL,
						       updated_at      = now()
						 WHERE github_repository.updated_at < :listedAt
						""")
			.param("id", repository.id())
			.param("installationId", installationId)
			.param("fullName", repository.fullName())
			.param("private", repository.isPrivate())
			.param("listedAt", utc(listedAt))
			.update();
	}

	/** {@link #markRepositoryRemoved}, unless a webhook changed the row after {@code listedAt}. */
	void markRepositoryRemovedFromSync(long installationId, long repositoryId, Instant listedAt) {
		jdbc.sql("""
						UPDATE github_repository
						   SET removed_at = now(),
						       updated_at = now()
						 WHERE repository_id = :id
						   AND installation_id = :installationId
						   AND removed_at IS NULL
						   AND updated_at < :listedAt
						""")
			.param("id", repositoryId)
			.param("installationId", installationId)
			.param("listedAt", utc(listedAt))
			.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	/**
	 * Attaches the repository to the installation. On a known id this also
	 * renames it, undoes an earlier removal, and moves it from a previous
	 * installation after a reinstall.
	 */
	void upsertRepository(long installationId, InstallationPayload.Repository repository) {
		jdbc.sql("""
						INSERT INTO github_repository (repository_id, installation_id, full_name, private)
						VALUES (:id, :installationId, :fullName, :private)
						ON CONFLICT (repository_id) DO UPDATE
						   SET installation_id = EXCLUDED.installation_id,
						       full_name       = EXCLUDED.full_name,
						       private         = EXCLUDED.private,
						       removed_at      = NULL,
						       updated_at      = now()
						""")
			.param("id", repository.id())
			.param("installationId", installationId)
			.param("fullName", repository.fullName())
			.param("private", repository.isPrivate())
			.update();
	}

	/**
	 * Marks the repository removed, but only from this installation. If it has
	 * since moved to another one, a late removal from the old installation must
	 * not detach it from the new.
	 */
	void markRepositoryRemoved(long installationId, long repositoryId) {
		jdbc.sql("""
						UPDATE github_repository
						   SET removed_at = now(),
						       updated_at = now()
						 WHERE repository_id = :id
						   AND installation_id = :installationId
						   AND removed_at IS NULL
						""")
			.param("id", repositoryId)
			.param("installationId", installationId)
			.update();
	}

	private static Map<String, Object> installationParams(Installation installation) {
		// HashMap rather than Map.of: suspended_at is null on every live installation.
		Map<String, Object> params = new HashMap<>();
		params.put("id", installation.id());
		params.put("accountId", installation.account().id());
		params.put("accountLogin", installation.account().login());
		params.put("accountType", installation.account().type());
		params.put("selection", installation.repositorySelection());
		params.put("suspendedAt", installation.suspendedAt() == null ? null
				: OffsetDateTime.ofInstant(installation.suspendedAt(), ZoneOffset.UTC));
		return params;
	}

}
