package dev.patocommit.github.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The GitHub webhook endpoint.
 *
 * <p>Order matters and is the security boundary of this endpoint: read the raw
 * bytes, verify the signature, and only then look at anything else. Nothing here
 * calls the GitHub REST API — the request is answered from the database alone, so
 * the response never waits on a network call to GitHub.
 */
@RestController
public class GitHubWebhookController {

	static final String DELIVERY_HEADER = "X-GitHub-Delivery";

	static final String EVENT_HEADER = "X-GitHub-Event";

	static final String SIGNATURE_HEADER = "X-Hub-Signature-256";

	static final String UNKNOWN_EVENT = "unknown";

	/** GitHub documents 25 MB as the maximum webhook payload. */
	private static final int MAX_BODY_BYTES = 25 * 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

	private final GitHubSignatureVerifier signatureVerifier;

	private final WebhookIntakeService intake;

	private final ObjectMapper objectMapper;

	public GitHubWebhookController(GitHubSignatureVerifier signatureVerifier, WebhookIntakeService intake,
			ObjectMapper objectMapper) {
		this.signatureVerifier = signatureVerifier;
		this.intake = intake;
		this.objectMapper = objectMapper;
	}

	@PostMapping("/api/webhooks/github")
	public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
		byte[] rawBody = readBody(request);

		// First, and over the untouched bytes. An unauthenticated caller learns
		// nothing about delivery-id or payload handling from this endpoint.
		if (!signatureVerifier.isValid(rawBody, request.getHeader(SIGNATURE_HEADER))) {
			log.warn("rejected webhook delivery: missing or invalid {}", SIGNATURE_HEADER);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid webhook signature");
		}

		WebhookDelivery delivery = toDelivery(rawBody, request.getHeader(DELIVERY_HEADER),
				request.getHeader(EVENT_HEADER));
		boolean fresh = intake.persist(delivery);

		// 202 for both. The delivery is durable and queued; a redelivery of a known
		// id is equally satisfied, and telling the two apart here would leak whether
		// an id has been seen.
		log.debug("webhook delivery {} for event '{}' accepted (new={})", delivery.deliveryId(), delivery.eventType(), fresh);
		return ResponseEntity.accepted().build();
	}

	private static byte[] readBody(HttpServletRequest request) throws IOException {
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "webhook payload too large");
		}
		return body;
	}

	private WebhookDelivery toDelivery(byte[] rawBody, String deliveryIdHeader, String eventHeader) {
		UUID deliveryId = parseDeliveryId(deliveryIdHeader);

		String event = (eventHeader == null || eventHeader.isBlank()) ? UNKNOWN_EVENT : eventHeader;
		if (UNKNOWN_EVENT.equals(event)) {
			// Absent event header means this is not a GitHub delivery. It is still
			// stored and then ignored, so it shows up instead of vanishing.
			log.warn("delivery {} arrived without {}; it will be recorded as ignored", deliveryId, EVENT_HEADER);
		}

		JsonNode payload = parsePayload(rawBody);
		String action = textOrNull(payload.get("action"));
		Long repositoryId = numberOrNull(payload.path("repository").get("id"));

		return new WebhookDelivery(deliveryId, event, action, repositoryId,
				new String(rawBody, StandardCharsets.UTF_8),
				WebhookDeliveryStatus.PENDING, 0, null, Instant.now(), null, null);
	}

	private static UUID parseDeliveryId(String header) {
		if (header == null || header.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing " + DELIVERY_HEADER);
		}
		try {
			return UUID.fromString(header);
		}
		catch (IllegalArgumentException malformed) {
			// Thrown without echoing the value: the header is caller-controlled.
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, DELIVERY_HEADER + " is not a valid GUID");
		}
	}

	private JsonNode parsePayload(byte[] rawBody) {
		JsonNode payload;
		try {
			payload = objectMapper.readTree(rawBody);
		}
		catch (JacksonException notJson) {
			// The signature already passed, so this is GitHub sending something that
			// is not JSON. Nothing is stored: there is no payload to process.
			log.warn("delivery with a valid signature had a non-JSON payload; rejected");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload is not JSON");
		}
		if (payload == null || !payload.isObject()) {
			log.warn("delivery with a valid signature had a non-object payload; rejected");
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload is not a JSON object");
		}
		return payload;
	}

	private static String textOrNull(JsonNode node) {
		return (node == null || !node.isTextual()) ? null : node.textValue();
	}

	private static Long numberOrNull(JsonNode node) {
		return (node == null || !node.isNumber()) ? null : node.longValue();
	}

}
