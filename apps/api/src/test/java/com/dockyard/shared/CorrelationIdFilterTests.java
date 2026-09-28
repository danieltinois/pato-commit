package com.dockyard.shared;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.dockyard.shared.CorrelationIdFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTests {

	private final CorrelationIdFilter filter = new CorrelationIdFilter();

	private String filterOnce(String inboundHeader) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (inboundHeader != null) {
			request.addHeader(CorrelationIdFilter.HEADER, inboundHeader);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (req, res) -> {
		});
		return response.getHeader(CorrelationIdFilter.HEADER);
	}

	@Test
	void echoesAWellFormedInboundCorrelationId() throws Exception {
		assertThat(filterOnce("abc-123_X.y")).isEqualTo("abc-123_X.y");
	}

	@Test
	void generatesACorrelationIdWhenTheHeaderIsAbsent() throws Exception {
		assertThat(filterOnce(null)).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	}

	@Test
	void replacesAnInboundIdThatCouldForgeLogLines() {
		// A forged newline would let a caller inject fake entries into the log stream.
		assertThat(CorrelationIdFilter.resolve("abc\nINFO fake log line")).isNotEqualTo("abc\nINFO fake log line");
		assertThat(CorrelationIdFilter.resolve("abc def")).isNotEqualTo("abc def");
		assertThat(CorrelationIdFilter.resolve("x".repeat(65))).hasSize(36);
	}

	@Test
	void exposesTheCorrelationIdToTheLoggingContextAndAlwaysClearsIt() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(CorrelationIdFilter.HEADER, "trace-1");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("trace-1"));

		assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void clearsTheLoggingContextEvenWhenTheChainFails() {
		MDC.put(CorrelationIdFilter.MDC_KEY, "leaked-from-an-earlier-request");
		try {
			filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
					(req, res) -> {
						throw new IllegalStateException("boom");
					});
		}
		catch (Exception expected) {
			// The filter must not swallow the original failure.
		}
		assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
	}

}
