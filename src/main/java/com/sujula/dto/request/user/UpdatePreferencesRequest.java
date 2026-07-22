package com.sujula.dto.request.user;

import lombok.Getter;

@Getter
public class UpdatePreferencesRequest {

    private String PreferredCurrency;

    private String PreferredLanguage;
}
