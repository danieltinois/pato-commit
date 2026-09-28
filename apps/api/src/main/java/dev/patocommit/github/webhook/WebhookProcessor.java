package dev.patocommit.github.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Claims a delivery and runs its handler.
 *
 * <p>Both callers go through here: the trigger that fires right after intake, and
 * the sweeper that recovers deliveries abandoned by a crash. There is one code
 * path, so recovery is not a special case that can drift from normal processing.
 */
@Component
class WebhookProcessor {

	private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);

	private final WebhookDeliveryRepository repository;

	private final WebhookHandlerRegistry registry;

	private final WebhookProperties properties;

	WebhookProcessor(WebhookDeliveryRepository repository, WebhookHandlerRegistry registry, WebhookProperties properties) {
		this.repository = repository;
		this.registry = registry;
		this.properties = properties;
	}

	/**
	 * Claims and dispatches. Does nothing when the delivery is not {@code PENDING}
	 * — already claimed, already done, or unknown.
	 */
	void process(UUID deliveryId) {
		repository.claim(deliveryId).ifPresent(this::dispatch);
	}

	/**
	 * Claims and dispatches everything that has been waiting or stuck for longer
	 * than the configured timeout. Returns how many were recovered.
	 */
	int recoverStale() {
		List<WebhookDelivery> recovered = repository.claimStale(Instant.now().minus(properties.staleAfter()),
				properties.recoveryBatchSize());
		recovered.forEach(this::dispatch);
		return recovered.size();
	}

	private void dispatch(WebhookDelivery delivery) {
		Optional<WebhookEventHandler> handler = registry.forEvent(delivery.eventType());
		if (handler.isEmpty()) {
			// An event type Pato Commit does not handle yet. Recorded and terminal,
			// so it is visible without ever being an error.
			log.info("no handler for event '{}': delivery {} recorded as ignored", delivery.eventType(), delivery.deliveryId());
			repository.markIgnored(delivery.deliveryId(), "no handler registered");
			return;
		}
		try {
			handler.get().handle(delivery);
			repository.markProcessed(delivery.deliveryId());
		}
		catch (RuntimeException failed) {
			recordFailure(delivery, failed);
		}
	}

	private void recordFailure(WebhookDelivery delivery, RuntimeException failed) {
		// The class name only. An exception message can quote the payload, and this
		// value is stored in the database where it would outlive the log.
		String error = failed.getClass().getSimpleName();
		log.warn("handler for event '{}' failed on delivery {} (attempt {} of {})", delivery.eventType(),
				delivery.deliveryId(), delivery.attempts(), properties.maxAttempts(), failed);
		if (delivery.attempts() >= properties.maxAttempts()) {
			repository.markFailed(delivery.deliveryId(), error);
		}
		else {
			repository.releaseForRetry(delivery.deliveryId(), error);
		}
	}

}
