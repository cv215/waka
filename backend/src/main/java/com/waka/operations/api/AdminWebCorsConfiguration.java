package com.waka.operations.api;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the separate administration web app call the API from its own origin. Closed by default:
 * only origins listed explicitly in {@code WAKA_ADMIN_WEB_ORIGINS} are allowed, plain http only for a
 * local development host, never a wildcard, never cookies (proofs travel in headers).
 */
@Configuration
public class AdminWebCorsConfiguration implements WebMvcConfigurer {
    static final List<String> HEADERS = List.of("Authorization", "Content-Type", "Idempotency-Key",
            "X-Waka-Client-Instance", "X-Waka-Admin-Session", "X-Waka-Valid-Until");
    private final List<String> origins;

    public AdminWebCorsConfiguration(@Value("${waka.admin-web.allowed-origins:}") String configured) {
        this.origins = parse(configured);
    }

    static List<String> parse(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        List<String> parsed = Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        for (String origin : parsed) {
            boolean local = origin.matches("http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?");
            boolean secure = origin.matches("https://[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?");
            if (!local && !secure) throw new IllegalArgumentException("ADMIN_WEB_ORIGIN_REFUSED");
        }
        return List.copyOf(parsed);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.isEmpty()) return;
        registry.addMapping("/api/v1/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH")
                .allowedHeaders(HEADERS.toArray(String[]::new))
                .exposedHeaders("X-Request-Id")
                .allowCredentials(false)
                .maxAge(600);
    }
}
