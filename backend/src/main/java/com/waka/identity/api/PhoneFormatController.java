package com.waka.identity.api;

import java.io.IOException;

import com.waka.identity.application.CameroonMobilePhoneFormat;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Precontrole sans compte, challenge, SMS, stockage ou echo du contact. */
@RestController
public final class PhoneFormatController {
    private static final int MAX_BODY_BYTES = 512;
    private static final JsonFactory JSON = new JsonFactory();

    @PostMapping(path = "/api/v1/identity/phone-format", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PhoneFormatResult> check(HttpServletRequest request) throws IOException {
        // Mesure les octets effectivement recus, meme sans Content-Length (transfert chunked).
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "PHONE_FORMAT_BODY_TOO_LARGE");
        }
        try (JsonParser parser = JSON.createParser(body)) {
            // Une seule propriete string : rejette aussi doublons, champs inconnus et plusieurs racines.
            if (parser.nextToken() != JsonToken.START_OBJECT
                    || parser.nextToken() != JsonToken.PROPERTY_NAME
                    || !"phoneNumber".equals(parser.getString())
                    || parser.nextToken() != JsonToken.VALUE_STRING) {
                throw invalid();
            }
            CameroonMobilePhoneFormat.normalize(parser.getString());
            if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                throw invalid();
            }
        } catch (JacksonException | IllegalArgumentException exception) {
            // Jamais de valeur recue ou d'exception du parseur dans la reponse ou les journaux.
            throw invalid();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PhoneFormatResult(true, false, "CM"));
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE_FORMAT_REQUEST");
    }

    public record PhoneFormatResult(boolean formatValid, boolean ownershipVerified, String country) { }
}
