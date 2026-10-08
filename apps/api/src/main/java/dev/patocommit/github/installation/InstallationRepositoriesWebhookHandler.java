package dev.patocommit.github.installation;

import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import dev.patocommit.github.webhook.WebhookDelivery;
import dev.patocommit.github.webhook.WebhookEventHandler;

/**
 * Handles {@code installation_repositories}: repositories were granted to or
 * taken from an installation with {@code repository_selection = selected}.
 */
@Component
class InstallationRepositoriesWebhookHandler implements WebhookEventHandler {

	static final String EVENT = "installation_repositories";

	private final ObjectMapper objectMapper;

	private final InstallationService service;

	InstallationRepositoriesWebhookHandler(ObjectMapper objectMapper, InstallationService service) {
		this.objectMapper = objectMapper;
		this.service = service;
	}

	@Override
	public String eventType() {
		return EVENT;
	}

	@Override
	public void handle(WebhookDelivery delivery) {
		service.applyRepositoryChanges(objectMapper.readValue(delivery.payload(), InstallationPayload.class));
	}

}
