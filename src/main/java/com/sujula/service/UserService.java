package com.sujula.service;



import com.sujula.dto.request.UserRequest;
import com.sujula.dto.response.user.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    /**
     * Signs a returning user in and puts them in the session.
     *
     * <p>An unverified email is not a barrier — registration signs a user in
     * before they have verified, so refusing them here would strand every
     * account created that way. The response carries {@code emailVerified} for
     * callers that need to gate on it.
     *
     * @throws org.springframework.security.authentication.BadCredentialsException
     *         if the email is unknown or the password is wrong — the same failure
     *         either way, so the endpoint cannot be used to discover which
     *         addresses hold accounts
     * @throws org.springframework.security.authentication.DisabledException  if the account is disabled
     * @throws org.springframework.security.authentication.LockedException    if the account is blocked or flagged for fraud
     */
    UserResponse login(String email, String password);

    UserResponse getCurrentUser(Long userId);

    UserResponse findById(Long id);

    UserResponse findByEmail(String email);

    Page<UserResponse> findAll(com.sujula.model.constant.UserRole role, Pageable pageable);

    //Page<UserResponse> search(String query, Pageable pageable);
    UserResponse save(UserRequest request);

    UserResponse updateUser(Long id, UserRequest request);

    void deleteById(Long id);

    UserResponse blockUser(Long id, boolean blocked, boolean fraud);

    UserResponse unblockUser(Long id);

    UserResponse markFraud(Long id, boolean fraud);

    UserResponse enableUser(Long id);

    UserResponse disableUser(Long id);

    UserResponse updatePreferences(Long id, String preferredCurrency, String preferredLanguage);

    void changePassword(Long id, String currentPassword, String newPassword);

    void requestPasswordReset(String email);

    void resetPassword(String token, String newPassword);

    boolean verifyPassword(Long id, String password);

    void deleteAccountPermanently(Long id);

    void logout(Long userId);

    void verifyEmail(String token);

    void resendVerificationEmail(String email);
}
   
