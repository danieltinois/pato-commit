package dev.patocommit.github.app;

import java.time.Duration;
import java.time.Instant;

/**
 * A short-lived token that acts as one installation of the App: it can read the
 * repositories that installation was granted, and nothing else.
 *
 * @param token the bearer value. Never logged, and {@link #toString()} hides it,
 * because a record's default {@code toString} would print it the first time
 * anyone logged the object.
 * @param expiresAt when GitHub stops accepting it, about an hour after issue.
 */
record InstallationToken(String token, Instant expiresAt) {

	/** Still valid for at least {@code margin} from {@code now}. */
	boolean isUsableAt(Instant now, Duration margin) {
		return now.plus(margin).isBefore(expiresAt);
	}

	@Override
	public String toString() {
		return "InstallationToken[token=<redacted>, expiresAt=" + expiresAt + "]";
	}

}
