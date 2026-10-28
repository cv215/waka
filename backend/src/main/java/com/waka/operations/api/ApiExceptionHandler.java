package com.waka.operations.api;

import com.waka.identity.api.ProfileHttpException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Les messages et proprietes des exceptions ne sont jamais serialises. */
@RestControllerAdvice
public final class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<SafeApiProblem> safe = safeResponse(statusCode.value(), headers,
                ((ServletWebRequest) request).getRequest());
        return new ResponseEntity<>(safe.getBody(), safe.getHeaders(), safe.getStatusCode());
    }

    /** More specific than the inherited ErrorResponseException handler, which would drop the selected code. */
    @ExceptionHandler(ProfileHttpException.class)
    public ResponseEntity<SafeApiProblem> handleProfile(ProfileHttpException exception, HttpServletRequest request) {
        return SafeApiProblem.response(exception.getStatusCode().value(), request, exception.code());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SafeApiProblem> handle(Exception exception, HttpServletRequest request) {
        int status = exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        return safeResponse(status, exception instanceof ErrorResponse error ? error.getHeaders() : HttpHeaders.EMPTY,
                request);
    }

    private ResponseEntity<SafeApiProblem> safeResponse(int status, HttpHeaders originalHeaders,
            HttpServletRequest request) {
        if (status < 400 || status > 599) {
            status = 500;
        }
        ResponseEntity<SafeApiProblem> safe = SafeApiProblem.response(status, request);
        if (status == 405) {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(safe.getHeaders());
            headers.setAllow(originalHeaders.getAllow());
            return new ResponseEntity<>(safe.getBody(), headers, safe.getStatusCode());
        }
        return safe;
    }
}
