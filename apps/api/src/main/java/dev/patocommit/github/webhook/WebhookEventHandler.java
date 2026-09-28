package dev.patocommit.github.webhook;

/**
 * Handles one class of webhook event.
 *
 * <p>An implementation is registered for exactly one value of
 * {@code X-GitHub-Event}. Anything without a handler is recorded as
 * {@code IGNORED}: an event Pato Commit does not care about yet is a normal
 * occurrence, not a failure, and must not stall the delivery pipeline.
 */
interface WebhookEventHandler {

	/** The {@code X-GitHub-Event} value this handler claims. */
	String eventType();

	/**
	 * Processes the delivery. The row is already claimed and marked
	 * {@code PROCESSING} when this runs.
	 *
	 * <p>Must not call the GitHub REST API on the request thread. Throwing is
	 * safe: the delivery is released for another attempt until the attempt budget
	 * runs out.
	 */
	void handle(WebhookDelivery delivery);

}
