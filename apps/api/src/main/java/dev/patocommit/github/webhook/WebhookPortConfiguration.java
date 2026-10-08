package dev.patocommit.github.webhook;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A second loopback port that serves the webhook endpoint and nothing else
 * (ADR 0003).
 *
 * <p>A tunnel makes whatever it points at reachable from the internet. Pointed
 * at the main port, it would publish Swagger, actuator and every read endpoint
 * of an API that has no authentication. Pointed at this port, the only thing
 * that answers is the endpoint built to face GitHub, which rejects anything not
 * signed with the webhook secret.
 *
 * <p>The filter decides by the port the connection arrived on. That value comes
 * from the server socket, not from the request, so a caller cannot forge it.
 */
@Configuration(proxyBeanMethods = false)
class WebhookPortConfiguration {

	static final String WEBHOOK_PATH = "/api/webhooks/github";

	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> webhookConnector(WebhookProperties properties) {
		return factory -> {
			if (properties.port() <= 0) {
				return;
			}
			Connector connector = new Connector();
			connector.setPort(properties.port());
			// Loopback, like the main port: the tunnel client runs on this machine.
			connector.setProperty("address", "127.0.0.1");
			factory.addAdditionalConnectors(connector);
		};
	}

	@Bean
	FilterRegistrationBean<WebhookPortFilter> webhookPortFilter(WebhookProperties properties) {
		FilterRegistrationBean<WebhookPortFilter> registration = new FilterRegistrationBean<>(
				new WebhookPortFilter(properties.port()));
		// Before anything else can act on the request, the correlation id included.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	static final class WebhookPortFilter extends OncePerRequestFilter {

		private final int webhookPort;

		WebhookPortFilter(int webhookPort) {
			this.webhookPort = webhookPort;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (webhookPort > 0 && request.getLocalPort() == webhookPort && !isWebhook(request)) {
				// A bare 404 with no body: nothing about what else this server hosts.
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				return;
			}
			chain.doFilter(request, response);
		}

		/**
		 * Exact match on the raw request URI. Raw, so an encoded or dot-segment
		 * variant of the path does not equal it and is refused: anything that is not
		 * literally the webhook path fails closed.
		 */
		private static boolean isWebhook(HttpServletRequest request) {
			return "POST".equals(request.getMethod()) && WEBHOOK_PATH.equals(request.getRequestURI())
					&& request.getQueryString() == null;
		}

	}

}
