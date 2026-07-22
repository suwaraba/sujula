package com.sujula.dto.request;

import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 150, message = "Email must not exceed 150 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).+$",
            message = "Password must contain uppercase, lowercase, number, and special character"
    )
    private String password;

    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    private String lastName;

    @Pattern(
            regexp = "^$|^\\+[1-9]\\d{7,14}$",
            message = "Phone must include country prefix, for example +2201234567"
    )
    private String phone;

    @Size(max = 500, message = "Profile image URL must not exceed 500 characters")
    private String profileImageUrl;

    public static User toEntity(UserRequest request, String encodedPassword, UserRole role, String detectedCountryCode, String preferredCurrency, String preferredLanguage) {
        User user = new User();
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setPassword(encodedPassword);
        user.setFirstName(request.getFirstName().trim());
        user.setLastName(request.getLastName().trim());
        user.setPhone(normalizeBlank(request.getPhone()));
        user.setProfileImageUrl(normalizeBlank(request.getProfileImageUrl()));
        user.setRole(role);
        user.setDetectedCountryCode(detectedCountryCode);
        user.setPreferredCurrency(preferredCurrency);
        user.setPreferredLanguage(preferredLanguage);

       return user;
    }

    private static String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
