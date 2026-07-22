package com.sujula.dto.request.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class ChangePasswordRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "new Password is required")
    private  String newPassword;


    @NotBlank(message = "Old Password is required")
    private  String currentPassword;



}
