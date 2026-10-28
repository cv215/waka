package com.waka.operations.api;

import java.io.IOException;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** RFC 9457 : aucune instance, entree usager ou exception dans le corps public. */
public record SafeApiProblem(String type, String title, int status, String detail,
        String code, String requestId, String serverTime) {

    static SafeApiProblem create(int status, HttpServletRequest request) {
        return create(status, request, null);
    }

    static SafeApiProblem create(int status, HttpServletRequest request, String selectedCode) {
        String code = switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "ACTION_NOT_ALLOWED";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "RESPONSE_TYPE_UNSUPPORTED";
            case 409 -> "IDEMPOTENCY_KEY_REUSED";
            case 410 -> "REPLAY_EXPIRED";
            case 413 -> "FILE_TOO_LARGE";
            case 415 -> "CONTENT_TYPE_UNSUPPORTED";
            case 429 -> "RATE_LIMITED";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "INVALID_REQUEST";
        };
        if (selectedCode != null) code = selectedCode;
        String detail = switch (status) {
            case 404 -> "Ressource introuvable.";
            case 405 -> "Methode non autorisee pour cette ressource.";
            default -> status >= 500
                    ? "Service momentanement indisponible. Conservez la reference de suivi."
                    : "La demande ne peut pas etre traitee dans ce format ou ce contexte.";
        };
        return new SafeApiProblem("about:blank", status >= 500 ? "Erreur serveur" : "Demande refusee",
                status, detail, code, RequestReference.getOrCreate(request).toString(), Instant.now().toString());
    }

    static ResponseEntity<SafeApiProblem> response(int status, HttpServletRequest request) {
        return response(status, request, null);
    }

    static ResponseEntity<SafeApiProblem> response(int status, HttpServletRequest request, String selectedCode) {
        SafeApiProblem problem = create(status, request, selectedCode);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).header("X-Request-Id", problem.requestId()).body(problem);
    }

    /** Secours du filtre avant les convertisseurs MVC ; toutes les valeurs sont internes. */
    static void write(int status, HttpServletRequest request, HttpServletResponse response) throws IOException {
        SafeApiProblem problem = create(status, request);
        response.reset();
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-Id", problem.requestId());
        if (!"HEAD".equals(request.getMethod())) {
            // Les champs sont des constantes, un entier, un UUID et une date UTC serveur.
            response.getWriter().write("{\"type\":\"" + problem.type() + "\",\"title\":\""
                    + problem.title() + "\",\"status\":" + status + ",\"detail\":\"" + problem.detail()
                    + "\",\"code\":\"" + problem.code() + "\",\"requestId\":\"" + problem.requestId()
                    + "\",\"serverTime\":\"" + problem.serverTime() + "\"}");
        }
    }
}
