package com.sujula.dto.request.reference;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Asks for a rate to be held.
 *
 * @param amount optional. Given, the quote records what that amount converts to,
 *               so what was promised is written down rather than recomputed from
 *               the rate later and possibly rounded differently.
 */
public record FxQuoteRequest(
        @NotBlank @Size(min = 3, max = 3) String base,
        @NotBlank @Size(min = 3, max = 3) String quote,
        @Positive @Digits(integer = 14, fraction = 4) BigDecimal amount) {}
