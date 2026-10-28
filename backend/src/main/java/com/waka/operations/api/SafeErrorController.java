package com.waka.operations.api;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Meme enveloppe lors d'un dispatch d'erreur du conteneur, sans page HTML de diagnostic. */
@RestController
public final class SafeErrorController implements ErrorController {

    @RequestMapping("${spring.web.error.path:${error.path:/error}}")
    public ResponseEntity<SafeApiProblem> error(HttpServletRequest request) {
        Object suppliedStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = suppliedStatus instanceof Integer value && value >= 400 && value <= 599 ? value : 404;
        return SafeApiProblem.response(status, request);
    }
}
