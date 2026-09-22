package com.sujula.service.webhook;

import java.math.BigDecimal;
import java.util.Locale;

import com.sujula.service.reference.CurrencyCatalogue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a Stripe event into the flat shape the payment handler checks.
 *
 * <p>Two things about Stripe's shape would otherwise go wrong quietly. The
 * object sits two levels down, at {@code data.object}. And amounts are in minor
 * units — 10800 for 108.00 EUR — so read as they arrive, every honest payment
 * would fail the amount check, and the check exists precisely so that nothing
 * gets waved through when it disagrees.
 */
final class StripeEvents {

    private StripeEvents() {}

    /**
     * What a completed checkout needs before it counts. A session can complete
     * with {@code payment_status=unpaid} when the buyer chose a delayed method;
     * the money then arrives as {@code checkout.session.async_payment_succeeded}.
     */
    static boolean awaitingFunds(String eventType, JsonNode body) {
        JsonNode object = body.path("data").path("object");
        return "checkout.session.completed".equals(eventType)
                && !"paid".equals(object.path("payment_status").asString(""))
                && !"no_payment_required".equals(object.path("payment_status").asString(""));
    }

    /** {@code reference}, {@code id}, {@code amount} in major units, {@code currency}. */
    static JsonNode flatten(JsonNode body, ObjectMapper mapper, CurrencyCatalogue currencies) {
        JsonNode object = body.path("data").path("object");
        ObjectNode flat = mapper.createObjectNode();

        String reference = object.path("metadata").path("reference").asString("");
        if (reference.isBlank()) {
            reference = object.path("client_reference_id").asString("");
        }
        if (!reference.isBlank()) {
            flat.put("reference", reference);
        }
        String id = object.path("id").asString("");
        if (!id.isBlank()) {
            flat.put("id", id);
        }

        String currency = object.path("currency").asString("").toUpperCase(Locale.ROOT);
        if (!currency.isBlank()) {
            flat.put("currency", currency);
        }
        JsonNode minor = firstNumber(object, "amount_total", "amount_received", "amount");
        if (minor != null && !currency.isBlank() && currencies.isSupported(currency)) {
            BigDecimal amount = BigDecimal.valueOf(minor.asLong())
                    .movePointLeft(currencies.minorUnits(currency));
            flat.put("amount", amount.toPlainString());
        }

        JsonNode error = object.path("last_payment_error").path("message");
        if (error.isValueNode()) {
            flat.put("failureReason", error.asString());
        }
        return flat;
    }

    private static JsonNode firstNumber(JsonNode object, String... names) {
        for (String name : names) {
            JsonNode value = object.get(name);
            if (value != null && value.isNumber()) {
                return value;
            }
        }
        return null;
    }
}
