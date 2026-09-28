package dev.patocommit.github.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Handles {@code ping}, the event GitHub sends when the webhook URL is saved or
 * redelivered manually.
 *
 * <p>It has no side effect on purpose. Its value in M1 is proving that intake,
 * persistence, claiming and dispatch all work end to end before any event that
 * writes business state exists.
 */
@Component
class PingWebhookHandler implements WebhookEventHandler {

	static final String EVENT = "ping";

	private static final Logger log = LoggerFactory.getLogger(PingWebhookHandler.class);

	@Override
	public String eventType() {
		return EVENT;
	}

	@Override
	public void handle(WebhookDelivery delivery) {
		// Delivery id only. The payload carries hook_id and the zen string; neither
		// is worth logging, and the payload itself is never logged.
		log.info("ping handled: delivery={}", delivery.deliveryId());
	}

}
