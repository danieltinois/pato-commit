package dev.patocommit.github.webhook;

/**
 * Lifecycle of a webhook delivery.
 *
 * <p>{@code PENDING -> PROCESSING} is the claim, and it only ever happens when
 * the row is still {@code PENDING}. That single conditional transition is what
 * makes processing exactly-once: a delivery that dies mid-processing is left
 * {@code PROCESSING} with a timestamp, and the sweeper takes it again after a
 * timeout rather than leaving it stuck.
 */
enum WebhookDeliveryStatus {

	/** Persisted, not yet claimed by any worker. */
	PENDING,

	/** Claimed by a worker. {@code processingStartedAt} is set. */
	PROCESSING,

	/** A handler ran to completion. Terminal. */
	PROCESSED,

	/** No handler is registered for the event type. Terminal, and not an error. */
	IGNORED,

	/** A handler kept failing until the attempt budget ran out. Terminal. */
	FAILED;

	/** A delivery in one of these states is never claimed again. */
	boolean isTerminal() {
		return this == PROCESSED || this == IGNORED || this == FAILED;
	}

}
