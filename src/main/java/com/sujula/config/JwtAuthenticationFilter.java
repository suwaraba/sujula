package com.sujula.config;

import com.sujula.model.user.User;
import com.sujula.repository.auth.UserSessionRepository;
import com.sujula.service.auth.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Turns a {@code Bearer} access token into an authenticated request.
 *
 * <p>The principal it installs is the {@code User} entity, exactly as the
 * session login installs — so every controller already written keeps working
 * unchanged, and the two ways of signing in are indistinguishable downstream.
 *
 * <p>One database query per authenticated request, and only one. The filter has
 * to load the user anyway, so the session check rides along in the same
 * statement: the token says which session it belongs to, and the query returns
 * the user only if that session is still live and the account is neither
 * disabled nor blocked. Revoking a device therefore takes effect on its next
 * request rather than whenever its access token happens to lapse — a remote
 * sign-out that leaves the device working for ten more minutes is not one.
 *
 * <p>A request with no token, or a bad one, is left unauthenticated and passed
 * along. It is not this filter's job to reject anything: the authorisation rules
 * decide whether the endpoint needed authentication, and answering 401 here
 * would break every deliberately public route.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    /**
     * Where the verified session id is left for the request to read.
     *
     * <p>Published as an attribute because the signature has already been
     * checked here: anything downstream that needs to know which device the
     * request came from would otherwise verify the same token a second time,
     * and an HMAC per handler is a cost paid for nothing.
     */
    public static final String SESSION_ID_ATTRIBUTE = "sujula.sessionId";

    private final TokenService tokenService;
    private final UserSessionRepository sessions;

    public JwtAuthenticationFilter(TokenService tokenService, UserSessionRepository sessions) {
        this.tokenService = tokenService;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // An existing authentication wins: the session login runs before this and
        // re-authenticating a request that already has an identity is pointless work.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }

        bearerToken(request)
                .flatMap(tokenService::verifyAccessToken)
                .ifPresent(claims -> sessions.findAuthenticatedUser(claims.sessionId(), claims.userId())
                        .ifPresent(user -> {
                            request.setAttribute(SESSION_ID_ATTRIBUTE, claims.sessionId());
                            authenticate(user, request);
                        }));

        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String value = header.substring(PREFIX.length()).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private void authenticate(User user, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * Skipped where a token could never apply.
     *
     * <p>Not an optimisation so much as a statement: these paths are reached
     * without credentials by definition, and running credential resolution over
     * them invites the mistake of making one of them depend on it.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }
}
