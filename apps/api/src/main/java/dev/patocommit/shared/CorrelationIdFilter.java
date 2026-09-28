package dev.patocommit.shared;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Propagates a correlation id across log lines, so one request can be followed
 * through the whole system.
 *
 * <p>An inbound id is only honoured when it matches {@code SAFE_ID}. Anything else
 * is replaced by a fresh UUID: the value ends up in log files, and a caller must
 * not be able to forge newlines or control characters into them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";

	public static final String MDC_KEY = "correlationId";

	/** Opaque, log-safe token: no whitespace, no control characters, bounded length. */
	private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String correlationId = resolve(request.getHeader(HEADER));
		MDC.put(MDC_KEY, correlationId);
		try {
			response.setHeader(HEADER, correlationId);
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	static String resolve(String inbound) {
		return (inbound != null && SAFE_ID.matcher(inbound).matches()) ? inbound : UUID.randomUUID().toString();
	}

}
