package com.sujula.dto.request.money;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a seller sends about their money. */
public final class MoneyRequests {

    private MoneyRequests() {}

    /**
     * Asking to be paid.
     *
     * <p>There is no amount. A request is for whatever is available in one
     * currency, which removes the only thing about it that could be wrong: a
     * figure the seller typed that does not match what they are owed. If they
     * are owed nothing there is nothing to ask for, and that is arithmetic
     * rather than a rule somebody set.
     *
     * <p>Nothing else gates it. A seller may always ask; an administrator
     * decides, because money leaving the platform is the one action no later
     * call can undo.
     */
    public record RequestPayout(
            /**
             * Which currency to be paid in. Defaults to the shop's settlement
             * currency, which is the only one most shops ever have.
             */
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "A currency is a three-letter code, like GMD")
            String currency,

            @Size(max = 300, message = "A note can be at most 300 characters")
            String note) {

        public String normalisedCurrency() {
            return currency == null || currency.isBlank() ? null
                    : currency.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }
}
