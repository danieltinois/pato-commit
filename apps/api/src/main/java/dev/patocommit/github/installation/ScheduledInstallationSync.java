package dev.patocommit.github.installation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the sync shortly after startup, then on a fixed delay.
 *
 * <p>The first run is what turns an App installed before Pato Commit ever ran
 * into rows, since no {@code created} webhook will arrive for it. Fixed delay,
 * not fixed rate: a slow pass delays the next one instead of overlapping it.
 */
@Component
class ScheduledInstallationSync {

	private static final Logger log = LoggerFactory.getLogger(ScheduledInstallationSync.class);

	private final InstallationSync sync;

	ScheduledInstallationSync(InstallationSync sync) {
		this.sync = sync;
	}

	@Scheduled(fixedDelayString = "${pato-commit.sync.interval:PT6H}", initialDelayString = "${pato-commit.sync.initial-delay:PT10S}")
	void run() {
		try {
			sync.syncAll();
		}
		catch (RuntimeException e) {
			// Same reasoning as the webhook sweeper: a scheduled task that throws is
			// cancelled, and losing the sync would leave drift uncorrected for good.
			log.error("installation sync failed; it stays scheduled", e);
		}
	}

}
