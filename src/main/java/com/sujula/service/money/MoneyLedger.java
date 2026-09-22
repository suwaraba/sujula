package com.sujula.service.money;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.model.constant.LedgerEntryType;
import com.sujula.model.money.VendorLedgerEntry;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.Payout;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.money.VendorLedgerEntryRepository;
import com.sujula.service.reference.CurrencyCatalogue;

import lombok.extern.slf4j.Slf4j;

/**
 * The only thing that writes vendor money.
 *
 * <p>Same shape as the stock ledger, for the same reason: the movement is the
 * record and the total is the consequence. Nothing stores a balance, so nothing
 * can drift from one. A seller disputing what they are owed is shown the rows
 * that add up to it, and every row says which order, which refund or which
 * transfer put it there.
 *
 * <h2>What this refuses to do</h2>
 *
 * <p><strong>It never converts.</strong> Every entry is posted in the currency
 * the order was settled in, at the rate that order already carries. There is no
 * path through this class that reads a rate table. A vendor whose orders span
 * two currencies has two balances and this class will not produce a third
 * number by adding them — {@link #balances} returns one row per currency, and
 * the caller renders what is actually there (C2).
 *
 * <p><strong>It never edits.</strong> A refund is a new negative row, not a
 * smaller sale. A failed payout is a reversal, not a deleted transfer. A
 * corrected mistake is an adjustment naming the mistake.
 *
 * <p><strong>It rounds to the currency's own scale.</strong> Through
 * {@link CurrencyCatalogue}, which knows that XOF has no minor units — 1250.50
 * CFA is not an amount that exists, and a ledger that stored it would be a
 * ledger whose rows cannot be paid.
 */
@Slf4j
@Component
public class MoneyLedger {

    private final VendorLedgerEntryRepository entries;
    private final CurrencyCatalogue currencies;

    public MoneyLedger(VendorLedgerEntryRepository entries, CurrencyCatalogue currencies) {
        this.entries = entries;
        this.currencies = currencies;
    }

    // ── Posting a sale ───────────────────────────────────────────────────────

    /**
     * Records what a slice earned and what the platform took.
     *
     * <p>Two rows, not a net one. A seller looking at a 9,700 dalasi order wants
     * to see 9,700 in and 970 out, because that is what they can check against
     * the order; a single 8,730 row is a number they have to trust.
     *
     * <p>Both post <em>held</em> — {@code availableFrom} null — because the goods
     * have not arrived. {@link #releaseEscrow} is what makes them payable, and it
     * is driven by delivery being proven rather than by time passing.
     *
     * <p>Posting twice is a no-op. A retried request must not pay a seller twice,
     * and the guard is a query for an existing entry of that type on that slice
     * rather than a flag somebody has to remember to set.
     */
    @Transactional
    public List<VendorLedgerEntry> postSale(VendorOrder slice) {
        if (slice == null || slice.getVendor() == null) {
            throw new IllegalArgumentException("A sale must belong to a vendor");
        }
        if (entries.existsByVendorOrderIdAndType(slice.getId(), LedgerEntryType.SALE)) {
            log.debug("[Ledger] Slice {} is already posted; not posting again", slice.getId());
            return entries.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId());
        }

        String currency = currencyOf(slice);
        BigDecimal gross = round(slice.getTotalNative(), currency);
        BigDecimal commission = round(slice.getCommissionNative(), currency);
        LocalDateTime when = slice.getCreatedAt() != null ? slice.getCreatedAt() : LocalDateTime.now();
        String reference = orderNumber(slice);

        List<VendorLedgerEntry> posted = new ArrayList<>();
        posted.add(save(entry(slice, LedgerEntryType.SALE, gross, currency, when,
                "Sale on order " + reference, reference)));

        if (commission.signum() != 0) {
            // Negative: it leaves the seller's side. Stored signed so a balance
            // is a sum and nothing has to know which way a type points.
            posted.add(save(entry(slice, LedgerEntryType.COMMISSION, commission.negate(), currency, when,
                    "Platform commission on order " + reference, reference)));
        }

        log.info("[Ledger] Slice {} posted: {} {} gross, {} commission, held in escrow",
                slice.getId(), gross, currency, commission);
        return posted;
    }

    /**
     * Makes a slice's money payable.
     *
     * <p>Called when delivery is proven or the buyer confirms receipt — never on
     * a timer and never by the vendor. Stamps every held entry on the slice,
     * which is what turns "earned" into "available" without moving a penny.
     *
     * @return how many entries were released
     */
    @Transactional
    public int releaseEscrow(VendorOrder slice, LocalDateTime when) {
        if (slice.getDisputeFrozenAt() != null) {
            // The guard that makes a dispute freeze structural rather than
            // remembered. Delivery is the usual reason escrow releases, and a
            // parcel arriving is exactly what most disputes are about — so
            // without this, the event that triggers the argument would also pay
            // the seller in the middle of it. Refusing here catches every path,
            // including ones written later that know nothing about disputes.
            log.info("[Ledger] Slice {} not released: a dispute is holding it since {}",
                    slice.getId(), slice.getDisputeFrozenAt());
            return 0;
        }
        LocalDateTime at = when != null ? when : LocalDateTime.now();
        List<VendorLedgerEntry> held = entries.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId())
                .stream().filter(VendorLedgerEntry::isHeld).toList();
        for (VendorLedgerEntry held0 : held) {
            held0.setAvailableFrom(at);
        }
        entries.saveAll(held);
        if (!held.isEmpty()) {
            log.info("[Ledger] Slice {} released from escrow: {} entries now payable",
                    slice.getId(), held.size());
        }
        return held.size();
    }

    // ── Refunds ──────────────────────────────────────────────────────────────

    /**
     * Takes back what was refunded, and hands back the commission on it.
     *
     * <p>Posted when an administrator <em>approves</em> a refund, never when one
     * is requested. A request is a question and this is the answer; posting on
     * the question would move a seller's balance for something that might be
     * refused.
     *
     * <p>The commission reversal is its own row rather than netted in, because a
     * seller checking a refund is specifically looking to see they were not
     * charged commission on a sale that did not happen. Netting hides the thing
     * they came to look at.
     */
    @Transactional
    public List<VendorLedgerEntry> postRefund(VendorOrder slice, BigDecimal amountNative,
                                              BigDecimal commissionReversal, String reference,
                                              String reason, LocalDateTime when) {
        String currency = currencyOf(slice);
        LocalDateTime at = when != null ? when : LocalDateTime.now();
        BigDecimal refunded = round(amountNative, currency).abs();

        // A refund follows the money it takes back. If the sale is still held,
        // the two cancel inside escrow and neither is payable; if it has been
        // released, the refund is live too. Reading the slice's own
        // escrowReleasedAt would get this wrong, because escrow is released by
        // stamping the entries and a slice released before this column existed
        // still reads null.
        LocalDateTime availability = availabilityOfSale(slice, at);

        List<VendorLedgerEntry> posted = new ArrayList<>();
        if (refunded.signum() != 0) {
            posted.add(save(dated(slice, LedgerEntryType.REFUND, refunded.negate(), currency, at,
                    availability,
                    reason == null || reason.isBlank()
                            ? "Refund on order " + orderNumber(slice)
                            : "Refund on order " + orderNumber(slice) + " — " + reason,
                    reference)));
        }
        BigDecimal givenBack = round(commissionReversal, currency).abs();
        if (givenBack.signum() != 0) {
            posted.add(save(dated(slice, LedgerEntryType.COMMISSION_REVERSAL, givenBack, currency, at,
                    availability,
                    "Commission returned on the refunded part of " + orderNumber(slice), reference)));
        }

        // A refund can legitimately take a balance below zero - the seller was
        // paid out before the buyer asked. That is a debt, not a bug, and
        // refusing to record it would only hide it.
        log.info("[Ledger] Refund posted on slice {}: -{} {}, commission +{} returned",
                slice.getId(), refunded, currency, givenBack);
        return posted;
    }

    // ── Disputes ─────────────────────────────────────────────────────────────

    /**
     * Holds a slice's money still while a dispute is decided.
     *
     * <p>Two different situations, and only one of them needs an entry. If the
     * sale is still in escrow, nothing is payable and the freeze is already
     * complete — {@link #releaseEscrow} will refuse to lift it. If it has been
     * released, the money is sitting in the seller's available balance and has
     * to be taken back out, which is what this row does.
     *
     * <p>Writing the row in both cases would take the balance down twice for one
     * sale: once by never adding it and once by subtracting it.
     *
     * @return the hold entry, or null when the sale was still held and there was
     *         nothing available to hold
     */
    @Transactional
    public VendorLedgerEntry holdForDispute(VendorOrder slice, String reference, String reason) {
        LocalDateTime availability = availabilityOfSale(slice, null);
        if (availability == null) {
            log.info("[Ledger] Slice {} disputed while still in escrow — no hold entry needed",
                    slice.getId());
            return null;
        }
        if (!findHolds(slice).isEmpty()) {
            // A second dispute on the same slice must not hold the money twice.
            log.info("[Ledger] Slice {} is already held for a dispute", slice.getId());
            return null;
        }

        String currency = currencyOf(slice);
        BigDecimal held = round(payableOf(slice), currency).abs();
        if (held.signum() == 0) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        VendorLedgerEntry entry = save(dated(slice, LedgerEntryType.DISPUTE_HOLD,
                held.negate(), currency, now, now,
                reason == null || reason.isBlank()
                        ? "Held while a dispute on " + orderNumber(slice) + " is decided"
                        : "Held while a dispute on " + orderNumber(slice) + " is decided — " + reason,
                reference));
        log.info("[Ledger] Slice {} held for dispute {}: -{} {}",
                slice.getId(), reference, held, currency);
        return entry;
    }

    /**
     * Lifts a dispute hold, whatever the dispute decided.
     *
     * <p>Always the full hold, always as its own row. Where the buyer won, a
     * separate refund takes the money back off — netting the two would leave a
     * seller unable to tell "the hold came off and then you were refunded" from
     * "you were never held", and those are different stories about their month.
     *
     * @return the release entry, or null where nothing was held
     */
    @Transactional
    public VendorLedgerEntry releaseDisputeHold(VendorOrder slice, String reference, String why) {
        List<VendorLedgerEntry> holds = findHolds(slice);
        if (holds.isEmpty()) {
            return null;
        }
        String currency = currencyOf(slice);
        BigDecimal total = BigDecimal.ZERO;
        for (VendorLedgerEntry hold : holds) {
            total = total.add(hold.getAmount());
        }
        BigDecimal giveBack = round(total, currency).abs();
        if (giveBack.signum() == 0) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        VendorLedgerEntry entry = save(dated(slice, LedgerEntryType.DISPUTE_HOLD_RELEASE,
                giveBack, currency, now, now,
                why == null || why.isBlank()
                        ? "Dispute on " + orderNumber(slice) + " closed — hold lifted"
                        : "Dispute on " + orderNumber(slice) + " closed — " + why,
                reference));
        log.info("[Ledger] Slice {} hold lifted on {}: +{} {}",
                slice.getId(), reference, giveBack, currency);
        return entry;
    }

    /** Holds on a slice that have not been lifted yet. */
    private List<VendorLedgerEntry> findHolds(VendorOrder slice) {
        List<VendorLedgerEntry> all = entries.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId());
        BigDecimal released = BigDecimal.ZERO;
        List<VendorLedgerEntry> holds = new ArrayList<>();
        for (VendorLedgerEntry existing : all) {
            if (existing.getType() == LedgerEntryType.DISPUTE_HOLD) {
                holds.add(existing);
            } else if (existing.getType() == LedgerEntryType.DISPUTE_HOLD_RELEASE) {
                released = released.add(existing.getAmount());
            }
        }
        if (released.signum() == 0) {
            return holds;
        }
        // Everything held has already been given back, so there is nothing
        // outstanding. Compared rather than counted, because a slice can be
        // disputed, released and disputed again.
        BigDecimal outstanding = BigDecimal.ZERO;
        for (VendorLedgerEntry hold : holds) {
            outstanding = outstanding.add(hold.getAmount());
        }
        return outstanding.abs().compareTo(released) <= 0 ? List.of() : holds;
    }

    /**
     * What this slice has actually earned the seller, net of everything posted.
     *
     * <p>Summed from the rows rather than read off the vendor order's payout
     * column, because by the time a dispute is raised there may have been a
     * partial refund — and holding the original sale would hold money the seller
     * no longer has.
     */
    private BigDecimal payableOf(VendorOrder slice) {
        BigDecimal total = BigDecimal.ZERO;
        for (VendorLedgerEntry existing : entries.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId())) {
            if (existing.getType() != LedgerEntryType.PAYOUT
                    && existing.getType() != LedgerEntryType.PAYOUT_REVERSAL) {
                total = total.add(existing.getAmount());
            }
        }
        return total;
    }

    // ── Payouts ──────────────────────────────────────────────────────────────

    /**
     * Commits money to a transfer.
     *
     * <p>The entry is written when the payout is <em>requested</em>, not when it
     * settles. That is deliberate: it takes the money out of the available
     * figure immediately, so a second request cannot claim the same balance
     * while the first is still being decided.
     */
    @Transactional
    public VendorLedgerEntry postPayout(Vendor vendor, Payout payout) {
        String currency = payout.getCurrency();
        BigDecimal amount = round(payout.getAmount(), currency).abs();

        VendorLedgerEntry entry = VendorLedgerEntry.builder()
                .vendor(vendor)
                .type(LedgerEntryType.PAYOUT)
                .amount(amount.negate())
                .currency(currency)
                .payout(payout)
                // A payout is not held: the money was already available, which
                // is the only reason it could be requested.
                .availableFrom(LocalDateTime.now())
                .occurredAt(LocalDateTime.now())
                .description("Payout requested — " + payout.getReference())
                .reference(payout.getReference())
                .build();

        log.info("[Ledger] Payout {} committed: {} {} leaves vendor {}'s balance",
                payout.getReference(), amount, currency, vendor.getId());
        return save(entry);
    }

    /**
     * Gives the money back when a transfer fails or is withdrawn.
     *
     * <p>A reversal rather than a deletion. The attempt happened, and a seller
     * looking at a statement with money that left and came back deserves to see
     * both — otherwise the gap in the middle is unexplainable.
     */
    @Transactional
    public VendorLedgerEntry reversePayout(Payout payout, String why) {
        BigDecimal amount = round(payout.getAmount(), payout.getCurrency()).abs();

        VendorLedgerEntry entry = VendorLedgerEntry.builder()
                .vendor(payout.getVendor())
                .type(LedgerEntryType.PAYOUT_REVERSAL)
                .amount(amount)
                .currency(payout.getCurrency())
                .payout(payout)
                .availableFrom(LocalDateTime.now())
                .occurredAt(LocalDateTime.now())
                .description("Payout " + payout.getReference() + " came back — "
                        + (why == null || why.isBlank() ? "no reason given" : why))
                .reference(payout.getReference())
                .build();

        log.info("[Ledger] Payout {} reversed: {} {} back to vendor {}",
                payout.getReference(), amount, payout.getCurrency(), payout.getVendor().getId());
        return save(entry);
    }

    /** A correction somebody made by hand, which is the one row a person may write. */
    @Transactional
    public VendorLedgerEntry postAdjustment(Vendor vendor, BigDecimal amount, String currency,
                                            String reason, User by) {
        if (reason == null || reason.isBlank()) {
            // An adjustment with no reason is an unexplained change to somebody's
            // money, which is the one thing a ledger exists to make impossible.
            throw new IllegalArgumentException("An adjustment must say why");
        }
        return save(VendorLedgerEntry.builder()
                .vendor(vendor)
                .type(LedgerEntryType.ADJUSTMENT)
                .amount(round(amount, currency))
                .currency(currency)
                .availableFrom(LocalDateTime.now())
                .occurredAt(LocalDateTime.now())
                .description(reason)
                .createdBy(by)
                .build());
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * What a vendor is owed, one row per currency.
     *
     * <p>Never one row. A vendor who has only ever traded in dalasi gets a
     * single-entry map, which looks the same to a caller as a total would — and
     * a vendor who has traded in two gets two, which is the case a single total
     * would have silently got wrong.
     */
    @Transactional(readOnly = true)
    public Map<String, Balance> balances(Long vendorId) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, BigDecimal> available = sums(entries.sumAvailableByCurrency(vendorId, now));
        Map<String, BigDecimal> held = sums(entries.sumHeldByCurrency(vendorId));
        Map<String, BigDecimal> inFlight = sums(entries.sumInFlightPayoutsByCurrency(vendorId));
        Map<String, BigDecimal> atRisk = sums(entries.sumAtRiskByCurrency(vendorId));

        Map<String, Balance> result = new LinkedHashMap<>();
        List<String> all = new ArrayList<>(entries.currenciesFor(vendorId));
        java.util.Collections.sort(all);
        for (String currency : all) {
            result.put(currency, new Balance(
                    currency,
                    round(available.getOrDefault(currency, BigDecimal.ZERO), currency),
                    round(held.getOrDefault(currency, BigDecimal.ZERO), currency),
                    round(inFlight.getOrDefault(currency, BigDecimal.ZERO), currency),
                    round(atRisk.getOrDefault(currency, BigDecimal.ZERO), currency)));
        }
        return result;
    }

    /** The balance in one currency, or a zeroed one where there is no history. */
    @Transactional(readOnly = true)
    public Balance balance(Long vendorId, String currency) {
        Balance found = balances(vendorId).get(currency);
        return found != null ? found
                : new Balance(currency, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * One currency's worth of what a vendor is owed.
     *
     * @param available money that is posted, out of escrow and not committed to
     *                  an open transfer — what could actually be paid today
     * @param pending   earned but still held against parcels that have not
     *                  arrived
     * @param inFlight  already committed to a transfer that has not settled;
     *                  informational, because the payout entry has taken it out
     *                  of {@code available} already
     * @param atRisk    sales with a refund still being decided. Not withheld
     *                  from {@code available} — nothing has been decided — but
     *                  shown, so an approval is not a surprise
     */
    public record Balance(String currency, BigDecimal available, BigDecimal pending,
                          BigDecimal inFlight, BigDecimal atRisk) {

        /** Everything the vendor will have once the held money lands. */
        public BigDecimal total() {
            return available.add(pending);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static Map<String, BigDecimal> sums(List<Object[]> rows) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put((String) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
        }
        return out;
    }

    /** A sale entry: held until the parcel is proven delivered. */
    private VendorLedgerEntry entry(VendorOrder slice, LedgerEntryType type, BigDecimal amount,
                                    String currency, LocalDateTime when, String description,
                                    String reference) {
        return dated(slice, type, amount, currency, when, slice.getEscrowReleasedAt(),
                description, reference);
    }

    /** The same, with availability stated rather than taken from the slice. */
    private VendorLedgerEntry dated(VendorOrder slice, LedgerEntryType type, BigDecimal amount,
                                    String currency, LocalDateTime when, LocalDateTime availableFrom,
                                    String description, String reference) {
        return VendorLedgerEntry.builder()
                .vendor(slice.getVendor())
                .vendorOrder(slice)
                .type(type)
                .amount(amount)
                .currency(currency)
                .availableFrom(availableFrom)
                .occurredAt(when)
                .description(description)
                .reference(reference)
                // Carried from the slice so the row can still explain itself
                // after the rate table has moved on.
                .fx(slice.getFx())
                .build();
    }

    /**
     * When this slice's sale became payable, or null while it is still held.
     *
     * <p>Read off the posted SALE row, which is where escrow is actually
     * recorded. A refund has to land on the same side of escrow as the sale it
     * reverses, or the two stop cancelling and a fully refunded order leaves a
     * balance behind.
     */
    private LocalDateTime availabilityOfSale(VendorOrder slice, LocalDateTime fallback) {
        // Deliberately not Optional.map: a held sale carries a null
        // availableFrom, and mapping would collapse that to "absent" and hand
        // back the fallback - making a refund payable against a sale that is
        // not. The two cases are different and are kept different.
        for (VendorLedgerEntry existing : entries.findByVendorOrderIdOrderByOccurredAtAsc(slice.getId())) {
            if (existing.getType() == LedgerEntryType.SALE) {
                return existing.getAvailableFrom();
            }
        }
        return fallback;
    }

    private VendorLedgerEntry save(VendorLedgerEntry entry) {
        return entries.save(entry);
    }

    /**
     * The slice's own frozen currency, never the vendor's current settlement
     * currency.
     *
     * <p>They differ exactly when a shop has changed what it settles in, which is
     * the case where reading the vendor row would restate old orders in a
     * currency they were never sold in.
     */
    private static String currencyOf(VendorOrder slice) {
        if (slice.getNativeCurrency() != null && !slice.getNativeCurrency().isBlank()) {
            return slice.getNativeCurrency();
        }
        return slice.getVendor().getSettlementCurrency();
    }

    private static String orderNumber(VendorOrder slice) {
        return slice.getOrder() == null ? String.valueOf(slice.getId()) : slice.getOrder().getOrderNumber();
    }

    /** Through the catalogue, which knows XOF has no minor units. */
    private BigDecimal round(BigDecimal amount, String currency) {
        return amount == null ? BigDecimal.ZERO : currencies.round(amount, currency);
    }
}
