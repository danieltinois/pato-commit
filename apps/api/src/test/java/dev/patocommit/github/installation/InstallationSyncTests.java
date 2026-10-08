package dev.patocommit.github.installation;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.http.HttpStatus;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.patocommit.github.app.GitHubApi;
import dev.patocommit.github.installation.InstallationPayload.Account;
import dev.patocommit.github.installation.InstallationPayload.Installation;
import dev.patocommit.github.installation.InstallationPayload.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The reconciliation against a real PostgreSQL, with GitHub replaced by a mock
 * of {@link GitHubApi}. Pagination and transport are covered by
 * {@code GitHubApiTests}; this is only about what the sync writes.
 *
 * <p>Unlike the other integration tests, tables are emptied before each test.
 * The sync reconciles every live installation in the database, so rows left by
 * another test would be part of what each test is asserting about.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class InstallationSyncTests {

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

	/** Wired by hand for the same reason as in {@code WebhookIntakeTests}. */
	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@MockitoBean
	private GitHubApi api;

	@Autowired
	private InstallationSync sync;

	@Autowired
	private InstallationService service;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE github_repository, github_installation").update();
		when(api.isConfigured()).thenReturn(true);
	}

	// --- what the sync adds and removes --------------------------------------

	@Test
	void recordsAnInstallationNoWebhookEverReported() {
		// The App was installed before Pato Commit first ran: no 'created' arrived.
		listInstallations(installation(10));
		listRepositories(10, repo(100, "pato/commit"), repo(101, "pato/pond"));

		sync.syncAll();

		assertThat(liveRepositories(10)).containsExactlyInAnyOrder(100L, 101L);
	}

	@Test
	void removesRepositoriesNoLongerGrantedAndRenamesTheRest() {
		created(10, repo(100, "pato/old-name"), repo(101, "pato/dropped"));
		backdate();
		listInstallations(installation(10));
		listRepositories(10, repo(100, "pato/new-name"));

		sync.syncAll();

		assertThat(liveRepositories(10)).containsExactly(100L);
		assertThat(jdbc.sql("SELECT full_name FROM github_repository WHERE repository_id = 100")
			.query(String.class)
			.single()).isEqualTo("pato/new-name");
	}

	@Test
	void removesAnInstallationOnlyWhenGitHubConfirmsItIsGone() {
		created(10, repo(100, "pato/gone"));
		created(20, repo(200, "pato/still-here"));
		backdate();
		// Neither is listed, but only installation 10 answers 404 by id.
		listInstallations();
		when(api.installationExists(10)).thenReturn(false);
		when(api.installationExists(20)).thenReturn(true);

		sync.syncAll();

		assertThat(isRemoved(10)).isTrue();
		assertThat(liveRepositories(10)).isEmpty();
		assertThat(isRemoved(20)).isFalse();
		assertThat(liveRepositories(20)).containsExactly(200L);
	}

	@Test
	void leavesASuspendedInstallationsRepositoriesAlone() {
		created(10, repo(100, "pato/commit"));
		backdate();
		listInstallations(new Installation(10, account(), "selected", Instant.parse("2026-10-08T12:00:00Z")));

		sync.syncAll();

		verify(api, never()).getAllAsInstallation(eq(10L), eq(InstallationSync.REPOSITORIES_PATH), eq("repositories"),
				eq(Repository.class));
		assertThat(liveRepositories(10)).containsExactly(100L);
	}

	@Test
	void oneFailingInstallationDoesNotStopTheOthers() {
		listInstallations(installation(10), installation(20));
		when(api.getAllAsInstallation(eq(10L), eq(InstallationSync.REPOSITORIES_PATH), eq("repositories"),
				eq(Repository.class)))
			.thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));
		listRepositories(20, repo(200, "pato/fine"));

		sync.syncAll();

		assertThat(liveRepositories(20)).containsExactly(200L);
	}

	@Test
	void aFailedInstallationListingChangesNothing() {
		created(10, repo(100, "pato/commit"));
		when(api.getAllAsApp(InstallationSync.INSTALLATIONS_PATH, Installation.class))
			.thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

		assertThatExceptionOfType(HttpServerErrorException.class).isThrownBy(sync::syncAll);

		verify(api, never()).installationExists(anyLong());
		assertThat(liveRepositories(10)).containsExactly(100L);
	}

	@Test
	void doesNothingWhileTheAppIsNotConfigured() {
		when(api.isConfigured()).thenReturn(false);

		sync.syncAll();

		verify(api).isConfigured();
		verifyNoMoreInteractions(api);
	}

	// --- racing webhooks -----------------------------------------------------
	//
	// The listing is a snapshot. A webhook processed while it was being paged saw
	// a later state of GitHub, and must win over it.

	@Test
	void aRepositoryAddedByAWebhookDuringTheListingIsKept() {
		created(10, repo(100, "pato/commit"));
		listInstallations(installation(10));
		when(api.getAllAsInstallation(eq(10L), eq(InstallationSync.REPOSITORIES_PATH), eq("repositories"),
				eq(Repository.class)))
			.thenAnswer(invocation -> {
				// GitHub serves the listing, then a repository is granted: the
				// webhook lands before the sync gets to write.
				service.applyRepositoryChanges(new InstallationPayload("added", installation(10), null,
						List.of(repo(101, "pato/new")), null));
				return List.of(repo(100, "pato/commit"));
			});

		sync.syncAll();

		assertThat(liveRepositories(10)).containsExactlyInAnyOrder(100L, 101L);
	}

	@Test
	void aRepositoryRemovedByAWebhookDuringTheListingStaysRemoved() {
		created(10, repo(100, "pato/commit"), repo(101, "pato/leaving"));
		listInstallations(installation(10));
		when(api.getAllAsInstallation(eq(10L), eq(InstallationSync.REPOSITORIES_PATH), eq("repositories"),
				eq(Repository.class)))
			.thenAnswer(invocation -> {
				service.applyRepositoryChanges(new InstallationPayload("removed", installation(10), null, null,
						List.of(repo(101, "pato/leaving"))));
				return List.of(repo(100, "pato/commit"), repo(101, "pato/leaving"));
			});

		sync.syncAll();

		assertThat(liveRepositories(10)).containsExactly(100L);
	}

	// --- helpers --------------------------------------------------------------

	private static Account account() {
		return new Account(1001, "pato", "User");
	}

	private static Installation installation(long id) {
		return new Installation(id, account(), "selected", null);
	}

	private static Repository repo(long id, String fullName) {
		return new Repository(id, fullName, true);
	}

	/** State as the webhooks left it before the sync runs. */
	private void created(long installationId, Repository... repositories) {
		service.applyInstallationEvent(new InstallationPayload("created", installation(installationId),
				List.of(repositories), null, null));
	}

	/**
	 * Moves every row's updated_at an hour back, so it is older than the listing
	 * the sync is about to take, as rows written by an earlier webhook would be.
	 * Without it, a row written a microsecond before the sync and the listing's
	 * timestamp would be too close to say which came first.
	 */
	private void backdate() {
		jdbc.sql("UPDATE github_installation SET created_at = created_at - interval '1 hour', updated_at = updated_at - interval '1 hour'").update();
		jdbc.sql("UPDATE github_repository SET created_at = created_at - interval '1 hour', updated_at = updated_at - interval '1 hour'").update();
	}

	private void listInstallations(Installation... installations) {
		when(api.getAllAsApp(InstallationSync.INSTALLATIONS_PATH, Installation.class)).thenReturn(List.of(installations));
	}

	private void listRepositories(long installationId, Repository... repositories) {
		when(api.getAllAsInstallation(eq(installationId), eq(InstallationSync.REPOSITORIES_PATH), eq("repositories"),
				eq(Repository.class)))
			.thenReturn(List.of(repositories));
	}

	private List<Long> liveRepositories(long installationId) {
		return jdbc.sql("SELECT repository_id FROM github_repository WHERE installation_id = :id AND removed_at IS NULL")
			.param("id", installationId)
			.query(Long.class)
			.list();
	}

	private boolean isRemoved(long installationId) {
		return jdbc.sql("SELECT removed_at IS NOT NULL FROM github_installation WHERE installation_id = :id")
			.param("id", installationId)
			.query(Boolean.class)
			.single();
	}

}
