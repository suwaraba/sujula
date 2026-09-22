package com.sujula.config;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * What a refusal looks like when the filter chain makes it, rather than a controller.
 *
 * <p>Two things were wrong with leaving this to the defaults, and they are
 * separate problems that happen to share a cause.
 *
 * <p><strong>The status.</strong> 401 and 403 are different instructions. 401
 * means "say who you are and try again"; 403 means "signing in will not help".
 * A client cannot tell them apart from one code, and the default sent 403 to
 * callers who had presented no credential at all — so a browser opening
 * {@code /auth/register} (a GET, where only POST is published) was told it
 * lacked permission, when what it lacked was a request method and an account.
 *
 * <p><strong>The body.</strong> {@code GlobalExceptionHandler} answers every
 * controller-level failure with a JSON object carrying a timestamp, a status,
 * an error and a message. A refusal made in the filter chain never reaches a
 * {@code @ControllerAdvice}, so it returned an empty body — a client parsing
 * the documented error shape got nothing to parse. The same shape is written
 * here, deliberately duplicated rather than shared, because the two run in
 * different worlds: one has a {@code ResponseEntity} and a message converter,
 * this one has a raw {@code HttpServletResponse} and no MVC machinery at all.
 *
 * <p><strong>Why one class serves as both handlers.</strong>
 * {@code ExceptionTranslationFilter} decides which of the two to call by asking
 * its trust resolver whether the caller is anonymous, and with a bearer-token
 * filter that populates the context late, that question is not reliably
 * answered by the time the exception unwinds — a signed-in caller denied at the
 * chain was being sent to the entry point and told to sign in again. So the
 * decision is not delegated: the same object is registered as both, reads the
 * context itself, and picks the status. Whichever route the filter takes, the
 * answer is the same.
 *
 * <p>Refusals raised by a controller or a service keep their own status and are
 * not touched here. A blocked account signing in is still 403, because those
 * credentials were read and found wanting, which is not the same as presenting
 * none.
 */
public final class FilterChainRefusals implements AuthenticationEntryPoint, AccessDeniedHandler {

    /**
     * Fixed, and the same for both statuses.
     *
     * <p>A message naming the endpoint or the missing role would confirm that
     * the endpoint exists and describe what would open it, which is what the
     * check just declined to reveal. {@code GlobalExceptionHandler} withholds
     * the same thing for the same reason.
     */
    private static final String NOT_SIGNED_IN = "Authentication is required";
    private static final String NOT_PERMITTED = "You do not have permission to do that";

    private final ObjectMapper json;

    public FilterChainRefusals(ObjectMapper json) {
        this.json = json;
    }

    /** No credential, or one that did not verify. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException failed)
            throws IOException {
        answer(response);
    }

    /** A credential that verified but does not reach far enough. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException denied)
            throws IOException {
        answer(response);
    }

    private void answer(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;   // something downstream already wrote; do not append to it
        }

        HttpStatus status = signedIn() ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", status == HttpStatus.FORBIDDEN ? NOT_PERMITTED : NOT_SIGNED_IN);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }

    /**
     * Whether this request established an identity.
     *
     * <p>Read from the context rather than from the {@code Authorization}
     * header, and the difference matters: a header carrying an expired or
     * forged token is a caller who has NOT identified themselves, and answering
     * 403 to them would be telling them their credential was accepted.
     */
    private static boolean signedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
