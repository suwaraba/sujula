package com.sujula.util;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class Utils {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");
    private static final Pattern MULTI_DASH = Pattern.compile("-+");
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final HttpClient httpClient = HttpClient.newHttpClient();


    public static String toSlug(String input) {
        if (input == null || input.isBlank()) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        String slug = WHITESPACE.matcher(normalized).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = MULTI_DASH.matcher(slug).replaceAll("-");
        return slug.toLowerCase(Locale.ENGLISH).replaceAll("^-|-$", "");
    }

    public static String generateVerificationCode() {
        int code = secureRandom.nextInt(1_000_000); // 0 to 999999
        return String.format("%06d", code);         // keeps leading zeros
    }

}
