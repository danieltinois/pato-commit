package dev.patocommit.shared;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document served at {@code /v3/api-docs} and rendered by Swagger UI
 * at {@code /swagger-ui.html}. Both are on the loopback port only: the webhook
 * port refuses every path but the webhook.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	@Bean
	OpenAPI patoCommitOpenApi() {
		return new OpenAPI().info(new Info().title("Pato Commit API")
			.version("M2")
			.description("""
					Self-hosted developer inbox for GitHub. No authentication yet (ADR 0002): \
					this API is reachable from the local machine only.""")
			.license(new License().name("MIT")));
	}

}
