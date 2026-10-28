package com.waka.operations.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/** Reference generee par le serveur, jamais lue dans les en-tetes du demandeur. */
public final class RequestReference {
    private static final String ATTRIBUTE = RequestReference.class.getName() + ".id";

    private RequestReference() { }

    public static UUID getOrCreate(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof UUID id) {
            return id;
        }
        UUID id = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }
}
