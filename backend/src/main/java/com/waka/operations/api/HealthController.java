package com.waka.operations.api;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controle HTTP du processus ; aucune verification de donnees ou de services metier. */
@RestController
public class HealthController {

    @GetMapping(value = "/api/v1/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
