package com.sujula.exceptions;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every exception that escapes a controller into the same JSON shape.
 *
 * <p>Two rules run through all of it. Nothing internal reaches the client — no
 * SQL, no constraint names, no stack traces, no class names — because those
 * describe the schema to anyone who can provoke an error. And nothing is
 * allowed to fall through to the container's default error page, which answers
 * in HTML and says more than we do.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Bean Validation failures from @Valid on the request body (400). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    /** Bean Validation on path variables and request params, from @Validated (400). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation ->
                fieldErrors.putIfAbsent(String.valueOf(violation.getPropertyPath()), violation.getMessage()));

        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    /** Business rule violations thrown by the service (400). */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    /** Missing entities: vendor, brand, product... (404). */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    /**
     * Wrong email or wrong password (401).
     *
     * <p>The message is fixed rather than taken from the exception: the two
     * cases must be indistinguishable, or the endpoint becomes a way to test
     * which addresses hold accounts.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", null);
    }

    /** Credentials were right but the account is disabled, blocked or flagged (403). */
    @ExceptionHandler({DisabledException.class, LockedException.class})
    public ResponseEntity<Map<String, Object>> handleAccountShutOut(RuntimeException ex) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), null);
    }

    /**
     * A @PreAuthorize or @PostAuthorize refusal (401 or 403).
     *
     * <p>Without this the refusal escapes as an unhandled exception and the
     * client gets an HTML error page instead of the JSON every other failure
     * speaks. Anonymous callers get 401 — they may simply not have signed in
     * yet — and signed-in callers get 403, which is a decision, not a prompt.
     *
     * <p>The message is fixed. A refusal that named the resource would confirm
     * it exists, which is the one thing the check just declined to reveal.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);

        return signedIn
                ? build(HttpStatus.FORBIDDEN, "You do not have permission to do that", null)
                : build(HttpStatus.UNAUTHORIZED, "Authentication is required", null);
    }

    /**
     * A unique key or foreign key the database refused (409).
     *
     * <p>Usually a race two requests lost together — the same slug, the same
     * email, the same order number — where a service-level check passed and the
     * constraint caught it a moment later. The database's own message names
     * tables, columns and index names, so it is logged and not returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("[Error] Database rejected the write: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT,
                "That conflicts with something already saved. Reload and try again.", null);
    }

    /**
     * Two writers touched the same row at once (409).
     *
     * <p>Payments carry a version column precisely so this happens instead of one
     * update quietly overwriting the other. Retrying is the correct response.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("[Error] Concurrent update rejected: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT,
                "Someone else changed this at the same moment. Reload and try again.", null);
    }

    /** Malformed or missing JSON body (400). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // The parser's message quotes the offending JSON and the target class;
        // neither belongs in a response.
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON", null);
    }

    /** A path variable or query parameter of the wrong type — /products/abc (400). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(HttpStatus.BAD_REQUEST, "'" + ex.getName() + "' is not a valid value", null);
    }

    /** A required query parameter was not sent (400). */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return build(HttpStatus.BAD_REQUEST, "'" + ex.getParameterName() + "' is required", null);
    }

    /** Right path, wrong verb (405). */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMethod() + " is not supported here", null);
    }

    /** No handler for the path at all (404), in JSON rather than the container's HTML page. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoHandler(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "No such endpoint", null);
    }

    /** Anything already carrying its own status keeps it. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Map<String, Object>> handleErrorResponse(ErrorResponseException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        return build(status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getBody().getDetail() != null ? ex.getBody().getDetail() : "Request could not be completed",
                null);
    }

    /**
     * Everything else (500).
     *
     * <p>The last stop before the container's default error page. The client
     * gets a reference and nothing else; the reference is logged beside the
     * stack trace, so a buyer can quote eight characters and support can find
     * the exact failure without the response ever having described it.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        String reference = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.error("[Error] Unhandled exception (ref {})", reference, ex);

        Map<String, Object> body = baseBody(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on our side. Quote reference " + reference + " if you contact support.");
        body.put("reference", reference);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message,
                                                      Map<String, String> fieldErrors) {
        Map<String, Object> body = baseBody(status, message);
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            body.put("errors", fieldErrors);
        }
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
