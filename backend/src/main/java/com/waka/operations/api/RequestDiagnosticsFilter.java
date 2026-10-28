package com.waka.operations.api;

import java.io.IOException;
import java.util.Set;

import com.waka.operations.infrastructure.OperationalEvents;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ne collecte ni URL, ni chemin, ni en-tete, ni corps, ni contact. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestDiagnosticsFilter extends OncePerRequestFilter {
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE",
            "OPTIONS", "TRACE", "CONNECT");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String requestId = RequestReference.getOrCreate(request).toString();
        response.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException exception) {
            if (response.isCommitted()) {
                // Une reponse deja partie ne peut pas etre remplacee ni completee avec des details.
                OperationalEvents.responseIncomplete(requestId);
                throw exception;
            }
            SafeApiProblem.write(500, request, response);
        } finally {
            OperationalEvents.httpCompleted(requestId, METHODS.contains(request.getMethod())
                    ? request.getMethod() : "OTHER", response.getStatus(),
                    Math.max(0, (System.nanoTime() - started) / 1_000_000));
        }
    }
}
