package dev.patocommit.github.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * One webhook delivery, as stored in {@code webhook_delivery}.
 *
 * <p>{@code payload} is the JSON body as received. Note that the {@code jsonb}
 * column normalises it, so it is <em>not</em> byte-identical to what GitHub
 * signed. That is fine: the signature is always verified against the raw request
 * bytes at intake, and never recomputed from this value.
 *
 * @param error exception class name only, never a message: a Jackson parse error
 * can quote the offending input, and this value would end up in a log.
 */
record WebhookDelivery(UUID deliveryId,
		String eventType,
		String action,
		Long repositoryId,
		String payload,
		WebhookDeliveryStatus status,
		int attempts,
		String error,
		Instant receivedAt,
		Instant processingStartedAt,
		Instant processedAt) {

}
