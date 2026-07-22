package com.sujula.dto.request.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class VerifyPasswordRequest {


    @NotBlank(message = "code is required")
    private  String password;


}
