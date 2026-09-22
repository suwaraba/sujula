package com.sujula.service.auth.impl;

import com.nimbusds.jwt.SignedJWT;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.auth.OAuthAccount;
import com.sujula.model.constant.AuthProvider;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import com.sujula.repository.auth.OAuthAccountRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.auth.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a provider's authorisation code into a Sujula account.
 *
 * <p>The exchange happens server-side, which is the point of the whole
 * arrangement: the client sends only the short-lived code it was redirected
 * with, and the client secret never leaves this process.
 *
 * <p>Identity is taken from the ID token's {@code sub} claim and nothing else.
 * Matching on email is the tempting shortcut and it is dangerous twice over — a
 * person who changes their Google address loses their account, and worse, a
 * person who acquires an address someone else once used could be handed theirs.
 * The subject claim is immutable for the life of the provider account, which is
 * exactly the property an identifier needs.
 */
@Slf4j
@Component
public class OAuthExchange {

    private final AuthProperties properties;
    private final OAuthAccountRepository links;
    private final UserRepository users;
    private final RestClient restClient = RestClient.create();

    public OAuthExchange(AuthProperties properties, OAuthAccountRepository links, UserRepository users) {
        this.properties = properties;
        this.links = links;
        this.users = users;
    }

    /**
     * Resolves the account behind an authorisation code, creating one on first
     * sign-in and linking to an existing account where the verified email
     * already belongs to one.
     */
    public User resolveUser(String providerName, com.sujula.dto.request.auth.AuthRequests.OAuthCallback request) {
        AuthProvider provider = parseProvider(providerName);
        AuthProperties.OAuth.Provider config = configFor(provider);

        if (!config.isConfigured()) {
            throw new BadRequestException(
                    "Signing in with " + provider.name().toLowerCase(Locale.ROOT)
                            + " is not configured on this deployment. Set sujula.auth.oauth."
                            + provider.name().toLowerCase(Locale.ROOT) + ".client-id and .client-secret.");
        }

        ProviderIdentity identity = exchangeCode(provider, config, request);

        Optional<OAuthAccount> existing = links.findByProviderAndSubject(provider, identity.subject());
        if (existing.isPresent()) {
            OAuthAccount link = existing.get();
            link.setLastUsedAt(LocalDateTime.now());
            links.save(link);
            return link.getUser();
        }

        // First time through this provider. Either attach to the account that
        // already owns the address, or create one — but only when the provider
        // says it verified the address itself. An unverified email from a
        // provider is a claim, and attaching on a claim is account takeover.
        User user = (identity.email() != null && identity.emailVerified())
                ? users.findByEmailIgnoreCase(identity.email()).orElseGet(() -> createFrom(identity))
                : createFrom(identity);

        links.save(OAuthAccount.builder()
                .user(user)
                .provider(provider)
                .providerUserId(identity.subject())
                .email(identity.email())
                .lastUsedAt(LocalDateTime.now())
                .build());

        log.info("[Auth] Linked {} identity to user {}", provider, user.getId());
        return user;
    }

    /**
     * Posts the code to the provider's token endpoint and reads the ID token.
     *
     * <p>The ID token's signature is not verified here, and that is safe for this
     * specific flow only: it came back over TLS directly from the provider's own
     * token endpoint in response to a request carrying our client secret, so
     * there is no untrusted party in the path. A token arriving by any other
     * route — through the client, say — would have to be verified against the
     * provider's JWKS before a single claim in it could be believed.
     */
    private ProviderIdentity exchangeCode(AuthProvider provider,
                                          AuthProperties.OAuth.Provider config,
                                          com.sujula.dto.request.auth.AuthRequests.OAuthCallback request) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", request.code());
        form.add("client_id", config.getClientId());
        form.add("client_secret", config.getClientSecret());
        form.add("redirect_uri", blankTo(request.redirectUri(), config.getRedirectUri()));
        if (request.codeVerifier() != null && !request.codeVerifier().isBlank()) {
            form.add("code_verifier", request.codeVerifier());
        }

        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(config.getTokenUri())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(form)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {});
        } catch (Exception e) {
            log.warn("[Auth] {} token exchange failed: {}", provider, e.getMessage());
            throw new BadRequestException(
                    "Could not complete sign-in with " + provider.name().toLowerCase(Locale.ROOT)
                            + ". The authorisation code may have expired — start again.");
        }

        if (body == null || body.get("id_token") == null) {
            throw new BadRequestException(
                    "The identity provider did not return an ID token. Sign-in cannot complete.");
        }

        try {
            var claims = SignedJWT.parse(String.valueOf(body.get("id_token"))).getJWTClaimsSet();
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new BadRequestException("The identity provider did not identify the user");
            }
            Object verified = claims.getClaim("email_verified");
            return new ProviderIdentity(
                    subject,
                    claims.getStringClaim("email"),
                    verified instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(verified)),
                    claims.getStringClaim("given_name"),
                    claims.getStringClaim("family_name"));
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("The identity provider's response could not be read");
        }
    }

    /**
     * Creates an account for someone who has only ever signed in with a provider.
     *
     * <p>The password is a random value nobody holds, not an empty string: the
     * column is not nullable, and leaving something guessable there would make
     * password sign-in a way around the provider. To use a password they go
     * through the reset flow, which proves the address.
     */
    private User createFrom(ProviderIdentity identity) {
        String email = identity.email() != null
                ? identity.email().toLowerCase(Locale.ROOT)
                : "oauth-" + UUID.randomUUID() + "@placeholder.invalid";

        return users.save(User.builder()
                .email(email)
                .password("{noop-unusable}" + UUID.randomUUID())
                .firstName(blankTo(identity.givenName(), "Sujula"))
                .lastName(blankTo(identity.familyName(), "User"))
                .role(UserRole.CUSTOMER)
                .enabled(true)
                .emailVerified(identity.emailVerified())
                .phoneVerified(false)
                .preferredCurrency("GMD")
                .preferredLanguage("en")
                .build());
    }

    private AuthProvider parseProvider(String name) {
        try {
            return AuthProvider.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BadRequestException("Unknown sign-in provider: " + name);
        }
    }

    private AuthProperties.OAuth.Provider configFor(AuthProvider provider) {
        return provider == AuthProvider.GOOGLE
                ? properties.getOauth().getGoogle()
                : properties.getOauth().getApple();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** What the provider asserts about the person signing in. */
    private record ProviderIdentity(String subject, String email, boolean emailVerified,
                                    String givenName, String familyName) {}
}
