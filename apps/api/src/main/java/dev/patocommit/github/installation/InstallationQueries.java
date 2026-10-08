package dev.patocommit.github.installation;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import dev.patocommit.github.installation.InstallationView.RepositoryView;

/**
 * Read side of the installation tables. Kept apart from {@link InstallationStore}
 * so the write path, with its locks and its ordering rules, is not where a
 * screen's query lives.
 */
@Repository
class InstallationQueries {

	private final JdbcClient jdbc;

	InstallationQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Live installations with their live repositories. One query rather than one
	 * per installation; the LEFT JOIN keeps an installation that has no
	 * repositories granted.
	 */
	List<InstallationView> liveInstallations() {
		Map<Long, InstallationView> installations = new LinkedHashMap<>();
		Map<Long, List<RepositoryView>> repositories = new LinkedHashMap<>();
		jdbc.sql("""
						SELECT i.installation_id, i.account_login, i.account_type, i.repository_selection, i.suspended_at,
						       r.repository_id, r.full_name, r.private
						  FROM github_installation i
						  LEFT JOIN github_repository r
						         ON r.installation_id = i.installation_id
						        AND r.removed_at IS NULL
						 WHERE i.removed_at IS NULL
						 ORDER BY i.account_login, i.installation_id, r.full_name
						""")
			.query(rs -> {
				long installationId = rs.getLong("installation_id");
				if (!installations.containsKey(installationId)) {
					OffsetDateTime suspendedAt = rs.getObject("suspended_at", OffsetDateTime.class);
					installations.put(installationId, new InstallationView(installationId, rs.getString("account_login"),
							rs.getString("account_type"), rs.getString("repository_selection"),
							suspendedAt == null ? null : suspendedAt.toInstant(), List.of()));
					repositories.put(installationId, new ArrayList<>());
				}
				Long repositoryId = rs.getObject("repository_id", Long.class);
				if (repositoryId != null) {
					repositories.get(installationId)
						.add(new RepositoryView(repositoryId, rs.getString("full_name"), rs.getBoolean("private")));
				}
			});
		return installations.values()
			.stream()
			.map(i -> new InstallationView(i.installationId(), i.accountLogin(), i.accountType(),
					i.repositorySelection(), i.suspendedAt(), List.copyOf(repositories.get(i.installationId()))))
			.toList();
	}

}
