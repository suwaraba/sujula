package com.sujula.service.impl;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.CountryResponse;
import com.sujula.service.GeoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

@Service
public class GeoServiceImp implements GeoService {

    private static final List<String> COUNTRY_HEADERS = List.of(
            "CF-IPCountry",
            "CloudFront-Viewer-Country",
            "X-AppEngine-Country",
            "X-Vercel-IP-Country"
    );

    private static final List<String> IP_HEADERS = List.of(
            "CF-Connecting-IP",
            "True-Client-IP",
            "X-Forwarded-For",
            "X-Real-IP",
            "Forwarded"
    );

    private final DatabaseReader countryReader;

    public GeoServiceImp(
            @Value("${sujula.geoip.country-database:classpath:GeoLite2-Country.mmdb}") Resource countryDatabase
    ) throws IOException {
        if (!countryDatabase.exists()) {
            throw new IllegalStateException(
                    "GeoIP country database is required. Configure sujula.geoip.country-database or provide GeoLite2-Country.mmdb on the classpath."
            );
        }

        try (InputStream inputStream = countryDatabase.getInputStream()) {
            this.countryReader = new DatabaseReader.Builder(inputStream).build();
        }
    }

    @Override
    public String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        for (String header : IP_HEADERS) {
            String candidate = firstHeaderValue(request.getHeader(header));
            if (candidate != null && "Forwarded".equalsIgnoreCase(header)) {
                candidate = forwardedFor(candidate);
            }
            if (candidate != null && isPublicIp(candidate)) {
                return candidate;
            }
        }

        String remoteAddr = request.getRemoteAddr();
        return isPublicIp(remoteAddr) ? remoteAddr : null;
    }

    @Override
    public String getCurrencyCode(String countryCode) {
        String normalizedCountry = normalizeCountryCode(countryCode);
        if (normalizedCountry == null) {
            return null;
        }

        try {
            return Currency.getInstance(new Locale("", normalizedCountry)).getCurrencyCode();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public String getCountryCode(String ip) {
        if (!isPublicIp(ip)) {
            return null;
        }

        try {
            CountryResponse response = countryReader.country(InetAddress.getByName(ip));
            return normalizeCountryCode(response.getCountry().getIsoCode());
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String getCountryCode(HttpServletRequest request) {
        String countryFromHeader = getCountryFromHeaders(request);
        if (countryFromHeader != null) {
            return countryFromHeader;
        }

        return getCountryCode(getClientIp(request));
    }

    @Override
    public String getCurrencyCode(HttpServletRequest request) {
        return getCurrencyCode(getCountryCode(request));
    }

    @Override
    public String getLanguageCode(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return null;
        }

        try {
            for (Locale.LanguageRange range : Locale.LanguageRange.parse(acceptLanguage)) {
                Locale locale = Locale.forLanguageTag(range.getRange());
                if (locale.getLanguage() != null && !locale.getLanguage().isBlank()) {
                    return locale.toLanguageTag();
                }
            }
        } catch (IllegalArgumentException ex) {
            return null;
        }

        return null;
    }

    private String getCountryFromHeaders(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        for (String header : COUNTRY_HEADERS) {
            String countryCode = normalizeCountryCode(request.getHeader(header));
            if (countryCode != null && !"XX".equals(countryCode) && !"T1".equals(countryCode)) {
                return countryCode;
            }
        }

        return null;
    }

    private String firstHeaderValue(String value) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) {
            return null;
        }

        return value.split(",")[0].trim();
    }

    private String forwardedFor(String forwardedHeader) {
        for (String part : forwardedHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "for=", 0, 4)) {
                String value = trimmed.substring(4).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        }

        return null;
    }

    private String normalizeCountryCode(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return null;
        }

        String normalized = countryCode.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("^[A-Z]{2}$") ? normalized : null;
    }

    private boolean isPublicIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }

        try {
            InetAddress address = InetAddress.getByName(ip.trim());
            return !address.isAnyLocalAddress()
                    && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress()
                    && !address.isSiteLocalAddress()
                    && !address.isMulticastAddress();
        } catch (Exception ex) {
            return false;
        }
    }
}
