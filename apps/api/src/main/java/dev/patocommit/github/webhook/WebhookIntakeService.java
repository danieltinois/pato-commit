package dev.patocommit.github.webhook;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Persists a delivery, then hands processing to a background thread.
 *
 * <p>The insert is deliberately not wrapped in a transaction: a single statement
 * commits on its own, so the row is durable before any worker can look for it.
 * That is the property the intake depends on — the row must exist by the time
 * the HTTP response goes out, or a crash would drop the event with no trace.
 */
@Service
class WebhookIntakeService {

	private static final Logger log = LoggerFactory.getLogger(WebhookIntakeService.class);

	private final WebhookDeliveryRepository repository;

	private final WebhookProcessor processor;

	private final TaskExecutor executor;

	WebhookIntakeService(WebhookDeliveryRepository repository, WebhookProcessor processor,
			@Qualifier("applicationTaskExecutor") TaskExecutor executor) {
		this.repository = repository;
		this.processor = processor;
		this.executor = executor;
	}

	/**
	 * @return {@code true} when this was a new delivery, {@code false} when the id
	 * was already known. A duplicate still triggers a dispatch attempt, which is
	 * harmless: the claim only succeeds from {@code PENDING}, so the work runs at
	 * most once. Skipping it would leave a row stranded if the first trigger died.
	 */
	boolean persist(WebhookDelivery delivery) {
		boolean inserted = repository.insert(delivery);
		UUID deliveryId = delivery.deliveryId();
		executor.execute(() -> runSafely(deliveryId));
		return inserted;
	}

	private void runSafely(UUID deliveryId) {
		try {
			processor.process(deliveryId);
		}
		catch (RuntimeException e) {
			// A task executor discards whatever a Runnable throws. Without this the
			// failure would leave the delivery in PROCESSING for the sweeper, with
			// no record of why.
			log.error("dispatch failed outside the handler for delivery {}", deliveryId, e);
		}
	}

}
