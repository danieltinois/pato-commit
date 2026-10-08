package dev.patocommit.shared;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One clock for the whole application. Code that depends on "now" takes a
 * {@link Clock} instead of calling {@code Instant.now()}, so a test can pin time
 * rather than race it.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
