package dev.patocommit.github.webhook;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param secret GitHub webhook secret. Never defaulted to a real value: it comes
 * from {@code PATO_COMMIT_GITHUB_WEBHOOK_SECRET}. While it is blank the intake
 * rejects every delivery, which is why the app still boots before the GitHub App
 * exists in M2.
 * @param staleAfter how long a delivery may sit in {@code PENDING}, or in
 * {@code PROCESSING} after being claimed, before the sweeper may take it again.
 * Must exceed the worst-case handler runtime or healthy work gets re-run.
 * @param maxAttempts total claims before a delivery is parked in {@code FAILED}.
 */
@ConfigurationProperties("pato-commit.webhook")
record WebhookProperties(@DefaultValue("") String secret,
		@DefaultValue("PT5M") Duration staleAfter,
		@DefaultValue("3") int maxAttempts,
		@DefaultValue("50") int recoveryBatchSize) {

}
