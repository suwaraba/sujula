package com.sujula.service;



import com.sujula.dto.request.UserRequest;
import com.sujula.dto.response.user.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

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
   
