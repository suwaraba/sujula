package com.sujula.service.impl;



import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.exceptions.BadRequestException;
import com.sujula.model.constant.UserRole;

import com.sujula.dto.request.UserRequest;
import com.sujula.dto.response.user.UserResponse;
import com.sujula.model.user.User;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.EmailService;
import com.sujula.service.GeoService;
import com.sujula.service.UserService;
import com.sujula.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final GeoService geoService;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, EmailService emailService, GeoService geoService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.geoService = geoService;
    }

    // Characters used for random password generation — no ambiguous chars (0/O, 1/l/I)
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789@#$!";
    private static final int PWD_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public UserResponse getCurrentUser(Long userId) {
        Long effectiveUserId = userId != null ? userId : getAuthenticatedUserId();
        if (effectiveUserId == null) {
            throw new AccessDeniedException("Authentication is required");
        }
        requireSelfOrAdmin(effectiveUserId);
        return UserResponse.toResponse(findUserEntityById(effectiveUserId));
    }

    @Override
    public UserResponse findById(Long id) {
        return userRepository.findById(id).map(UserResponse::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Override
    public UserResponse findByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email).map(UserResponse::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
    }

    private User findUserEntityById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Override
    public Page<UserResponse> findAll(UserRole role, Pageable pageable) {
        requireAdmin();
        return userRepository.findByRole(role, pageable).map(UserResponse::toResponse);
    }

    @Override
    @Transactional
    public UserResponse save(UserRequest request) {

        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("An account already exists with this email. Please recover the existing account.");
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        HttpServletRequest servletRequest = getCurrentRequest();
        String countryCode = servletRequest != null ? geoService.getCountryCode(servletRequest) : null;
        String currencyCode = geoService.getCurrencyCode(countryCode);
        String languageCode = servletRequest != null ? geoService.getLanguageCode(servletRequest) : null;
        User user = UserRequest.toEntity(request, encodedPassword, UserRole.CUSTOMER, countryCode, currencyCode, languageCode);
        user.setEmailVerified(false);
        user.setEmailVerificationToken(Utils.generateVerificationCode());
        user.setEmailVerificationTokenExpiry(LocalDateTime.now().plusHours(24));
        user.setEnabled(true);
        user.setBlocked(false);
        user.setFraud(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        user = userRepository.save(user);

        authenticate(user, servletRequest);
        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), user.getEmailVerificationToken());
        emailService.sendRegistrationConfirmationEmail(user.getEmail(), user.getFullName());

        return UserResponse.toResponse(user);
    }



    private HttpServletRequest getCurrentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }

        return null;
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        requireAdmin();
        User user = findUserEntityById(id);
        user.setEnabled(false);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public UserResponse updateUser(Long id, UserRequest request) {
        requireSelfOrAdmin(id);
        User user = findUserEntityById(id);

        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            String email = request.getEmail().trim().toLowerCase();
            if (!email.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmailIgnoreCase(email)) {
                throw new BadRequestException("An account already exists with this email. Please recover the existing account.");
            }
            user.setEmail(email);
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        if (request.getFirstName() != null && !request.getFirstName().isBlank()) {
            user.setFirstName(request.getFirstName().trim());
        }
        if (request.getLastName() != null && !request.getLastName().isBlank()) {
            user.setLastName(request.getLastName().trim());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone().isBlank() ? null : request.getPhone().trim());
        }
        if (request.getProfileImageUrl() != null) {
            user.setProfileImageUrl(request.getProfileImageUrl().isBlank() ? null : request.getProfileImageUrl().trim());
        }

        return UserResponse.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse blockUser(Long id, boolean blocked, boolean fraud) {
        requireAdmin();
        User user = findUserEntityById(id);
        user.setBlocked(blocked);
        user.setFraud(fraud);
        return UserResponse.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse unblockUser(Long id) {
        requireAdmin();
        User user = findUserEntityById(id);
        user.setBlocked(false);
        return UserResponse.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse markFraud(Long id, boolean fraud) {
        requireAdmin();
        User user = findUserEntityById(id);
        user.setFraud(fraud);
        return UserResponse.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public UserResponse enableUser(Long id) {
        requireAdmin();
        User user = findUserEntityById(id);
        user.setEnabled(true);
        User saved = userRepository.save(user);
        emailService.sendAccountStatusChangeEmail(saved.getEmail(), saved.getFullName(), true, "Account enabled");
        return UserResponse.toResponse(saved);
    }

    @Override
    @Transactional
    public UserResponse disableUser(Long id) {
        requireAdmin();
        User user = findUserEntityById(id);
        user.setEnabled(false);
        User saved = userRepository.save(user);
        emailService.sendAccountStatusChangeEmail(saved.getEmail(), saved.getFullName(), false, "Account disabled");
        return UserResponse.toResponse(saved);
    }

    @Override
    @Transactional
    public UserResponse updatePreferences(Long id, String preferredCurrency, String preferredLanguage) {
        requireSelfOrAdmin(id);
        User user = findUserEntityById(id);

        if (preferredCurrency != null && !preferredCurrency.isBlank()) {
            String currency = preferredCurrency.trim().toUpperCase(Locale.ROOT);
            try {
                Currency.getInstance(currency);
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Preferred currency must be a valid ISO 4217 code");
            }
            user.setPreferredCurrency(currency);
        }

        if (preferredLanguage != null && !preferredLanguage.isBlank()) {
            Locale locale = Locale.forLanguageTag(preferredLanguage.trim());
            if (locale.getLanguage() == null || locale.getLanguage().isBlank()) {
                throw new BadRequestException("Preferred language must be a valid language tag");
            }
            user.setPreferredLanguage(locale.toLanguageTag());
        }

        return UserResponse.toResponse(userRepository.save(user));
    }

    @Override
    @Transactional
    public void changePassword(Long id, String currentPassword, String newPassword) {
        requireSelfOrAdmin(id);
        User user = findUserEntityById(id);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new BadRequestException("Current password is incorrect");
        }
        validatePasswordStrength(newPassword);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void requestPasswordReset(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        userRepository.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            if (!user.isEnabled() || user.isBlocked() || user.isFraud()) {
                return;
            }

            user.setPasswordResetToken(generateSecureToken());
            user.setPasswordResetTokenExpiry(LocalDateTime.now().plusHours(24));
            User saved = userRepository.save(user);
            emailService.sendPasswordResetEmail(saved.getEmail(), saved.getFullName(), saved.getPasswordResetToken());
        });
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Password reset token is required");
        }
        validatePasswordStrength(newPassword);

        User user = userRepository.findByPasswordResetToken(token.trim())
                .orElseThrow(() -> new BadRequestException("Invalid or expired password reset token"));
        LocalDateTime expiry = user.getPasswordResetTokenExpiry();
        if (expiry == null || expiry.isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Invalid or expired password reset token");
        }
        if (!user.isEnabled() || user.isBlocked() || user.isFraud()) {
            throw new AccessDeniedException("Account is not allowed to reset password");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);
        userRepository.save(user);
    }

    @Override
    public boolean verifyPassword(Long id, String password) {
        requireSelfOrAdmin(id);
        if (password == null || password.isBlank()) {
            return false;
        }
        User user = findUserEntityById(id);
        return passwordEncoder.matches(password, user.getPassword());
    }

    @Override
    @Transactional
    public void deleteAccountPermanently(Long id) {
        requireAdmin();
        User user = findUserEntityById(id);
        userRepository.delete(user);
    }


    @Override
    public void logout(Long userId) {
        Long authenticatedUserId = getAuthenticatedUserId();
        if (userId != null && authenticatedUserId != null && !userId.equals(authenticatedUserId)) {
            throw new AccessDeniedException("Cannot logout another user");
        }

        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }

        SecurityContextHolder.clearContext();
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Verification token is required");
        }

        User user = userRepository.findByEmailVerificationToken(token.trim())
                .orElseThrow(() -> new BadRequestException("Invalid or expired verification token"));

        if (user.isEmailVerified()) {
            user.setEmailVerificationToken(null);
            user.setEmailVerificationTokenExpiry(null);
            userRepository.save(user);
            return;
        }

        LocalDateTime expiry = user.getEmailVerificationTokenExpiry();
        if (expiry == null || expiry.isBefore(LocalDateTime.now())) {
            throw new BadRequestException("Invalid or expired verification token");
        }

        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void resendVerificationEmail(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        userRepository.findByEmailIgnoreCase(email.trim()).ifPresent(user -> {
            if (user.isEmailVerified()) {
                return;
            }
            if (!user.isEnabled() || user.isBlocked() || user.isFraud()) {
                return;
            }

            user.setEmailVerificationToken(Utils.generateVerificationCode());
            user.setEmailVerificationTokenExpiry(LocalDateTime.now().plusHours(24));
            User saved = userRepository.save(user);
            emailService.sendVerificationEmail(saved.getEmail(), saved.getFullName(), saved.getEmailVerificationToken());
        });
    }

    private Long getAuthenticatedUserId() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        if (securityContext.getAuthentication() == null) {
            return null;
        }

        Object principal = securityContext.getAuthentication().getPrincipal();
        if (principal instanceof User user) {
            return user.getId();
        }
        if (principal instanceof Long id) {
            return id;
        }
        if (principal instanceof String value) {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private void requireSelfOrAdmin(Long userId) {
        if (isAdmin()) {
            return;
        }

        Long authenticatedUserId = getAuthenticatedUserId();
        if (authenticatedUserId == null || userId == null || !authenticatedUserId.equals(userId)) {
            throw new AccessDeniedException("Access is denied");
        }
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new BadRequestException("Password must be between 8 and 72 characters");
        }
        if (!password.matches(".*[a-z].*")
                || !password.matches(".*[A-Z].*")
                || !password.matches(".*\\d.*")
                || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new BadRequestException("Password must contain uppercase, lowercase, number, and special character");
        }
    }

    private void authenticate(User user, HttpServletRequest request) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user,
                null,
                List.of(authority)
        );

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        if (request != null) {
            request.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    securityContext
            );
        }
    }


    private void requireAdmin() {
        if (!isAdmin()) {
            throw new AccessDeniedException("Admin access is required");
        }
    }

    private boolean isAdmin() {
        SecurityContext securityContext = SecurityContextHolder.getContext();
        if (securityContext.getAuthentication() == null) {
            return false;
        }

        return securityContext.getAuthentication().getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

}
