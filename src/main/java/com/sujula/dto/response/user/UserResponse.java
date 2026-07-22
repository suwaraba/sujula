package com.sujula.dto.response.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sujula.model.constant.UserRole;
import com.sujula.model.user.User;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
    private Long          id;
    private String        email;
    private String        firstName;
    private String        lastName;
    private String        fullName;
    private String        phone;
    private String        profileImageUrl;
    private UserRole      role;
    private boolean       emailVerified;
    private String        preferredCurrency;
    private String        preferredLanguage;
    private LocalDateTime createdAt;

    public static UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .profileImageUrl(user.getProfileImageUrl())
                .role(user.getRole())
                .emailVerified(user.isEmailVerified())
                .preferredCurrency(user.getPreferredCurrency())
                .preferredLanguage(user.getPreferredLanguage())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
