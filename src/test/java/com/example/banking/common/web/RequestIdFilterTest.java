package com.example.banking.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void usesTheCallersRequestIdForLogsAndTheResponse() throws Exception {
        request.addHeader(RequestIdFilter.HEADER, "checkout-42");
        AtomicReference<String> idSeenByLogging = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> idSeenByLogging.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(idSeenByLogging).hasValue("checkout-42");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("checkout-42");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("MDC is cleared after the request").isNull();
    }

    @Test
    void fallsBackToTheLoadBalancerTraceId() throws Exception {
        String traceId = "Root=1-67891233-abcdef012345678912345678";
        request.addHeader(RequestIdFilter.AMAZON_TRACE_HEADER, traceId);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(traceId);
    }

    @Test
    void generatesAnIdWhenNoneIsSupplied() throws Exception {
        filter.doFilter(request, response, new MockFilterChain());

        assertThatGeneratedId(response.getHeader(RequestIdFilter.HEADER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"has spaces", "line\nbreak", "<script>"})
    void replacesUnsafeIdsWithAGeneratedOne(String unsafeId) throws Exception {
        request.addHeader(RequestIdFilter.HEADER, unsafeId);

        filter.doFilter(request, response, new MockFilterChain());

        assertThatGeneratedId(response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void replacesOverlyLongIdsWithAGeneratedOne() throws Exception {
        request.addHeader(RequestIdFilter.HEADER, "a".repeat(129));

        filter.doFilter(request, response, new MockFilterChain());

        assertThatGeneratedId(response.getHeader(RequestIdFilter.HEADER));
    }

    private static void assertThatGeneratedId(String requestId) {
        assertThat(requestId).isNotBlank();
        assertThatNoException().isThrownBy(() -> UUID.fromString(requestId));
    }
}
