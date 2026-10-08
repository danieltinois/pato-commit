package dev.patocommit.github.installation;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of what the GitHub App can see. Like every non-webhook
 * endpoint, it is served on the loopback port only and never through the
 * webhook port a tunnel points at.
 */
@RestController
@Tag(name = "Installations", description = "Where the GitHub App is installed, and which repositories it was granted.")
public class InstallationController {

	private final InstallationQueries queries;

	InstallationController(InstallationQueries queries) {
		this.queries = queries;
	}

	@GetMapping("/api/installations")
	@Operation(summary = "List live installations and their granted repositories",
			description = "Removed installations and repositories are left out. Kept current by the installation "
					+ "webhooks and corrected by the periodic sync.")
	public List<InstallationView> list() {
		return queries.liveInstallations();
	}

}
