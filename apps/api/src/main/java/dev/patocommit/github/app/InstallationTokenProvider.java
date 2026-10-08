package dev.patocommit.github.app;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hands out installation tokens, reusing each one until it is close to expiry.
 *
 * <p>In memory, on purpose. A token lives for an hour and minting a new one is a
 * single call, so losing the cache on restart costs one round trip per
 * installation. Persisting it would mean a bearer credential sitting in the
 * database for no gain.
 *
 * <p>Refreshing is serialised per installation, so a burst of webhooks for the
 * same installation produces one token request, not one per delivery. Different
 * installations never wait on each other.
 */
@Service
public class InstallationTokenProvider {

	/**
	 * A token is replaced this long before it expires. Covers a long sync that
	 * starts with a token and keeps using it, plus clock skew with GitHub.
	 */
	static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

	private static final Logger log = LoggerFactory.getLogger(InstallationTokenProvider.class);

	private final GitHubAppClient client;

	private final Clock clock;

	private final Map<Long, InstallationToken> tokens = new ConcurrentHashMap<>();

	/**
	 * One lock per installation ever seen. Never pruned: a single-user tool has a
	 * handful of installations, and a lock is a few dozen bytes.
	 */
	private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

	InstallationTokenProvider(GitHubAppClient client, Clock clock) {
		this.client = client;
		this.clock = clock;
	}

	/** A token for the installation, valid for at least {@link #REFRESH_MARGIN}. */
	public String tokenFor(long installationId) {
		// Fast path without the lock: almost every call finds a usable token.
		InstallationToken cached = tokens.get(installationId);
		if (cached != null && cached.isUsableAt(clock.instant(), REFRESH_MARGIN)) {
			return cached.token();
		}
		// A ReentrantLock rather than ConcurrentHashMap.compute: compute would hold
		// a map bin lock across a network call, stalling unrelated installations
		// that happen to hash to the same bin.
		ReentrantLock lock = locks.computeIfAbsent(installationId, id -> new ReentrantLock());
		lock.lock();
		try {
			// Checked again: another thread may have refreshed while this one waited.
			cached = tokens.get(installationId);
			if (cached != null && cached.isUsableAt(clock.instant(), REFRESH_MARGIN)) {
				return cached.token();
			}
			InstallationToken fresh = client.createInstallationToken(installationId);
			tokens.put(installationId, fresh);
			log.debug("minted installation token for installation {}, expires at {}", installationId, fresh.expiresAt());
			return fresh.token();
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * Drops the cached token, so the next call mints a new one. For when GitHub
	 * rejects a token early (401) or the installation is deleted or suspended.
	 */
	public void evict(long installationId) {
		tokens.remove(installationId);
	}

}
