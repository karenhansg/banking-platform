package com.example.banking.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id that appears on each log line (via the MDC), in the {@value #HEADER} response header and
 * in error responses, so a problem reported by a customer can be traced through the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    /** Added by the AWS Application Load Balancer. */
    static final String AMAZON_TRACE_HEADER = "X-Amzn-Trace-Id";

    // Caller-supplied ids are echoed into logs and headers, so only short, inert values are accepted.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9=;:._-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        return Stream.of(request.getHeader(HEADER), request.getHeader(AMAZON_TRACE_HEADER))
                .filter(id -> id != null && SAFE_ID.matcher(id).matches())
                .findFirst()
                .orElseGet(() -> UUID.randomUUID().toString());
    }
}
