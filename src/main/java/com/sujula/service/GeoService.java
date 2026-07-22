package com.sujula.service;

import jakarta.servlet.http.HttpServletRequest;

public interface GeoService {

    String getClientIp(HttpServletRequest request);
    String getCurrencyCode(String countryCode);
    String getCountryCode(String ip);
    String getCountryCode(HttpServletRequest request);
    String getCurrencyCode(HttpServletRequest request);
    String getLanguageCode(HttpServletRequest request);

}
