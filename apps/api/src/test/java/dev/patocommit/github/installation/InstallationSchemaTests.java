package dev.patocommit.github.installation;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * V2 against a real PostgreSQL, migrated by Flyway. Each test states one schema
 * decision, so changing a decision means changing a test that names it.
 *
 * <p>Ids are random per test instead of cleaning tables between tests, the same
 * way the webhook tests isolate themselves.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class InstallationSchemaTests {

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

	/** Wired by hand for the same reason as in {@code WebhookIntakeTests}. */
	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired
	private JdbcClient jdbc;

	private static long randomId() {
		return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
	}

	private void insertInstallation(long installationId, long accountId, String accountType) {
		jdbc.sql("""
				INSERT INTO github_installation
				    (installation_id, account_id, account_login, account_type, repository_selection)
				VALUES (:id, :accountId, 'pato', :accountType, 'all')
				""")
			.param("id", installationId)
			.param("accountId", accountId)
			.param("accountType", accountType)
			.update();
	}

	/** The upsert the installation handlers will use: last write wins, removal is undone. */
	private void upsertRepository(long repositoryId, long installationId, String fullName) {
		jdbc.sql("""
				INSERT INTO github_repository (repository_id, installation_id, full_name, private)
				VALUES (:id, :installationId, :fullName, true)
				ON CONFLICT (repository_id) DO UPDATE
				   SET installation_id = EXCLUDED.installation_id,
				       full_name       = EXCLUDED.full_name,
				       removed_at      = NULL,
				       updated_at      = now()
				""")
			.param("id", repositoryId)
			.param("installationId", installationId)
			.param("fullName", fullName)
			.update();
	}

	@Test
	void aReinstallMovesTheRepositoryInsteadOfDuplicatingIt() {
		long accountId = randomId();
		long firstInstallation = randomId();
		long secondInstallation = randomId();
		long repositoryId = randomId();

		insertInstallation(firstInstallation, accountId, "User");
		upsertRepository(repositoryId, firstInstallation, "pato/commit");
		jdbc.sql("UPDATE github_installation SET removed_at = now() WHERE installation_id = :id")
			.param("id", firstInstallation)
			.update();

		// Same account installs the App again: GitHub issues a new installation id.
		insertInstallation(secondInstallation, accountId, "User");
		upsertRepository(repositoryId, secondInstallation, "pato/commit");

		Long owner = jdbc.sql("SELECT installation_id FROM github_repository WHERE repository_id = :id")
			.param("id", repositoryId)
			.query(Long.class)
			.single();
		assertThat(owner).isEqualTo(secondInstallation);
	}

	@Test
	void aRenameKeepsTheSameRow() {
		long installationId = randomId();
		long repositoryId = randomId();
		insertInstallation(installationId, randomId(), "User");

		upsertRepository(repositoryId, installationId, "pato/old-name");
		upsertRepository(repositoryId, installationId, "pato/new-name");

		String fullName = jdbc.sql("SELECT full_name FROM github_repository WHERE repository_id = :id")
			.param("id", repositoryId)
			.query(String.class)
			.single();
		assertThat(fullName).isEqualTo("pato/new-name");
	}

	@Test
	void twoRepositoriesMayBrieflyShareAFullName() {
		// A rename to 'pato/a' can be processed before the event that renamed the
		// previous 'pato/a' away. A unique full_name would fail that delivery.
		long installationId = randomId();
		insertInstallation(installationId, randomId(), "User");

		upsertRepository(randomId(), installationId, "pato/a");
		upsertRepository(randomId(), installationId, "pato/a");
	}

	@Test
	void rejectsARepositoryOfAnUnknownInstallation() {
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> upsertRepository(randomId(), randomId(), "pato/orphan"));
	}

	@Test
	void anInstallationWithRepositoriesCannotBeHardDeleted() {
		long installationId = randomId();
		insertInstallation(installationId, randomId(), "Organization");
		upsertRepository(randomId(), installationId, "pato/commit");

		// The foreign key backs up the "nothing is deleted" rule: removal is
		// removed_at, and a DELETE that would orphan repositories fails.
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> jdbc.sql("DELETE FROM github_installation WHERE installation_id = :id")
				.param("id", installationId)
				.update());
	}

	@Test
	void rejectsAFullNameThatIsNotOwnerSlashName() {
		long installationId = randomId();
		insertInstallation(installationId, randomId(), "User");

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> upsertRepository(randomId(), installationId, "no-slash"));
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> upsertRepository(randomId(), installationId, "/name"));
	}

	@Test
	void acceptsAnAccountTypeThisCodeHasNeverSeen() {
		// GitHub's vocabulary is not constrained: a new account type must not turn
		// a valid delivery into a failed one.
		long installationId = randomId();
		insertInstallation(installationId, randomId(), "Enterprise");

		assertThat(jdbc.sql("SELECT count(*) FROM github_installation WHERE installation_id = :id")
			.param("id", installationId)
			.query(Long.class)
			.single()).isEqualTo(1L);
	}

}
