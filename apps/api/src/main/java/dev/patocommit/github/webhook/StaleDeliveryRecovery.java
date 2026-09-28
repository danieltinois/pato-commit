package dev.patocommit.github.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers deliveries that no one is working on any more.
 *
 * <p>Two cases, one query. A {@code PENDING} row older than the timeout was
 * never picked up, which means the process died between committing the delivery
 * and dispatching it. A {@code PROCESSING} row older than the timeout was claimed
 * and then abandoned, which means the process died mid-handler. Without this
 * sweep a crash would leave either row pending forever.
 */
@Component
class StaleDeliveryRecovery {

	private static final Logger log = LoggerFactory.getLogger(StaleDeliveryRecovery.class);

	private final WebhookProcessor processor;

	StaleDeliveryRecovery(WebhookProcessor processor) {
		this.processor = processor;
	}

	@Scheduled(fixedDelayString = "${pato-commit.webhook.recovery-interval:PT30S}", initialDelayString = "${pato-commit.webhook.recovery-interval:PT30S}")
	void recover() {
		try {
			int recovered = processor.recoverStale();
			if (recovered > 0) {
				log.info("recovered {} stale webhook deliveries", recovered);
			}
		}
		catch (RuntimeException e) {
			// A scheduled task that throws is cancelled by default. Losing the
			// sweeper would silently disable crash recovery, so it is caught.
			log.error("stale delivery recovery failed; the sweeper stays scheduled", e);
		}
	}

}
