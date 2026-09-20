package com.sujula.config.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.sujula.model.ExchangeRate;
import com.sujula.model.GiftCard;
import com.sujula.model.constant.CouponScope;
import com.sujula.model.constant.CouponType;
import com.sujula.model.constant.PromotionStatus;
import com.sujula.model.constant.PromotionType;
import com.sujula.model.finance.FxSpread;
import com.sujula.model.products.Coupon;
import com.sujula.model.promotion.Promotion;
import com.sujula.model.reference.FxQuote;

/**
 * Rates, spreads, held quotes and the things that take money off a total.
 *
 * <p>The rate table has three days in it rather than one. That is the whole
 * point of C2: an order priced on Monday cannot be re-derived from Wednesday's
 * rate, and sample data holding a single day's rates makes every snapshot look
 * like it could have been recomputed. With three days present, a snapshot that
 * disagrees with today's table is visibly correct rather than visibly stale.
 *
 * <p>Rates are stored as units of the quote currency per one unit of the base —
 * the same direction {@code FxSnapshot} uses, so {@code display = native × rate}.
 * Reading it backwards is silent, which is why it is written down here as well
 * as there.
 */
@Component
class MoneyStage implements SeedStage {

    @Override
    public String name() {
        return "Exchange rates, spreads, quotes, coupons and promotions";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        exchangeRates(cat);
        spreads(cat);
        quotes(cat);
        giftCards(cat);
        coupons(cat);
        cat.flush();
        promotions(cat);
    }

    // ── Published rates ──────────────────────────────────────────────────────

    private void exchangeRates(SeedCatalogue cat) {
        LocalDate today = LocalDate.now();
        // Three consecutive days, each slightly different. Rates that never move
        // let arithmetic that re-reads them pass by accident.
        rates(cat, today, "1.00000000");
        rates(cat, today.minusDays(1), "0.99820000");
        rates(cat, today.minusDays(2), "1.00310000");
    }

    /**
     * One day's table.
     *
     * @param drift multiplies every rate so consecutive days genuinely differ
     */
    private void rates(SeedCatalogue cat, LocalDate day, String drift) {
        BigDecimal factor = new BigDecimal(drift);
        // From the dalasi, into what the diaspora pays in.
        rate(cat, "GMD", "EUR", "ES", "0.01352000", factor, day);
        rate(cat, "GMD", "GBP", "GB", "0.01148000", factor, day);
        rate(cat, "GMD", "USD", "US", "0.01455000", factor, day);
        rate(cat, "GMD", "SEK", "SE", "0.15540000", factor, day);
        rate(cat, "GMD", "XOF", "SN", "8.86000000", factor, day);
        // From the CFA franc.
        rate(cat, "XOF", "EUR", "FR", "0.00152449", factor, day);
        rate(cat, "XOF", "GBP", "GB", "0.00129500", factor, day);
        rate(cat, "XOF", "GMD", "GM", "0.11287000", factor, day);
        // Back the other way, for figures quoted to a seller in a buyer's money.
        rate(cat, "EUR", "GMD", "GM", "73.96000000", factor, day);
        rate(cat, "GBP", "GMD", "GM", "87.10000000", factor, day);
        rate(cat, "EUR", "XOF", "SN", "655.95700000", factor, day);
    }

    private void rate(SeedCatalogue cat, String from, String to, String country,
                      String base, BigDecimal factor, LocalDate day) {
        cat.save(ExchangeRate.builder()
                .fromCurrency(from).currency(to).country(country)
                .rate(new BigDecimal(base).multiply(factor)
                        .setScale(8, java.math.RoundingMode.HALF_UP))
                .rateDate(day)
                .build());
    }

    // ── Spreads ──────────────────────────────────────────────────────────────

    /**
     * The margin taken over the published rate, in basis points.
     *
     * <p>Most specific wins, so the sample has a catch-all with both currencies
     * null, one that fixes only the source, and two that name both sides. A
     * lookup that takes the first match rather than the most specific one picks
     * the wrong row against this data, which is the point of having it.
     */
    private void spreads(SeedCatalogue cat) {
        Long setBy = cat.user("admin").getId();
        cat.save(FxSpread.builder()
                .basisPoints(150).effectiveFrom(cat.daysAgo(400)).setByUserId(setBy)
                .reason("Platform default: 1.5% over the published mid-rate.")
                .build());
        cat.save(FxSpread.builder()
                .fromCurrency("GMD").basisPoints(120)
                .effectiveFrom(cat.daysAgo(200)).setByUserId(setBy)
                .reason("Dalasi-listed goods, any buyer currency.")
                .build());
        cat.save(FxSpread.builder()
                .fromCurrency("GMD").toCurrency("EUR").basisPoints(90)
                .effectiveFrom(cat.daysAgo(120)).setByUserId(setBy)
                .reason("Largest corridor by volume; narrower margin.")
                .build());
        cat.save(FxSpread.builder()
                .fromCurrency("GMD").toCurrency("GBP").basisPoints(95)
                .effectiveFrom(cat.daysAgo(120)).setByUserId(setBy)
                .reason("Second corridor by volume.")
                .build());
        cat.save(FxSpread.builder()
                .fromCurrency("XOF").toCurrency("EUR").basisPoints(40)
                .effectiveFrom(cat.daysAgo(90)).setByUserId(setBy)
                .reason("CFA is pegged to the euro, so the risk taken is small.")
                .build());
        // Superseded by the 90 bp row above. Kept because an order priced four
        // months ago was priced on this one.
        cat.save(FxSpread.builder()
                .fromCurrency("GMD").toCurrency("EUR").basisPoints(175)
                .effectiveFrom(cat.daysAgo(300)).setByUserId(setBy)
                .reason("Opening margin, before volume justified narrowing it.")
                .build());
    }

    // ── Held quotes ──────────────────────────────────────────────────────────

    /**
     * Rates locked for a buyer while they fill in a card form.
     *
     * <p>Fifteen minutes each. The sample has one still live, one that ran out,
     * one already spent on an order, and one held by nobody at all — a guest
     * holds a quote exactly like anybody else, because C5's recipient is not the
     * only person here without an account.
     */
    private void quotes(SeedCatalogue cat) {
        cat.save(FxQuote.builder()
                .id("fxq-sample-isatou-live-0001").user(cat.user("isatou"))
                .baseCurrency("GMD").quoteCurrency("EUR")
                .rate(SeedCatalogue.rate("0.01339800"))
                .baseAmount(new BigDecimal("8500.0000")).quoteAmount(new BigDecimal("113.8830"))
                .rateFetchedAt(cat.hoursAgo(1))
                .createdAt(cat.hoursAgo(1)).expiresAt(cat.hoursAhead(1))
                .build());
        cat.save(FxQuote.builder()
                .id("fxq-sample-isatou-consumed-0002").user(cat.user("isatou"))
                .baseCurrency("GMD").quoteCurrency("EUR")
                .rate(SeedCatalogue.rate("0.01341000"))
                .baseAmount(new BigDecimal("8620.0000")).quoteAmount(new BigDecimal("115.6002"))
                .rateFetchedAt(cat.daysAgo(12))
                .createdAt(cat.daysAgo(12)).expiresAt(cat.daysAgo(12).plusMinutes(15))
                .consumedAt(cat.daysAgo(12).plusMinutes(4))
                .build());
        cat.save(FxQuote.builder()
                .id("fxq-sample-modou-expired-0003").user(cat.user("modou"))
                .baseCurrency("GMD").quoteCurrency("GBP")
                .rate(SeedCatalogue.rate("0.01137200"))
                .baseAmount(new BigDecimal("9750.0000")).quoteAmount(new BigDecimal("110.8770"))
                .rateFetchedAt(cat.daysAgo(2))
                .createdAt(cat.daysAgo(2)).expiresAt(cat.daysAgo(2).plusMinutes(15))
                .build());
        cat.save(FxQuote.builder()
                .id("fxq-sample-sally-live-0004").user(cat.user("sally"))
                .baseCurrency("XOF").quoteCurrency("EUR")
                .rate(SeedCatalogue.rate("0.00151840"))
                .baseAmount(new BigDecimal("95000.0000")).quoteAmount(new BigDecimal("144.2480"))
                .rateFetchedAt(cat.hoursAgo(1))
                .createdAt(cat.hoursAgo(1)).expiresAt(cat.hoursAhead(1))
                .build());
        // Nobody signed in.
        cat.save(FxQuote.builder()
                .id("fxq-sample-anonymous-0005")
                .baseCurrency("GMD").quoteCurrency("EUR")
                .rate(SeedCatalogue.rate("0.01339800"))
                .baseAmount(new BigDecimal("1450.0000")).quoteAmount(new BigDecimal("19.4271"))
                .rateFetchedAt(cat.hoursAgo(2))
                .createdAt(cat.hoursAgo(2)).expiresAt(cat.hoursAhead(1))
                .build());
        // Same currency both sides. The rate is one, and it still gets a row,
        // because "no conversion happened" is itself a fact worth recording.
        cat.save(FxQuote.builder()
                .id("fxq-sample-binta-identity-0006").user(cat.user("binta"))
                .baseCurrency("GMD").quoteCurrency("GMD")
                .rate(SeedCatalogue.rate("1.00000000"))
                .baseAmount(new BigDecimal("1450.0000")).quoteAmount(new BigDecimal("1450.0000"))
                .rateFetchedAt(cat.hoursAgo(3))
                .createdAt(cat.hoursAgo(3)).expiresAt(cat.hoursAhead(1))
                .build());
    }

    // ── Gift cards ───────────────────────────────────────────────────────────

    private void giftCards(SeedCatalogue cat) {
        cat.save(GiftCard.builder()
                .code("SJL-GIFT-4K7M-2QX9").initialAmount(SeedCatalogue.money("2000.00"))
                .remainingBalance(SeedCatalogue.money("2000.00")).currency("GMD")
                .issuedToEmail("binta.touray@example.gm")
                .purchasedByUserId(cat.user("isatou").getId())
                .expiresAt(cat.daysAhead(300)).active(true)
                .build());
        // Half spent. The two figures differing is the state most code gets
        // wrong by reading the wrong one.
        cat.save(GiftCard.builder()
                .code("SJL-GIFT-8P2R-5TD1").initialAmount(SeedCatalogue.money("5000.00"))
                .remainingBalance(SeedCatalogue.money("2350.00")).currency("GMD")
                .issuedToEmail("yankuba.bah@example.gm")
                .purchasedByUserId(cat.user("modou").getId())
                .redeemedByUserId(cat.user("yankuba").getId())
                .expiresAt(cat.daysAhead(180)).active(true)
                .build());
        cat.save(GiftCard.builder()
                .code("SJL-GIFT-1H9W-7BK4").initialAmount(SeedCatalogue.money("1000.00"))
                .remainingBalance(SeedCatalogue.money("0.00")).currency("GMD")
                .issuedToEmail("fanta.kanteh@example.gm")
                .purchasedByUserId(cat.user("binta").getId())
                .redeemedByUserId(cat.user("fanta").getId())
                .expiresAt(cat.daysAhead(90)).active(true)
                .build());
        // Expired with money still on it. Worth its own row: the balance is not
        // zero and it still must not be spendable.
        cat.save(GiftCard.builder()
                .code("SJL-GIFT-3N6C-9FQ2").initialAmount(SeedCatalogue.money("1500.00"))
                .remainingBalance(SeedCatalogue.money("1500.00")).currency("GMD")
                .issuedToEmail("sulayman.gaye@example.gm")
                .purchasedByUserId(cat.user("binta").getId())
                .expiresAt(cat.daysAgo(10)).active(true)
                .build());
        // Cancelled after a chargeback on the card that bought it.
        cat.save(GiftCard.builder()
                .code("SJL-GIFT-5Z1L-4RM8").initialAmount(SeedCatalogue.money("3000.00"))
                .remainingBalance(SeedCatalogue.money("3000.00")).currency("GMD")
                .issuedToEmail("baboucarr.jatta@example.gm")
                .purchasedByUserId(cat.user("fanta").getId())
                .expiresAt(cat.daysAhead(200)).active(false)
                .build());
        // In CFA, so the balance carries no decimals at all.
        cat.save(GiftCard.builder()
                .code("SJL-GIFT-6V4J-1WS7").initialAmount(SeedCatalogue.wholeUnits("25000"))
                .remainingBalance(SeedCatalogue.wholeUnits("25000")).currency("XOF")
                .issuedToEmail("cheikh.ndiaye@example.sn")
                .purchasedByUserId(cat.user("sally").getId())
                .expiresAt(cat.daysAhead(365)).active(true)
                .build());
        // Bought and not yet given to anybody: no recipient address, no
        // redeemer.
        cat.save(GiftCard.builder()
                .code("SJL-GIFT-2Q8T-6YN3").initialAmount(SeedCatalogue.money("750.00"))
                .remainingBalance(SeedCatalogue.money("750.00")).currency("GMD")
                .purchasedByUserId(cat.user("mariama").getId())
                .expiresAt(cat.daysAhead(365)).active(true)
                .build());
    }

    // ── Coupons ──────────────────────────────────────────────────────────────

    private void coupons(SeedCatalogue cat) {
        cat.coupons.put("welcome10", cat.save(Coupon.builder()
                .code("WELCOME10").description("10% off a first order, up to D 1000.")
                .type(CouponType.PERCENTAGE).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("10.00"))
                .minimumOrderAmount(SeedCatalogue.money("1000.00"))
                .maximumDiscountAmount(SeedCatalogue.money("1000.00"))
                .usageLimit(1000).usageCount(214).perUserLimit(1)
                .active(true).startsAt(cat.daysAgo(120)).expiresAt(cat.daysAhead(60))
                .build()));

        cat.coupons.put("diaspora500", cat.save(Coupon.builder()
                .code("DIASPORA500").description("D 500 off orders over D 6000.")
                .type(CouponType.FIXED_AMOUNT).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("500.00")).currency("GMD")
                .minimumOrderAmount(SeedCatalogue.money("6000.00"))
                .usageLimit(300).usageCount(87).perUserLimit(2)
                .active(true).startsAt(cat.daysAgo(60)).expiresAt(cat.daysAhead(30))
                .build()));

        cat.coupons.put("freeship", cat.save(Coupon.builder()
                .code("FREESHIP").description("Delivery on the platform, once.")
                .type(CouponType.FREE_SHIPPING).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("0.00"))
                .minimumOrderAmount(SeedCatalogue.money("2500.00"))
                .usageLimit(500).usageCount(310).perUserLimit(1)
                .active(true).startsAt(cat.daysAgo(40)).expiresAt(cat.daysAhead(20))
                .build()));

        cat.coupons.put("bp15", cat.save(Coupon.builder()
                .code("BANJULPHONES15").description("15% off at Banjul Phones.")
                .type(CouponType.PERCENTAGE).scope(CouponScope.VENDOR)
                .vendor(cat.vendor("banjul-phones"))
                .value(SeedCatalogue.money("15.00"))
                .minimumOrderAmount(SeedCatalogue.money("5000.00"))
                .maximumDiscountAmount(SeedCatalogue.money("2000.00"))
                .usageLimit(100).usageCount(12).perUserLimit(1)
                .active(true).startsAt(cat.daysAgo(20)).expiresAt(cat.daysAhead(40))
                .build()));

        // CFA, whole francs. A vendor coupon whose value carried decimals would
        // discount an amount the buyer cannot be charged.
        cat.coupons.put("dt5000", cat.save(Coupon.builder()
                .code("DAKARTECH5000").description("5 000 CFA de réduction.")
                .type(CouponType.FIXED_AMOUNT).scope(CouponScope.VENDOR)
                .vendor(cat.vendor("dakar-tech"))
                .value(SeedCatalogue.wholeUnits("5000")).currency("XOF")
                .minimumOrderAmount(SeedCatalogue.wholeUnits("50000"))
                .usageLimit(200).usageCount(33).perUserLimit(1)
                .active(true).startsAt(cat.daysAgo(30)).expiresAt(cat.daysAhead(30))
                .build()));

        // Every use spent. Still active, still in date, and must be refused.
        cat.coupons.put("exhausted", cat.save(Coupon.builder()
                .code("TOBASKI24").description("D 250 off. Fully taken up.")
                .type(CouponType.FIXED_AMOUNT).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("250.00")).currency("GMD")
                .minimumOrderAmount(SeedCatalogue.money("1500.00"))
                .usageLimit(50).usageCount(50).perUserLimit(1)
                .active(true).startsAt(cat.daysAgo(25)).expiresAt(cat.daysAhead(15))
                .build()));

        // Ran out last week.
        cat.coupons.put("expired", cat.save(Coupon.builder()
                .code("KORITE24").description("20% off. Ended.")
                .type(CouponType.PERCENTAGE).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("20.00"))
                .maximumDiscountAmount(SeedCatalogue.money("1500.00"))
                .usageLimit(400).usageCount(288).perUserLimit(1)
                .active(true).startsAt(cat.daysAgo(60)).expiresAt(cat.daysAgo(7))
                .build()));

        // Starts next week. In the table and not yet usable.
        cat.coupons.put("scheduled", cat.save(Coupon.builder()
                .code("INDEPENDENCE").description("D 300 off, from the 18th.")
                .type(CouponType.FIXED_AMOUNT).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("300.00")).currency("GMD")
                .minimumOrderAmount(SeedCatalogue.money("2000.00"))
                .usageLimit(1000).usageCount(0).perUserLimit(1)
                .active(true).startsAt(cat.daysAhead(7)).expiresAt(cat.daysAhead(21))
                .build()));

        // Switched off by hand mid-run.
        cat.coupons.put("withdrawn", cat.save(Coupon.builder()
                .code("OOPS50").description("50% off. Withdrawn — the ceiling was set wrong.")
                .type(CouponType.PERCENTAGE).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("50.00"))
                .usageLimit(100).usageCount(9).perUserLimit(1)
                .active(false).startsAt(cat.daysAgo(5)).expiresAt(cat.daysAhead(25))
                .build()));

        // No limits at all: no ceiling, no per-user cap, no end date. The row
        // that finds every null check.
        cat.coupons.put("openended", cat.save(Coupon.builder()
                .code("STAFF5").description("5% for staff. No limits set.")
                .type(CouponType.PERCENTAGE).scope(CouponScope.PLATFORM)
                .value(SeedCatalogue.money("5.00"))
                .active(true).startsAt(cat.daysAgo(300))
                .build()));

        cat.coupons.put("sh-ship", cat.save(Coupon.builder()
                .code("HOMEFREE").description("Free delivery at Serrekunda Home.")
                .type(CouponType.FREE_SHIPPING).scope(CouponScope.VENDOR)
                .vendor(cat.vendor("serrekunda-home"))
                .value(SeedCatalogue.money("0.00"))
                .minimumOrderAmount(SeedCatalogue.money("1200.00"))
                .usageLimit(80).usageCount(21).perUserLimit(2)
                .active(true).startsAt(cat.daysAgo(15)).expiresAt(cat.daysAhead(45))
                .build()));
    }

    // ── Promotions ───────────────────────────────────────────────────────────

    /**
     * Seller-run promotions, one of every type and every status.
     *
     * <p>Unlike a coupon these need no code: they apply on their own, which is
     * why the window and the status have to be right. A paused promotion inside
     * its window and an active one whose window has closed are both here, and
     * they are the two rows that separate "is it switched on" from "is it
     * running now".
     */
    private void promotions(SeedCatalogue cat) {
        cat.save(Promotion.builder()
                .vendor(cat.vendor("banjul-phones"))
                .name("Back to school — 10% off handsets")
                .description("Ten percent off every phone in the shop.")
                .type(PromotionType.PERCENT).status(PromotionStatus.ACTIVE)
                .percentOff(SeedCatalogue.money("10.00"))
                .maximumDiscount(SeedCatalogue.money("1500.00"))
                .minimumBasket(SeedCatalogue.money("5000.00"))
                .categoryIds(ids(cat.category("phones").getId()))
                .startsAt(cat.daysAgo(10)).endsAt(cat.daysAhead(20))
                .timesApplied(46).activatedAt(cat.daysAgo(10))
                .build());

        cat.save(Promotion.builder()
                .vendor(cat.vendor("banjul-phones"))
                .name("D 500 off the iPhone 11")
                .type(PromotionType.FIXED).status(PromotionStatus.ACTIVE)
                .amountOff(SeedCatalogue.money("500.00")).currency("GMD")
                .productIds(ids(cat.product("iphone11").getId()))
                .startsAt(cat.daysAgo(5)).endsAt(cat.daysAhead(10))
                .timesApplied(3).activatedAt(cat.daysAgo(5))
                .build());

        cat.save(Promotion.builder()
                .vendor(cat.vendor("dakar-tech"))
                .name("Deux achetés, un à moitié prix")
                .description("Sur les accessoires.")
                .type(PromotionType.BUY_X_GET_Y).status(PromotionStatus.ACTIVE)
                .buyQuantity(2).getQuantity(1)
                .getDiscountPercent(SeedCatalogue.money("50.00"))
                .categoryIds(ids(cat.category("accessories").getId()))
                .startsAt(cat.daysAgo(8)).endsAt(cat.daysAhead(22))
                .timesApplied(17).activatedAt(cat.daysAgo(8))
                .build());

        cat.save(Promotion.builder()
                .vendor(cat.vendor("dakar-tech"))
                .name("Livraison offerte dès 100 000 CFA")
                .type(PromotionType.FREE_SHIPPING).status(PromotionStatus.ACTIVE)
                .minimumBasket(SeedCatalogue.wholeUnits("100000")).currency("XOF")
                .startsAt(cat.daysAgo(30)).endsAt(cat.daysAhead(60))
                .timesApplied(9).activatedAt(cat.daysAgo(30))
                .build());

        cat.save(Promotion.builder()
                .vendor(cat.vendor("serrekunda-home"))
                .name("Pot and blender bundle")
                .description("Both together for D 4200.")
                .type(PromotionType.BUNDLE).status(PromotionStatus.PAUSED)
                .bundlePrice(SeedCatalogue.money("4200.00")).currency("GMD")
                .productIds(ids(cat.product("castironpot").getId(), cat.product("blender").getId()))
                // Paused, not expired: the window is still open and it is not
                // being applied, because somebody switched it off.
                .startsAt(cat.daysAgo(14)).endsAt(cat.daysAhead(16))
                .timesApplied(4).activatedAt(cat.daysAgo(14))
                .build());

        cat.save(Promotion.builder()
                .vendor(cat.vendor("kololi-style"))
                .name("Fabric fortnight — 25% off")
                .type(PromotionType.PERCENT).status(PromotionStatus.EXPIRED)
                .percentOff(SeedCatalogue.money("25.00"))
                .categoryIds(ids(cat.category("fabric").getId()))
                .startsAt(cat.daysAgo(45)).endsAt(cat.daysAgo(15))
                .timesApplied(28).activatedAt(cat.daysAgo(45))
                .build());

        cat.save(Promotion.builder()
                .vendor(cat.vendor("serrekunda-home"))
                .name("Tobaski cookware sale")
                .description("Not yet started. Store-wide when it does.")
                .type(PromotionType.PERCENT).status(PromotionStatus.DRAFT)
                .percentOff(SeedCatalogue.money("15.00"))
                .minimumBasket(SeedCatalogue.money("1000.00"))
                .startsAt(cat.daysAhead(10)).endsAt(cat.daysAhead(24))
                .build());

        cat.save(Promotion.builder()
                .vendor(cat.vendor("kololi-style"))
                .name("Clearance — everything 40% off")
                .type(PromotionType.PERCENT).status(PromotionStatus.CANCELLED)
                .percentOff(SeedCatalogue.money("40.00"))
                .startsAt(cat.daysAgo(12)).endsAt(cat.daysAhead(18))
                .timesApplied(0)
                .build());

        // Store-wide: no products and no categories named, which is its own
        // meaning rather than a row somebody forgot to finish.
        cat.save(Promotion.builder()
                .vendor(cat.vendor("banjul-phones"))
                .name("Store-wide D 200 off over D 10 000")
                .type(PromotionType.FIXED).status(PromotionStatus.ACTIVE)
                .amountOff(SeedCatalogue.money("200.00")).currency("GMD")
                .minimumBasket(SeedCatalogue.money("10000.00"))
                .startsAt(cat.daysAgo(3)).endsAt(cat.daysAhead(27))
                .timesApplied(6).activatedAt(cat.daysAgo(3))
                .build());
    }

    private Set<Long> ids(Long... values) {
        return new LinkedHashSet<>(java.util.List.of(values));
    }
}
