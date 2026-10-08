package dev.patocommit.github.installation;

import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import dev.patocommit.github.webhook.WebhookDelivery;
import dev.patocommit.github.webhook.WebhookEventHandler;

/** Handles {@code installation}: the App was installed, removed, suspended or re-permissioned. */
@Component
class InstallationWebhookHandler implements WebhookEventHandler {

	static final String EVENT = "installation";

	private final ObjectMapper objectMapper;

	private final InstallationService service;

	InstallationWebhookHandler(ObjectMapper objectMapper, InstallationService service) {
		this.objectMapper = objectMapper;
		this.service = service;
	}

	@Override
	public String eventType() {
		return EVENT;
	}

	@Override
	public void handle(WebhookDelivery delivery) {
		service.applyInstallationEvent(objectMapper.readValue(delivery.payload(), InstallationPayload.class));
	}

}
