package dev.patocommit.github.webhook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Resolves an {@code X-GitHub-Event} value to its handler, if Pato Commit has one. */
@Component
class WebhookHandlerRegistry {

	private final Map<String, WebhookEventHandler> byEventType;

	WebhookHandlerRegistry(List<WebhookEventHandler> handlers) {
		Map<String, WebhookEventHandler> index = new HashMap<>();
		for (WebhookEventHandler handler : handlers) {
			// Two handlers for one event is a wiring mistake that would dispatch
			// nondeterministically. Fail at startup rather than at delivery time.
			WebhookEventHandler previous = index.put(handler.eventType(), handler);
			if (previous != null) {
				throw new IllegalStateException("Two handlers registered for event '%s': %s and %s"
						.formatted(handler.eventType(), previous.getClass().getName(), handler.getClass().getName()));
			}
		}
		this.byEventType = Map.copyOf(index);
	}

	Optional<WebhookEventHandler> forEvent(String eventType) {
		return Optional.ofNullable(byEventType.get(eventType));
	}

}
