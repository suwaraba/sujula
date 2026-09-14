package com.sujula.service.delivery;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.logistics.DeliveryRateCard;
import com.sujula.repository.logistics.DeliveryRateCardRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Which rate card prices a leg, on a given day, to a given place.
 *
 * <p>Resolution is most specific first: a card for this zone and this mode, then
 * this zone for any mode, then this country and this mode, then this country,
 * then {@link DeliveryPricingProperties} — the numbers the deployment shipped
 * with. The fallback is not a failure case. Most of this platform's life will be
 * spent with no card written at all, and a marketplace that could not quote
 * carriage until somebody filled in a form would have no first order.
 *
 * <p><b>C1.</b> The zone and the country here are the <em>destination's</em>.
 * Nothing about the payer reaches this class. A buyer in Madrid paying for a
 * delivery to Serrekunda is quoted the Serrekunda card, and quoting them a
 * Spanish one because that is where the card was tapped would price a journey
 * nobody is making.
 *
 * <p><b>C2.</b> Everything returned is in the card's own currency. Converting is
 * the caller's job, once, at a rate it snapshots — a leg converted at quote and
 * again at checkout is a leg whose price changed between the two.
 */
@Slf4j
@Component
public class RateCardRegistry {

    private final DeliveryRateCardRepository cards;
    private final DeliveryPricingProperties properties;

    public RateCardRegistry(DeliveryRateCardRepository cards,
                            DeliveryPricingProperties properties) {
        this.cards = cards;
        this.properties = properties;
    }

    /** The resolved card, and where it came from, so a quote can say. */
    public record Resolved(LegRate rate, Long cardId, String cardName, String source) {}

    /**
     * The card in force for a destination on a day.
     *
     * @param zoneId      the zone the <em>destination</em> falls in, or null
     * @param countryCode the <em>destination</em> country, or null
     * @param mode        how the buyer receives the goods
     * @param day         the day the leg is being priced for — today for a live
     *                    quote, and the order's own date when re-explaining a
     *                    figure that was already charged
     */
    @Transactional(readOnly = true)
    public Resolved resolve(Long zoneId, String countryCode, DeliveryMode mode, LocalDate day) {
        List<DeliveryRateCard> inForce = cards.findInForceOn(day == null ? LocalDate.now() : day);

        Optional<DeliveryRateCard> best = inForce.stream()
                .filter(card -> matchesZone(card, zoneId))
                .filter(card -> matchesCountry(card, countryCode))
                .filter(card -> card.getMode() == null || card.getMode() == mode)
                // Most specific wins; a later start date breaks a tie between
                // two equally specific cards, which is the ordinary case when
                // one supersedes another on the same day it begins.
                .max(Comparator.comparingInt(DeliveryRateCard::specificity)
                        .thenComparing(DeliveryRateCard::getEffectiveFrom));

        return best.map(card -> new Resolved(toRate(card), card.getId(), card.getName(),
                        card.getZone() != null ? "zone " + card.getZone().getCode()
                                : card.getCountryCode() != null ? "country " + card.getCountryCode()
                                : "platform default card"))
                .orElseGet(() -> new Resolved(properties.asLegRate(), null,
                        "Configured default", "deployment configuration"));
    }

    /** The numbers on a card, as the shared formula wants them. */
    public LegRate toRate(DeliveryRateCard card) {
        return new LegRate(card.getCurrency(), card.getBaseFee(), card.getIncludedKm(),
                card.getPerKm(), card.getIncludedKg(), card.getPerKg(), card.getMinFee(),
                card.getMaxFee(), card.getFreeAbove(),
                // Scope and mode multipliers stay with the deployment rather than
                // the row. A card is a price for a place; how much further afield
                // a product ships and how the buyer receives it are properties of
                // the journey, and duplicating them onto every row is how two
                // zones come to disagree about what "national" means.
                properties.getScopeMultiplier(), properties.getModeMultiplier());
    }

    /** A card with no zone applies wherever its country does. */
    private static boolean matchesZone(DeliveryRateCard card, Long zoneId) {
        if (card.getZone() == null) return true;
        return zoneId != null && zoneId.equals(card.getZone().getId());
    }

    private static boolean matchesCountry(DeliveryRateCard card, String countryCode) {
        if (card.getCountryCode() == null) return true;
        return countryCode != null && card.getCountryCode().equalsIgnoreCase(countryCode);
    }
}
