package com.sujula.exceptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an error is allowed to say. Every case here is one where the natural
 * exception message would describe the schema, the code, or somebody else's
 * data to whoever provoked it.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static String message(ResponseEntity<Map<String, Object>> response) {
        assertNotNull(response.getBody());
        return String.valueOf(response.getBody().get("message"));
    }

    @Test
    void aSignedInCallerRefusedByAnAuthorisationCheckGets403() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "someone", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Access is denied"));

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void anAnonymousCallerGets401RatherThan403() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Access is denied"));

        // They may simply not have signed in yet; 403 would tell them to stop trying.
        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void withNoSecurityContextAtAllTheAnswerIs401() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleAccessDenied(new AccessDeniedException("Access is denied"));

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void aDatabaseConstraintBecomes409AndSaysNothingAboutTheSchema() {
        ResponseEntity<Map<String, Object>> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException(
                        "Duplicate entry 'kettle' for key 'products.idx_product_slug'"));

        assertEquals(409, response.getStatusCode().value());
        String text = message(response);
        assertFalse(text.contains("idx_product_slug"), "an index name describes the schema");
        assertFalse(text.contains("products"), "a table name describes the schema");
    }

    @Test
    void aLostRaceOnAVersionedRowBecomes409() {
        ResponseEntity<Map<String, Object>> response = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException(
                        "Row was updated or deleted by another transaction", null));

        assertEquals(409, response.getStatusCode().value());
        assertTrue(message(response).toLowerCase().contains("try again"));
    }

    @Test
    void anUnexpectedFailureReturnsAReferenceAndNothingElse() {
        ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(
                new IllegalStateException("connection to db-primary.internal:3306 refused"));

        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());

        String reference = String.valueOf(response.getBody().get("reference"));
        assertEquals(8, reference.length(), "support quotes this back, so keep it short");

        String text = message(response);
        assertTrue(text.contains(reference), "the caller has to be told the reference");
        // The hostname, the port and the exception type all stay in the log.
        assertFalse(text.contains("db-primary.internal"));
        assertFalse(text.contains("IllegalStateException"));
    }

    @Test
    void everyErrorBodyHasTheSameShape() {
        Map<String, Object> body = handler.handleBadRequest(
                new BadRequestException("Stock is not sufficient")).getBody();

        assertNotNull(body);
        assertTrue(body.containsKey("timestamp"));
        assertTrue(body.containsKey("status"));
        assertTrue(body.containsKey("error"));
        assertEquals("Stock is not sufficient", body.get("message"));
    }
}
