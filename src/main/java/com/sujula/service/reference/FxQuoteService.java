package com.sujula.service.reference;

import com.sujula.dto.request.reference.FxQuoteRequest;
import com.sujula.dto.response.reference.ReferenceResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.ExchangeRate;
import com.sujula.model.reference.FxQuote;
import com.sujula.repository.ExchangeRateRepository;
import com.sujula.repository.reference.FxQuoteRepository;
import com.sujula.repository.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Rates: what a pair is worth, and holding one still long enough to be paid at.
 *
 * <p>Two different promises, and the distinction is the whole point of this
 * class. {@code /currencies/rates} is indicative — it says what the pair was
 * last published at and how long ago, and commits to nothing.
 * {@code /currencies/quote} commits: this rate, this pair, for fifteen minutes.
 * A client that charges against the first is charging against a number that has
 * already moved.
 */
@Slf4j
@Service
public class FxQuoteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Working precision for rate arithmetic — not for money.
     *
     * <p>A rate is not an amount and must not be rounded like one. Rounding to
     * the currency's own scale before multiplying would flatten a rate to two
     * places, or for XOF to zero, and the error would then be multiplied by every
     * line of the order.
     */
    private static final MathContext RATE_MATH = new MathContext(16, RoundingMode.HALF_UP);
    private static final int RATE_SCALE = 8;

    private final ExchangeRateRepository rates;
    private final FxQuoteRepository quotes;
    private final UserRepository users;
    private final CurrencyCatalogue currencies;
    private final ReferenceDataProperties properties;

    public FxQuoteService(ExchangeRateRepository rates, FxQuoteRepository quotes,
                          UserRepository users, CurrencyCatalogue currencies,
                          ReferenceDataProperties properties) {
        this.rates = rates;
        this.quotes = quotes;
        this.users = users;
        this.currencies = currencies;
        this.properties = properties;
    }

    // ── Indicative ───────────────────────────────────────────────────────────

    /**
     * What the pair was last published at.
     *
     * <p>Three ways to answer, in descending order of directness: a stored rate
     * for exactly this pair; the reciprocal of the opposite pair; or, for a pair
     * with itself, one. The reciprocal is disclosed rather than passed off as a
     * direct quote — a stored GMD→GBP rate carries whatever spread was applied in
     * that direction, and inverting it produces a number no dealer would offer
     * going the other way.
     */
    @Transactional(readOnly = true)
    public ReferenceResponses.Rate rate(String baseCode, String quoteCode) {
        String base = currencies.require(baseCode);
        String quote = currencies.require(quoteCode);

        if (base.equals(quote)) {
            return new ReferenceResponses.Rate(base, quote, BigDecimal.ONE, true, false,
                    LocalDateTime.now(), "A currency is worth one of itself.");
        }

        Optional<ExchangeRate> direct = rates
                .findTopByFromCurrencyAndCurrencyOrderByRateDateDesc(base, quote);
        if (direct.isPresent()) {
            ExchangeRate found = direct.get();
            return new ReferenceResponses.Rate(base, quote,
                    found.getRate().setScale(RATE_SCALE, RoundingMode.HALF_UP),
                    true, false, publishedAt(found), null);
        }

        Optional<ExchangeRate> opposite = rates
                .findTopByFromCurrencyAndCurrencyOrderByRateDateDesc(quote, base);
        if (opposite.isPresent()) {
            ExchangeRate found = opposite.get();
            if (found.getRate() == null || found.getRate().signum() == 0) {
                // A stored zero cannot be inverted, and dividing by it would take
                // the request down rather than report a missing rate.
                return unavailable(base, quote);
            }
            BigDecimal inverted = BigDecimal.ONE.divide(found.getRate(), RATE_MATH)
                    .setScale(RATE_SCALE, RoundingMode.HALF_UP);
            return new ReferenceResponses.Rate(base, quote, inverted, true, true,
                    publishedAt(found),
                    "Derived by inverting the " + quote + " to " + base + " rate. It carries no "
                            + "spread in this direction.");
        }

        return unavailable(base, quote);
    }

    // ── Held ─────────────────────────────────────────────────────────────────

    /**
     * Locks a rate for the configured window.
     *
     * <p>The rate is resolved once, here, and written down — not referenced. A
     * quote that recomputed from the rate table when it was read would not be a
     * quote at all: the table can change underneath it, which is exactly what a
     * held rate exists to protect against.
     *
     * @param userId null for a guest, which is the ordinary case — prices are
     *               shown before anyone signs in
     */
    @Transactional
    public ReferenceResponses.FxQuote createQuote(FxQuoteRequest request, Long userId) {
        ReferenceResponses.Rate resolved = rate(request.base(), request.quote());

        if (resolved.rate() == null) {
            throw new BadRequestException(
                    "No rate is available for " + resolved.base() + " to " + resolved.quote()
                            + ", so no quote can be held. Orders in " + resolved.quote()
                            + " cannot be priced until one is published.");
        }

        LocalDateTime now = LocalDateTime.now();
        Duration ttl = properties.getFxQuoteTtl();

        FxQuote quote = FxQuote.builder()
                .id(newId())
                .baseCurrency(resolved.base())
                .quoteCurrency(resolved.quote())
                .rate(resolved.rate())
                .rateFetchedAt(resolved.fetchedAt())
                .createdAt(now)
                .expiresAt(now.plus(ttl))
                .build();

        if (request.amount() != null) {
            // Rounded to the target currency's own scale, because this figure is
            // money rather than a rate — and in CFA a figure carrying centimes is
            // one nobody can hand over.
            quote.setBaseAmount(request.amount());
            quote.setQuoteAmount(currencies.round(
                    request.amount().multiply(resolved.rate(), RATE_MATH), resolved.quote()));
        }

        if (userId != null) {
            quote.setUser(users.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId)));
        }

        FxQuote saved = quotes.save(quote);
        log.debug("[Fx] Quote {} held {} -> {} at {} for {}",
                saved.getId(), saved.getBaseCurrency(), saved.getQuoteCurrency(),
                saved.getRate(), ttl);
        return toResponse(saved);
    }

    /**
     * Reads a quote back.
     *
     * <p>Same rule as a delivery context: an expired quote is reported as not
     * found, so probing ids reveals nothing, and a quote belonging to an account
     * is readable only by that account.
     */
    @Transactional(readOnly = true)
    public ReferenceResponses.FxQuote get(String id, Long userId) {
        return toResponse(require(id, userId));
    }

    /**
     * The quote itself, for checkout to price against.
     *
     * <p>Not yet called from checkout — order placement still converts at the
     * rate table directly. This is the half that has to exist first; wiring
     * checkout to consume a quote changes what buyers are charged, which is a
     * change to make deliberately rather than as a side effect of adding an
     * endpoint.
     */
    @Transactional(readOnly = true)
    public FxQuote require(String id, Long userId) {
        FxQuote quote = quotes.findLive(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quote", id));

        if (!quote.isAnonymous() && !quote.belongsTo(userId)) {
            throw new ResourceNotFoundException("Quote", id);
        }
        return quote;
    }

    /**
     * Marks a quote as the one an order was priced at.
     *
     * <p>Recorded rather than deleted, so months later there is still an answer
     * to why a buyer was charged what they were.
     */
    @Transactional
    public void consume(String id, Long userId) {
        FxQuote quote = require(id, userId);
        if (quote.isConsumed()) {
            throw new BadRequestException(
                    "That quote has already been used for an order. Ask for a new one.");
        }
        quote.setConsumedAt(LocalDateTime.now());
        quotes.save(quote);
    }

    /** For the housekeeping job. Consumed quotes are kept as evidence. */
    @Transactional
    public int purgeExpired() {
        return quotes.deleteExpiredUnusedBefore(LocalDateTime.now());
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static ReferenceResponses.Rate unavailable(String base, String quote) {
        return new ReferenceResponses.Rate(base, quote, null, true, false, null,
                "No rate has been published for " + base + " to " + quote + ".");
    }

    /**
     * When the rate was published.
     *
     * <p>{@code rateDate} is a date, so the time of day is not recorded; start of
     * day is the honest reading of it rather than inventing an hour. The row's
     * own {@code createdAt} is when it was entered, which is a different fact.
     */
    private static LocalDateTime publishedAt(ExchangeRate rate) {
        return rate.getRateDate() == null
                ? rate.getCreatedAt()
                : rate.getRateDate().atStartOfDay();
    }

    private ReferenceResponses.FxQuote toResponse(FxQuote quote) {
        long seconds = Math.max(0, Duration.between(LocalDateTime.now(), quote.getExpiresAt())
                .getSeconds());
        return new ReferenceResponses.FxQuote(
                quote.getId(),
                quote.getBaseCurrency(),
                quote.getQuoteCurrency(),
                quote.getRate(),
                quote.getBaseAmount(),
                quote.getQuoteAmount(),
                quote.getRateFetchedAt(),
                quote.getCreatedAt(),
                quote.getExpiresAt(),
                seconds,
                quote.isConsumed());
    }

    /** 256 bits, url-safe. Sequential would put the next shopper's quote one increment away. */
    private static String newId() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
