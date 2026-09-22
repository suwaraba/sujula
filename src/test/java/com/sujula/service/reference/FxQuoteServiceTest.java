package com.sujula.service.reference;

import com.sujula.dto.request.reference.FxQuoteRequest;
import com.sujula.dto.response.reference.ReferenceResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.ExchangeRate;
import com.sujula.model.reference.FxQuote;
import com.sujula.model.user.User;
import com.sujula.repository.ExchangeRateRepository;
import com.sujula.repository.reference.FxQuoteRepository;
import com.sujula.repository.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rates, and the difference between saying what one is and promising to honour
 * it.
 */
class FxQuoteServiceTest {

    private static final Long OWNER = 4L;
    private static final Long INTRUDER = 99L;

    private ExchangeRateRepository rates;
    private FxQuoteRepository quotes;
    private FxQuoteService service;
    private ReferenceDataProperties properties;
    private final List<FxQuote> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rates = mock(ExchangeRateRepository.class);
        quotes = mock(FxQuoteRepository.class);
        UserRepository users = mock(UserRepository.class);
        properties = new ReferenceDataProperties();
        CurrencyCatalogue catalogue = CurrencyCatalogue.of(properties);

        User owner = new User();
        owner.setId(OWNER);
        when(users.findById(OWNER)).thenReturn(Optional.of(owner));

        when(rates.findTopByFromCurrencyAndCurrencyOrderByRateDateDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(quotes.save(any(FxQuote.class))).thenAnswer(call -> {
            FxQuote quote = call.getArgument(0);
            stored.removeIf(existing -> existing.getId().equals(quote.getId()));
            stored.add(quote);
            return quote;
        });
        when(quotes.findLive(any())).thenAnswer(call -> {
            String id = call.getArgument(0);
            return stored.stream()
                    .filter(quote -> quote.getId().equals(id) && !quote.isExpired())
                    .findFirst();
        });

        service = new FxQuoteService(rates, quotes, users, catalogue, properties);
    }

    private void publish(String from, String to, String rate) {
        ExchangeRate row = new ExchangeRate();
        row.setFromCurrency(from);
        row.setCurrency(to);
        row.setRate(new BigDecimal(rate));
        row.setRateDate(LocalDate.of(2026, 9, 12));
        when(rates.findTopByFromCurrencyAndCurrencyOrderByRateDateDesc(from, to))
                .thenReturn(Optional.of(row));
    }

    // ── Indicative rates ─────────────────────────────────────────────────────

    @Test
    void aPublishedRateIsReturnedDirectly() {
        publish("GMD", "GBP", "0.01200000");

        ReferenceResponses.Rate rate = service.rate("GMD", "GBP");

        assertEquals(0, new BigDecimal("0.012").compareTo(rate.rate()));
        assertFalse(rate.inverted());
        assertTrue(rate.indicative(), "it must never read as a commitment");
        assertNotNull(rate.fetchedAt(), "a rate without an age invites someone to rely on it");
    }

    /**
     * A stored rate carries whatever spread was applied in its own direction, so
     * inverting it produces a number no dealer would offer going the other way.
     * Returning it is useful; passing it off as direct is not.
     */
    @Test
    void aMissingDirectionIsInvertedAndSaysSo() {
        publish("GMD", "GBP", "0.01250000");

        ReferenceResponses.Rate rate = service.rate("GBP", "GMD");

        assertTrue(rate.inverted());
        assertEquals(0, new BigDecimal("80").compareTo(rate.rate()));
        assertTrue(rate.message().contains("no spread"));
    }

    /** Dividing by a stored zero would take the request down rather than report a gap. */
    @Test
    void aStoredZeroRateIsReportedAsUnavailableNotDividedBy() {
        publish("GMD", "GBP", "0");

        ReferenceResponses.Rate rate = service.rate("GBP", "GMD");

        assertNull(rate.rate());
        assertTrue(rate.message().contains("No rate"));
    }

    @Test
    void aCurrencyIsWorthOneOfItself() {
        ReferenceResponses.Rate rate = service.rate("GMD", "GMD");

        assertEquals(0, BigDecimal.ONE.compareTo(rate.rate()));
        assertFalse(rate.inverted());
    }

    @Test
    void anUnpublishedPairSaysSoRatherThanGuessing() {
        ReferenceResponses.Rate rate = service.rate("GMD", "SEK");

        assertNull(rate.rate());
        assertNotNull(rate.message());
    }

    @Test
    void anUnsupportedCurrencyIsRefused() {
        assertThrows(BadRequestException.class, () -> service.rate("GMD", "JPY"));
    }

    // ── Held quotes ──────────────────────────────────────────────────────────

    @Test
    void aQuoteHoldsTheRateItWasCreatedAt() {
        publish("GMD", "GBP", "0.01200000");

        ReferenceResponses.FxQuote quote =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null);

        assertEquals(0, new BigDecimal("0.012").compareTo(quote.rate()));
        assertNotNull(quote.expiresAt());
        assertTrue(quote.expiresInSeconds() > 0);
        assertFalse(quote.consumed());
    }

    /**
     * The point of holding it: the rate table can move underneath a quote, and
     * the quote must not move with it.
     */
    @Test
    void aHeldRateDoesNotFollowTheMarket() {
        publish("GMD", "GBP", "0.01200000");
        ReferenceResponses.FxQuote held =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null);

        publish("GMD", "GBP", "0.00900000");   // the market moves

        assertEquals(0, new BigDecimal("0.012").compareTo(
                service.get(held.id(), null).rate()),
                "a quote that re-reads the table is not a quote");
    }

    @Test
    void anAmountIsConvertedAndRecorded() {
        publish("GMD", "GBP", "0.01200000");

        ReferenceResponses.FxQuote quote = service.createQuote(
                new FxQuoteRequest("GMD", "GBP", new BigDecimal("4500")), null);

        assertEquals(0, new BigDecimal("4500").compareTo(quote.baseAmount()));
        assertEquals(0, new BigDecimal("54.00").compareTo(quote.quoteAmount()));
    }

    /** Converting into CFA must land on a franc, because half a franc is not money. */
    @Test
    void aConvertedAmountIsRoundedToTheTargetCurrencysScale() {
        publish("GMD", "XOF", "8.53000000");

        ReferenceResponses.FxQuote quote = service.createQuote(
                new FxQuoteRequest("GMD", "XOF", new BigDecimal("100")), null);

        assertEquals(0, new BigDecimal("853").compareTo(quote.quoteAmount()));
        assertEquals(0, quote.quoteAmount().stripTrailingZeros().scale(),
                "a CFA amount carrying centimes is one nobody can hand over");
    }

    @Test
    void aPairWithNoRateCannotBeHeld() {
        BadRequestException refused = assertThrows(BadRequestException.class,
                () -> service.createQuote(new FxQuoteRequest("GMD", "SEK", null), null));

        assertTrue(refused.getMessage().contains("No rate is available"));
    }

    // ── Who may read one ─────────────────────────────────────────────────────

    @Test
    void aGuestReadsTheirQuoteBackWithTheIdAlone() {
        publish("GMD", "GBP", "0.012");
        ReferenceResponses.FxQuote quote =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null);

        assertEquals(quote.id(), service.get(quote.id(), null).id());
    }

    @Test
    void aSignedInShoppersQuoteIsNotReadableByAnybodyElse() {
        publish("GMD", "GBP", "0.012");
        ReferenceResponses.FxQuote quote =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), OWNER);

        assertEquals(quote.id(), service.get(quote.id(), OWNER).id());
        assertThrows(ResourceNotFoundException.class, () -> service.get(quote.id(), INTRUDER));
        assertThrows(ResourceNotFoundException.class, () -> service.get(quote.id(), null));
    }

    @Test
    void anExpiredQuoteIsIndistinguishableFromOneThatNeverExisted() {
        publish("GMD", "GBP", "0.012");
        ReferenceResponses.FxQuote quote =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null);
        stored.forEach(held -> held.setExpiresAt(LocalDateTime.now().minusSeconds(1)));

        assertThrows(ResourceNotFoundException.class, () -> service.get(quote.id(), null));
        assertThrows(ResourceNotFoundException.class, () -> service.get("never-issued", null));
    }

    @Test
    void idsAreUnguessable() {
        publish("GMD", "GBP", "0.012");
        String first = service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null).id();
        String second = service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null).id();

        assertNotEquals(first, second);
        assertTrue(first.length() >= 43, "a 256-bit id, got " + first.length());
        assertFalse(first.matches("\\d+"));
    }

    // ── Consumption ──────────────────────────────────────────────────────────

    @Test
    void aQuoteCanBeSpentOnce() {
        publish("GMD", "GBP", "0.012");
        ReferenceResponses.FxQuote quote =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null);

        service.consume(quote.id(), null);

        assertTrue(service.get(quote.id(), null).consumed());
        assertThrows(BadRequestException.class, () -> service.consume(quote.id(), null));
    }

    /**
     * A spent quote still reads back — a client reloading a confirmation page
     * should see it reported as used rather than as missing.
     */
    @Test
    void aSpentQuoteIsStillReadable() {
        publish("GMD", "GBP", "0.012");
        ReferenceResponses.FxQuote quote =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null);
        service.consume(quote.id(), null);

        assertNotNull(service.get(quote.id(), null));
    }

    @Test
    void theWindowComesFromConfiguration() {
        properties.setFxQuoteTtl(java.time.Duration.ofMinutes(2));
        publish("GMD", "GBP", "0.012");

        ReferenceResponses.FxQuote quote =
                service.createQuote(new FxQuoteRequest("GMD", "GBP", null), null);

        assertTrue(quote.expiresInSeconds() <= 120 && quote.expiresInSeconds() > 100,
                "got " + quote.expiresInSeconds());
    }
}
