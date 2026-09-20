package com.sujula.config.seed;

import org.springframework.stereotype.Component;

import com.sujula.model.admin.ModerationCase;
import com.sujula.model.admin.Sanction;
import com.sujula.model.aftersales.Dispute;
import com.sujula.model.aftersales.DisputeEvidence;
import com.sujula.model.aftersales.DisputeMessage;
import com.sujula.model.aftersales.MessageThread;
import com.sujula.model.aftersales.ReturnLine;
import com.sujula.model.aftersales.ReturnRequest;
import com.sujula.model.aftersales.ReviewReport;
import com.sujula.model.aftersales.ThreadMessage;
import com.sujula.model.constant.DisputeOutcome;
import com.sujula.model.constant.DisputeReason;
import com.sujula.model.constant.DisputeStatus;
import com.sujula.model.constant.ModerationCaseStatus;
import com.sujula.model.constant.ModerationReason;
import com.sujula.model.constant.ReturnReason;
import com.sujula.model.constant.ReturnStatus;
import com.sujula.model.constant.ReviewReportReason;
import com.sujula.model.constant.SanctionType;
import com.sujula.model.constant.ThreadSubject;
import com.sujula.model.money.FxSnapshot;

/**
 * What happens when an order goes wrong.
 *
 * <p>Returns, disputes, the messages either side sent, the moderation cases
 * raised off them and the sanctions that came out. Every status each of those
 * can hold is represented, because these are the paths where the money is
 * already taken and the goods already moved, and a branch nobody has data for
 * is a branch nobody has tried.
 *
 * <p>Two things here follow directly from the marketplace's rules. Every return
 * and every dispute hangs off a <b>vendor order</b>, not an order, so one
 * seller's problem never touches another's line in the same basket (C3). And
 * every amount on them carries both the buyer's figure and the seller's, with
 * the rate that joined them frozen at the moment of the order rather than
 * today's (C2) — a dispute settled at today's rate is a dispute whose arithmetic
 * nobody can reproduce.
 */
@Component
class AftersalesStage implements SeedStage {

    @Override
    public String name() {
        return "Returns, disputes, messaging and moderation";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        returns(cat);
        cat.flush();
        disputes(cat);
        cat.flush();
        disputeMessages(cat);
        disputeEvidence(cat);
        threads(cat);
        reviewReports(cat);
        moderation(cat);
    }

    // ── Returns ──────────────────────────────────────────────────────────────

    /**
     * Return requests, one for every status the flow defines.
     *
     * <p>The offer pair is the interesting one: a seller may offer less than was
     * asked for, and the request only settles when the buyer accepts it. Both
     * the asked-for amount and the offered amount are stored, in both
     * currencies, because "what was agreed" and "what was claimed" are different
     * questions and a refund built from the wrong one is a refund somebody
     * disputes.
     */
    private void returns(SeedCatalogue cat) {
        ReturnRequest requested = cat.save(ReturnRequest.builder()
                .reference("RET-2024-000001")
                .vendorOrder(cat.vendorOrder("1002-bp")).requestedBy(cat.user("isatou"))
                .status(ReturnStatus.REQUESTED).reason(ReturnReason.NOT_AS_DESCRIBED)
                .description("The blue is nothing like the photographs. My sister does not want it.")
                .photoUrls("https://cdn.sujula.gm/sample/returns/1002-colour-1.jpg,"
                        + "https://cdn.sujula.gm/sample/returns/1002-colour-2.jpg")
                .amount(SeedCatalogue.money("113.99")).currency("EUR")
                .amountNative(SeedCatalogue.money("8500.00"))
                .fx(FxSnapshot.published("GMD", "EUR", SeedCatalogue.rate("0.01341000"),
                        cat.daysAgo(4)))
                .sellerPaysCarriage(true)
                .build());
        cat.returns.put("requested", requested);
        cat.save(ReturnLine.builder()
                .returnRequest(requested).orderItem(cat.orderItem("1002-spark10"))
                .quantity(1)
                .unitPriceNative(SeedCatalogue.money("8500.00"))
                .unitPrice(SeedCatalogue.money("113.99"))
                .productName("Tecno Spark 10")
                .assignedImeis("350123456789012")
                .build());

        ReturnRequest approved = cat.save(ReturnRequest.builder()
                .reference("RET-2024-000002")
                .vendorOrder(cat.vendorOrder("1003-bp")).requestedBy(cat.user("modou"))
                .status(ReturnStatus.APPROVED).reason(ReturnReason.MISSING_PARTS)
                .description("No charger in the box.")
                .amount(SeedCatalogue.money("11.39")).currency("GBP")
                .amountNative(SeedCatalogue.money("1000.00"))
                .fx(FxSnapshot.published("GMD", "GBP", SeedCatalogue.rate("0.01139000"),
                        cat.daysAgo(5)))
                .decidedBy(cat.user("fatou")).decidedAt(cat.hoursAgo(22))
                .decisionNote("Agreed. Keep the handset; we will refund the charger.")
                .sellerPaysCarriage(true)
                .build());
        cat.returns.put("approved", approved);
        cat.save(ReturnLine.builder()
                .returnRequest(approved).orderItem(cat.orderItem("1003-hot30"))
                .quantity(1)
                .unitPriceNative(SeedCatalogue.money("9750.00"))
                .unitPrice(SeedCatalogue.money("111.05"))
                .productName("Infinix Hot 30")
                .assignedImeis("350987654321098")
                .build());

        cat.returns.put("rejected", cat.save(ReturnRequest.builder()
                .reference("RET-2024-000003")
                .vendorOrder(cat.vendorOrder("1001-bp")).requestedBy(cat.user("isatou"))
                .status(ReturnStatus.REJECTED).reason(ReturnReason.NO_LONGER_WANTED)
                .description("She changed her mind after three weeks.")
                .amount(SeedCatalogue.money("113.99")).currency("EUR")
                .amountNative(SeedCatalogue.money("8500.00"))
                .fx(FxSnapshot.published("GMD", "EUR", SeedCatalogue.rate("0.01341000"),
                        cat.daysAgo(12)))
                .decidedBy(cat.user("fatou")).decidedAt(cat.daysAgo(3))
                .decisionNote("Outside the fourteen-day window and the seal is broken.")
                .sellerPaysCarriage(false)
                .build()));

        // The seller offered less than was claimed and the buyer has not
        // answered. Both figures on the row, in both currencies.
        ReturnRequest offered = cat.save(ReturnRequest.builder()
                .reference("RET-2024-000004")
                .vendorOrder(cat.vendorOrder("1006-dt")).requestedBy(cat.user("cheikh"))
                .status(ReturnStatus.OFFER_MADE).reason(ReturnReason.FAULTY)
                .description("Le power bank ne tient pas la charge.")
                .amount(SeedCatalogue.wholeUnits("12500")).currency("XOF")
                .amountNative(SeedCatalogue.wholeUnits("12500"))
                .fx(FxSnapshot.identity("XOF", cat.daysAgo(18)))
                .offeredAmount(SeedCatalogue.wholeUnits("9000"))
                .offeredAmountNative(SeedCatalogue.wholeUnits("9000"))
                .offerNote("Remboursement partiel sans retour du produit.")
                .offeredAt(cat.daysAgo(13))
                .sellerPaysCarriage(false)
                .build());
        cat.returns.put("offered", offered);
        cat.save(ReturnLine.builder()
                .returnRequest(offered).orderItem(cat.orderItem("1006-powerbank"))
                .quantity(1)
                .unitPriceNative(SeedCatalogue.wholeUnits("12500"))
                .unitPrice(SeedCatalogue.wholeUnits("12500"))
                .productName("Anker PowerCore 20000")
                .build());

        cat.returns.put("accepted", cat.save(ReturnRequest.builder()
                .reference("RET-2024-000005")
                .vendorOrder(cat.vendorOrder("1006-dt")).requestedBy(cat.user("cheikh"))
                .status(ReturnStatus.OFFER_ACCEPTED).reason(ReturnReason.DAMAGED_IN_TRANSIT)
                .description("Coin de l'écran fêlé à la livraison.")
                .amount(SeedCatalogue.wholeUnits("20000")).currency("XOF")
                .amountNative(SeedCatalogue.wholeUnits("20000"))
                .fx(FxSnapshot.identity("XOF", cat.daysAgo(18)))
                .offeredAmount(SeedCatalogue.wholeUnits("15000"))
                .offeredAmountNative(SeedCatalogue.wholeUnits("15000"))
                .offerNote("15 000 CFA, l'appareil reste chez vous.")
                .offeredAt(cat.daysAgo(9)).offerAcceptedAt(cat.daysAgo(8))
                .sellerPaysCarriage(false)
                .build()));

        ReturnRequest received = cat.save(ReturnRequest.builder()
                .reference("RET-2024-000006")
                .vendorOrder(cat.vendorOrder("1004-sh")).requestedBy(cat.user("binta"))
                .status(ReturnStatus.RECEIVED).reason(ReturnReason.WRONG_ITEM)
                .description("A six litre pot arrived, not the eight.")
                .amount(SeedCatalogue.money("1450.00")).currency("GMD")
                .amountNative(SeedCatalogue.money("1450.00"))
                .fx(FxSnapshot.identity("GMD", cat.daysAgo(2)))
                .decidedBy(cat.user("awa")).decidedAt(cat.hoursAgo(30))
                .decisionNote("Our mistake. Send it back with the driver.")
                .receivedAt(cat.hoursAgo(8)).receivedBy(cat.user("awa"))
                .receivedNote("Back in the shop, unused, with the lid.")
                .sellerPaysCarriage(true)
                .build());
        cat.returns.put("received", received);
        cat.save(ReturnLine.builder()
                .returnRequest(received).orderItem(cat.orderItem("1004-pot"))
                .quantity(1)
                .unitPriceNative(SeedCatalogue.money("1450.00"))
                .unitPrice(SeedCatalogue.money("1450.00"))
                .productName("Cast iron cooking pot, 8 litre")
                .build());

        // Two lines on one request: a return can be partial, and a request that
        // can only ever hold one line cannot express "send back the charger but
        // keep the phone".
        ReturnRequest multiLine = cat.save(ReturnRequest.builder()
                .reference("RET-2024-000010")
                .vendorOrder(cat.vendorOrder("1006-dt")).requestedBy(cat.user("cheikh"))
                .status(ReturnStatus.APPROVED).reason(ReturnReason.WRONG_ITEM)
                .description("Les deux articles sont à renvoyer : mauvaise couleur et chargeur absent.")
                .amount(SeedCatalogue.wholeUnits("107500")).currency("XOF")
                .amountNative(SeedCatalogue.wholeUnits("107500"))
                .fx(FxSnapshot.identity("XOF", cat.daysAgo(18)))
                .decidedBy(cat.user("omar")).decidedAt(cat.daysAgo(14))
                .decisionNote("Accepté. Retour par le même livreur.")
                .sellerPaysCarriage(true)
                .build());
        cat.returns.put("multi-line", multiLine);
        cat.save(ReturnLine.builder()
                .returnRequest(multiLine).orderItem(cat.orderItem("1006-a15"))
                .quantity(1)
                .unitPriceNative(SeedCatalogue.wholeUnits("95000"))
                .unitPrice(SeedCatalogue.wholeUnits("95000"))
                .productName("Samsung Galaxy A15")
                .assignedImeis("352233445566012")
                .build());
        cat.save(ReturnLine.builder()
                .returnRequest(multiLine).orderItem(cat.orderItem("1006-powerbank"))
                .quantity(1)
                .unitPriceNative(SeedCatalogue.wholeUnits("12500"))
                .unitPrice(SeedCatalogue.wholeUnits("12500"))
                .productName("Anker PowerCore 20000")
                .build());

        cat.returns.put("refunded", cat.save(ReturnRequest.builder()
                .reference("RET-2024-000007")
                .vendorOrder(cat.vendorOrder("1006-dt")).requestedBy(cat.user("cheikh"))
                .status(ReturnStatus.REFUNDED).reason(ReturnReason.FAULTY)
                .description("Batterie externe défectueuse.")
                .amount(SeedCatalogue.wholeUnits("12500")).currency("XOF")
                .amountNative(SeedCatalogue.wholeUnits("12500"))
                .fx(FxSnapshot.identity("XOF", cat.daysAgo(18)))
                .decidedBy(cat.user("support")).decidedAt(cat.daysAgo(11))
                .receivedAt(cat.daysAgo(11)).receivedBy(cat.user("omar"))
                .refundReference("RFD-2024-000001")
                .sellerPaysCarriage(true)
                .build()));

        // Escalated into a dispute. The dispute is written below and linked back
        // to this row once both exist.
        cat.returns.put("escalated", cat.save(ReturnRequest.builder()
                .reference("RET-2024-000008")
                .vendorOrder(cat.vendorOrder("1007-ks")).requestedBy(cat.user("sally"))
                .status(ReturnStatus.ESCALATED).reason(ReturnReason.NOT_AS_DESCRIBED)
                .description("Annoncé comme wax hollandais; c'est une impression locale.")
                .photoUrls("https://cdn.sujula.gm/sample/returns/1007-fabric.jpg")
                .amount(SeedCatalogue.money("24.81")).currency("EUR")
                .amountNative(SeedCatalogue.money("1850.00"))
                .fx(FxSnapshot.published("GMD", "EUR", SeedCatalogue.rate("0.01341000"),
                        cat.daysAgo(25)))
                .escalatedAt(cat.daysAgo(20))
                .sellerPaysCarriage(true)
                .build()));

        cat.returns.put("withdrawn", cat.save(ReturnRequest.builder()
                .reference("RET-2024-000009")
                .vendorOrder(cat.vendorOrder("1001-bp")).requestedBy(cat.user("isatou"))
                .status(ReturnStatus.WITHDRAWN).reason(ReturnReason.NEVER_ARRIVED)
                .description("Raised before I saw the delivery photograph.")
                .amount(SeedCatalogue.money("113.99")).currency("EUR")
                .amountNative(SeedCatalogue.money("8500.00"))
                .fx(FxSnapshot.published("GMD", "EUR", SeedCatalogue.rate("0.01341000"),
                        cat.daysAgo(12)))
                .withdrawnAt(cat.daysAgo(9))
                .sellerPaysCarriage(true)
                .build()));
    }

    // ── Disputes ─────────────────────────────────────────────────────────────

    /**
     * Disputes, and the money each one freezes.
     *
     * <p>An open dispute holds the seller's payout rather than clawing it back
     * afterwards, which is why {@code frozenAt} and {@code holdReference} are on
     * the row. The resolved ones record which way they went and how much was
     * awarded — in both currencies, at the order's own rate.
     */
    private void disputes(SeedCatalogue cat) {
        cat.disputes.put("resolved-buyer", cat.save(Dispute.builder()
                .reference("DSP-000001")
                .vendorOrder(cat.vendorOrder("1007-ks")).raisedBy(cat.user("sally"))
                .status(DisputeStatus.RESOLVED).reason(DisputeReason.NOT_AS_DESCRIBED)
                .description("Le tissu n'est pas celui annoncé et le vendeur refuse le retour.")
                .returnRequest(cat.returnRequest("escalated"))
                .amount(SeedCatalogue.money("26.82")).currency("EUR")
                .amountNative(SeedCatalogue.money("2000.00"))
                .fx(FxSnapshot.published("GMD", "EUR", SeedCatalogue.rate("0.01341000"),
                        cat.daysAgo(25)))
                .frozenAt(cat.daysAgo(20)).unfrozenAt(cat.daysAgo(15))
                .holdReference("HOLD-DSP-000001")
                .outcome(DisputeOutcome.FOR_BUYER)
                .awardedToBuyer(SeedCatalogue.money("26.82"))
                .awardedToBuyerNative(SeedCatalogue.money("2000.00"))
                .resolvedBy(cat.user("support")).resolvedAt(cat.daysAgo(15))
                .resolutionNote("Les photographies du vendeur confirment une impression locale.")
                .assignedToUserId(cat.user("support").getId()).assignedAt(cat.daysAgo(19))
                .refundReference("RFD-2024-000002")
                .build()));

        cat.disputes.put("under-review", cat.save(Dispute.builder()
                .reference("DSP-000002")
                .vendorOrder(cat.vendorOrder("1006-dt")).raisedBy(cat.user("cheikh"))
                .status(DisputeStatus.UNDER_REVIEW).reason(DisputeReason.DAMAGED)
                .description("Écran fêlé à l'ouverture du colis.")
                .amount(SeedCatalogue.wholeUnits("20000")).currency("XOF")
                .amountNative(SeedCatalogue.wholeUnits("20000"))
                .fx(FxSnapshot.identity("XOF", cat.daysAgo(18)))
                .frozenAt(cat.daysAgo(7)).holdReference("HOLD-DSP-000002")
                .assignedToUserId(cat.user("support").getId()).assignedAt(cat.daysAgo(6))
                .dueBy(cat.daysAhead(1))
                .callbackRequestedAt(cat.daysAgo(2))
                .build()));

        // Open, unassigned and already past its deadline. The row a queue screen
        // exists to surface.
        cat.disputes.put("open-overdue", cat.save(Dispute.builder()
                .reference("DSP-000003")
                .vendorOrder(cat.vendorOrder("1004-sh")).raisedBy(cat.user("binta"))
                .status(DisputeStatus.OPEN).reason(DisputeReason.NOT_RECEIVED)
                .description("The driver says he came; nobody was called and nothing was left.")
                .amount(SeedCatalogue.money("1600.00")).currency("GMD")
                .amountNative(SeedCatalogue.money("1600.00"))
                .fx(FxSnapshot.identity("GMD", cat.daysAgo(2)))
                .frozenAt(cat.hoursAgo(18)).holdReference("HOLD-DSP-000003")
                .dueBy(cat.hoursAgo(2))
                .build()));

        cat.disputes.put("resolved-vendor", cat.save(Dispute.builder()
                .reference("DSP-000004")
                .vendorOrder(cat.vendorOrder("1001-bp")).raisedBy(cat.user("isatou"))
                .status(DisputeStatus.RESOLVED).reason(DisputeReason.NOT_AS_DESCRIBED)
                .description("Wrong colour sent.")
                .amount(SeedCatalogue.money("113.99")).currency("EUR")
                .amountNative(SeedCatalogue.money("8500.00"))
                .fx(FxSnapshot.published("GMD", "EUR", SeedCatalogue.rate("0.01341000"),
                        cat.daysAgo(12)))
                .frozenAt(cat.daysAgo(8)).unfrozenAt(cat.daysAgo(7))
                .holdReference("HOLD-DSP-000004")
                .outcome(DisputeOutcome.FOR_VENDOR)
                .awardedToBuyer(SeedCatalogue.money("0.00"))
                .awardedToBuyerNative(SeedCatalogue.money("0.00"))
                .resolvedBy(cat.user("support")).resolvedAt(cat.daysAgo(7))
                .resolutionNote("The delivery photograph shows the colour that was ordered.")
                .assignedToUserId(cat.user("support").getId()).assignedAt(cat.daysAgo(8))
                .build()));

        // Split: the seller keeps the goods' value and the platform refunds the
        // carriage. A partial award, in both currencies.
        cat.disputes.put("split", cat.save(Dispute.builder()
                .reference("DSP-000005")
                .vendorOrder(cat.vendorOrder("1003-bp")).raisedBy(cat.user("modou"))
                .status(DisputeStatus.RESOLVED).reason(DisputeReason.RETURN_REFUSED)
                .description("Charger missing and the seller stopped answering.")
                .amount(SeedCatalogue.money("101.65")).currency("GBP")
                .amountNative(SeedCatalogue.money("8925.00"))
                .fx(FxSnapshot.published("GMD", "GBP", SeedCatalogue.rate("0.01139000"),
                        cat.daysAgo(5)))
                .frozenAt(cat.daysAgo(3)).unfrozenAt(cat.hoursAgo(20))
                .holdReference("HOLD-DSP-000005")
                .outcome(DisputeOutcome.SPLIT)
                .awardedToBuyer(SeedCatalogue.money("11.39"))
                .awardedToBuyerNative(SeedCatalogue.money("1000.00"))
                .resolvedBy(cat.user("support")).resolvedAt(cat.hoursAgo(20))
                .resolutionNote("Charger refunded; the handset stays with the buyer.")
                .assignedToUserId(cat.user("support").getId()).assignedAt(cat.daysAgo(3))
                .refundReference("RFD-2024-000005")
                .build()));

        cat.disputes.put("withdrawn", cat.save(Dispute.builder()
                .reference("DSP-000006")
                .vendorOrder(cat.vendorOrder("1002-bp")).raisedBy(cat.user("isatou"))
                .status(DisputeStatus.WITHDRAWN).reason(DisputeReason.NOT_RECEIVED)
                .description("Raised, then the parcel turned up the same afternoon.")
                .amount(SeedCatalogue.money("113.99")).currency("EUR")
                .amountNative(SeedCatalogue.money("8500.00"))
                .fx(FxSnapshot.published("GMD", "EUR", SeedCatalogue.rate("0.01341000"),
                        cat.daysAgo(4)))
                .frozenAt(cat.daysAgo(2)).unfrozenAt(cat.daysAgo(2).plusHours(5))
                .holdReference("HOLD-DSP-000006")
                .withdrawnAt(cat.daysAgo(2).plusHours(5))
                .build()));

        // Closed with nothing decided: the buyer stopped responding and the
        // window ran out. Not the same as deciding for either side.
        cat.disputes.put("no-decision", cat.save(Dispute.builder()
                .reference("DSP-000007")
                .vendorOrder(cat.vendorOrder("1010-bp")).raisedBy(cat.user("yankuba"))
                .status(DisputeStatus.RESOLVED).reason(DisputeReason.UNAUTHORISED_CHARGE)
                .description("Says he never placed this order.")
                .amount(SeedCatalogue.money("1120.00")).currency("GMD")
                .amountNative(SeedCatalogue.money("1120.00"))
                .fx(FxSnapshot.identity("GMD", cat.daysAgo(1)))
                .outcome(DisputeOutcome.NO_DECISION)
                .resolvedBy(cat.user("support")).resolvedAt(cat.hoursAgo(3))
                .resolutionNote("No response in fourteen days and nothing was ever charged.")
                .build()));

        // Link the escalated return back to the dispute it produced, now that
        // both rows exist.
        cat.returnRequest("escalated").setDispute(cat.dispute("resolved-buyer"));
    }

    private void disputeMessages(SeedCatalogue cat) {
        message(cat, "resolved-buyer", "sally", "BUYER",
                "Le tissu reçu n'est pas du wax hollandais. Les photos le montrent clairement.",
                false);
        message(cat, "resolved-buyer", "lamin", "VENDOR",
                "C'est le tissu que nous vendons. L'annonce a été rédigée par mon assistant.",
                false);
        message(cat, "resolved-buyer", "support", "STAFF",
                "Le vendeur admet que l'annonce est inexacte. Décision pour l'acheteuse.",
                true);
        message(cat, "resolved-buyer", "support", "STAFF",
                "Remboursement intégral traité. Le litige est clos.", false);

        message(cat, "under-review", "cheikh", "BUYER",
                "L'écran était fêlé quand j'ai ouvert le colis. Photos jointes.", false);
        message(cat, "under-review", "omar", "VENDOR",
                "L'appareil a été vérifié avant l'expédition. C'est arrivé pendant le transport.",
                false);
        message(cat, "under-review", "support", "STAFF",
                "Demander au livreur les photos de ramassage avant de trancher.", true);

        message(cat, "open-overdue", "binta", "BUYER",
                "No call, no knock, nothing left with a neighbour.", false);

        message(cat, "resolved-vendor", "isatou", "BUYER",
                "She says it is black and I ordered blue.", false);
        message(cat, "resolved-vendor", "fatou", "VENDOR",
                "The delivery photograph shows the blue handset. The IMEI matches the blue SKU.",
                false);

        message(cat, "split", "modou", "BUYER",
                "No charger, and no reply for five days.", false);
        message(cat, "split", "support", "STAFF",
                "Seller unreachable. Refunding the charger and closing.", false);
    }

    private void message(SeedCatalogue cat, String disputeKey, String authorKey, String side,
                         String body, boolean internal) {
        cat.save(DisputeMessage.builder()
                .dispute(cat.dispute(disputeKey)).author(cat.user(authorKey))
                .authorSide(side).body(body).internal(internal)
                .build());
    }

    private void disputeEvidence(SeedCatalogue cat) {
        evidence(cat, "resolved-buyer", "sally", "BUYER",
                "https://cdn.sujula.gm/sample/disputes/000001-fabric-received.jpg",
                "image/jpeg", "Le tissu reçu, à plat.");
        evidence(cat, "resolved-buyer", "sally", "BUYER",
                "https://cdn.sujula.gm/sample/disputes/000001-listing-screenshot.png",
                "image/png", "Capture de l'annonce d'origine.");
        evidence(cat, "resolved-buyer", "lamin", "VENDOR",
                "https://cdn.sujula.gm/sample/disputes/000001-supplier-invoice.pdf",
                "application/pdf", "Facture du fournisseur.");
        evidence(cat, "under-review", "cheikh", "BUYER",
                "https://cdn.sujula.gm/sample/disputes/000002-cracked-screen.jpg",
                "image/jpeg", "Le coin fêlé.");
        evidence(cat, "under-review", "cheikh", "BUYER",
                "https://cdn.sujula.gm/sample/disputes/000002-packaging.jpg",
                "image/jpeg", "Le carton, intact.");
        evidence(cat, "under-review", "omar", "VENDOR",
                "https://cdn.sujula.gm/sample/disputes/000002-pre-dispatch.jpg",
                "image/jpeg", "Contrôle avant expédition.");
        // Filed by support from the driver's app rather than by either party.
        evidence(cat, "under-review", "support", "STAFF",
                "https://cdn.sujula.gm/sample/custody/1006-delivered.jpg",
                "image/jpeg", "Photo de remise, extraite du dossier de livraison.");
        evidence(cat, "resolved-vendor", "fatou", "VENDOR",
                "https://cdn.sujula.gm/sample/disputes/000004-imei-record.pdf",
                "application/pdf", "IMEI record showing the blue SKU.");
        evidence(cat, "open-overdue", "binta", "BUYER",
                "https://cdn.sujula.gm/sample/disputes/000003-call-log.png",
                "image/png", "Call log — no missed calls that day.");
    }

    private void evidence(SeedCatalogue cat, String disputeKey, String uploaderKey, String side,
                          String url, String contentType, String caption) {
        cat.save(DisputeEvidence.builder()
                .dispute(cat.dispute(disputeKey)).uploadedBy(cat.user(uploaderKey))
                .uploadedBySide(side).url(url).contentType(contentType).caption(caption)
                .build());
    }

    // ── Buyer and seller messaging ───────────────────────────────────────────

    /**
     * Threads between a buyer and a seller, with the filter's work visible.
     *
     * <p>Two of these messages were rewritten on the way through because they
     * contained a phone number or a request to pay off-platform, and both the
     * shown body and the original are stored. Keeping only the filtered version
     * would leave a moderator unable to tell what was actually sent.
     */
    private void threads(SeedCatalogue cat) {
        MessageThread orderThread = cat.save(MessageThread.builder()
                .subject(ThreadSubject.ORDER)
                .order(cat.order("1002")).buyer(cat.user("isatou"))
                .vendor(cat.vendor("dakar-tech"))
                .title("SJL-1002 — when will the Dakar half be dispatched?")
                .lastMessageAt(cat.hoursAgo(9))
                .unreadForBuyer(0).unreadForVendor(1)
                .build());
        threadMessage(cat, orderThread, "isatou", "BUYER",
                "The Banjul half is already on its way. Any news on the A15?",
                null, false, null, cat.hoursAgo(30));
        threadMessage(cat, orderThread, "omar", "VENDOR",
                "Demain matin au plus tard. Désolé pour l'attente.",
                null, false, null, cat.hoursAgo(26));
        threadMessage(cat, orderThread, "isatou", "BUYER",
                "Thank you. My sister is only home in the afternoons.",
                null, false, null, cat.hoursAgo(9));

        MessageThread productThread = cat.save(MessageThread.builder()
                .subject(ThreadSubject.PRODUCT)
                .product(cat.product("iphone11")).buyer(cat.user("modou"))
                .vendor(cat.vendor("banjul-phones"))
                .title("iPhone 11 — battery health before I buy")
                .lastMessageAt(cat.daysAgo(2))
                .unreadForBuyer(1).unreadForVendor(0)
                .build());
        threadMessage(cat, productThread, "modou", "BUYER",
                "What is the exact battery health on the one you have in stock?",
                null, false, null, cat.daysAgo(3));
        threadMessage(cat, productThread, "fatou", "VENDOR",
                "91% on the unit here now. I can send a screenshot.",
                null, false, null, cat.daysAgo(2));

        // Filtered: a phone number, removed on the way through.
        MessageThread filteredThread = cat.save(MessageThread.builder()
                .subject(ThreadSubject.PRODUCT)
                .product(cat.product("spark10")).buyer(cat.user("yankuba"))
                .vendor(cat.vendor("banjul-phones"))
                .title("Tecno Spark 10 — cash price")
                .lastMessageAt(cat.daysAgo(6))
                .unreadForBuyer(0).unreadForVendor(1)
                .build());
        threadMessage(cat, filteredThread, "yankuba", "BUYER",
                "Call me on [removed] and we can agree a cash price outside the app.",
                "Call me on 220 720 0222 and we can agree a cash price outside the app.",
                true, "PHONE_NUMBER,OFF_PLATFORM", cat.daysAgo(6));

        MessageThread closedThread = cat.save(MessageThread.builder()
                .subject(ThreadSubject.ORDER)
                .order(cat.order("1001")).buyer(cat.user("isatou"))
                .vendor(cat.vendor("banjul-phones"))
                .title("SJL-1001 — delivered, thank you")
                .lastMessageAt(cat.daysAgo(9))
                .unreadForBuyer(0).unreadForVendor(0)
                .closedAt(cat.daysAgo(8))
                .build());
        threadMessage(cat, closedThread, "isatou", "BUYER",
                "It reached her this morning. Thank you for the quick dispatch.",
                null, false, null, cat.daysAgo(9));
        threadMessage(cat, closedThread, "fatou", "VENDOR",
                "Glad to hear it. Come back any time.",
                null, false, null, cat.daysAgo(9));

        // Opened and never answered. The seller's unread count is the whole
        // point of the row.
        MessageThread unanswered = cat.save(MessageThread.builder()
                .subject(ThreadSubject.PRODUCT)
                .product(cat.product("castironpot")).buyer(cat.user("sally"))
                .vendor(cat.vendor("serrekunda-home"))
                .title("Cast iron pot — gas ring?")
                .lastMessageAt(cat.daysAgo(1))
                .unreadForBuyer(0).unreadForVendor(1)
                .build());
        threadMessage(cat, unanswered, "sally", "BUYER",
                "Is it safe on a gas ring, or only on charcoal?",
                null, false, null, cat.daysAgo(1));

        // Filtered for an email address this time, and read by the seller.
        MessageThread emailFiltered = cat.save(MessageThread.builder()
                .subject(ThreadSubject.ORDER)
                .order(cat.order("1003")).buyer(cat.user("modou"))
                .vendor(cat.vendor("banjul-phones"))
                .title("SJL-1003 — missing charger")
                .lastMessageAt(cat.hoursAgo(22))
                .unreadForBuyer(0).unreadForVendor(0)
                .build());
        threadMessage(cat, emailFiltered, "modou", "BUYER",
                "Send the charger to [removed] — my cousin will bring it over.",
                "Send the charger to modou.jallow@example.co.uk — my cousin will bring it over.",
                true, "EMAIL_ADDRESS", cat.daysAgo(1));
        ThreadMessage read = ThreadMessage.builder()
                .thread(emailFiltered).sender(cat.user("fatou")).senderSide("VENDOR")
                .body("We will refund it instead — that is simpler.")
                .filtered(false)
                .readAt(cat.hoursAgo(21))
                .build();
        cat.save(read);
    }

    private void threadMessage(SeedCatalogue cat, MessageThread thread, String senderKey,
                               String side, String body, String originalBody, boolean filtered,
                               String filteredKinds, java.time.LocalDateTime readAt) {
        cat.save(ThreadMessage.builder()
                .thread(thread).sender(cat.user(senderKey)).senderSide(side)
                .body(body).originalBody(originalBody)
                .filtered(filtered).filteredKinds(filteredKinds)
                .readAt(readAt)
                .build());
    }

    // ── Reports against reviews ──────────────────────────────────────────────

    private void reviewReports(SeedCatalogue cat) {
        cat.save(ReviewReport.builder()
                .review(cat.review("wax-fanta")).reportedBy(cat.user("lamin"))
                .reason(ReviewReportReason.FALSE_CLAIM)
                .detail("Claims the goods are counterfeit and gives no evidence.")
                .build());
        cat.save(ReviewReport.builder()
                .review(cat.review("wax-fanta")).reportedBy(cat.user("binta"))
                .reason(ReviewReportReason.PERSONAL_INFORMATION)
                .detail("Invites people to message her privately to buy elsewhere.")
                .reviewedAt(cat.daysAgo(10)).reviewedBy(cat.user("support"))
                .moderatorNote("Hidden pending a decision on the seller's case.")
                .build());
        cat.save(ReviewReport.builder()
                .review(cat.review("wax-fanta")).reportedBy(cat.user("modou"))
                .reason(ReviewReportReason.SPAM)
                .build());
        cat.save(ReviewReport.builder()
                .review(cat.review("pot-yankuba")).reportedBy(cat.user("awa"))
                .reason(ReviewReportReason.WRONG_PRODUCT)
                .detail("Complains it is heavy — which is what an eight-litre cast iron pot is.")
                .reviewedAt(cat.daysAgo(4)).reviewedBy(cat.user("support"))
                .moderatorNote("A low opinion is not a rule breach. Left up.")
                .build());
        cat.save(ReviewReport.builder()
                .review(cat.review("spark10-binta")).reportedBy(cat.user("fatou"))
                .reason(ReviewReportReason.ABUSIVE)
                .detail("Reported in error; the review is critical but civil.")
                .reviewedAt(cat.daysAgo(3)).reviewedBy(cat.user("admin"))
                .moderatorNote("No breach. Seller advised.")
                .build());
        // Unreviewed: the queue's backlog.
        cat.save(ReviewReport.builder()
                .review(cat.review("hot30-mariama")).reportedBy(cat.user("fatou"))
                .reason(ReviewReportReason.FALSE_CLAIM)
                .detail("Withdrawn by its author but the rating is still counted.")
                .build());
    }

    // ── Moderation cases and sanctions ───────────────────────────────────────

    /**
     * Cases against people and listings, and what came of them.
     *
     * <p>A case names its subject by type and id rather than by a foreign key,
     * because the things that get moderated — a listing, a review, a store, a
     * message — have no common table to point at. The sanction is the outcome,
     * and the two are linked both ways so neither can be read without the other.
     */
    private void moderation(SeedCatalogue cat) {
        Sanction fabricWarning = cat.save(Sanction.builder()
                .user(cat.user("lamin")).type(SanctionType.WARNING)
                .reason(ModerationReason.MISLEADING_LISTING)
                .reasonText("Listed a local print as Dutch wax.")
                .issuedBy(cat.user("support"))
                .build());

        Sanction storeSuspension = cat.save(Sanction.builder()
                .user(cat.user("lamin")).type(SanctionType.SUSPENSION)
                .reason(ModerationReason.NON_DELIVERY)
                .reasonText("Three parcels not delivered and not refunded.")
                .expiresAt(cat.daysAhead(19))
                .issuedBy(cat.user("admin"))
                .build());

        Sanction ban = cat.save(Sanction.builder()
                .user(cat.user("baboucarr")).type(SanctionType.BAN)
                .reason(ModerationReason.IDENTITY_FRAUD)
                .reasonText("Three accounts opened on one set of documents.")
                .issuedBy(cat.user("admin"))
                .build());

        Sanction restriction = cat.save(Sanction.builder()
                .user(cat.user("fanta")).type(SanctionType.FEATURE_RESTRICTION)
                .reason(ModerationReason.RATING_MANIPULATION)
                .reasonText("Reviews on products never bought.")
                .restrictedPermission("REVIEW_WRITE")
                .expiresAt(cat.daysAhead(60))
                .issuedBy(cat.user("support"))
                .build());

        // Lifted early. The row stays with who lifted it and why, because a
        // sanction that simply disappears leaves the next moderator guessing.
        cat.save(Sanction.builder()
                .user(cat.user("yankuba")).type(SanctionType.FEATURE_RESTRICTION)
                .reason(ModerationReason.OFF_PLATFORM_PAYMENT)
                .reasonText("Asked two sellers to deal off the platform.")
                .restrictedPermission("MESSAGE_SEND")
                .expiresAt(cat.daysAhead(10))
                .liftedAt(cat.daysAgo(1)).liftedBy(cat.user("support"))
                .liftedReason("First offence and the messages were filtered before delivery.")
                .issuedBy(cat.user("support"))
                .build());

        // Expired on its own. Neither active nor lifted — the third state.
        cat.save(Sanction.builder()
                .user(cat.user("sulayman")).type(SanctionType.WARNING)
                .reason(ModerationReason.ABUSIVE_CONDUCT)
                .reasonText("Abusive language to a driver.")
                .expiresAt(cat.daysAgo(5))
                .issuedBy(cat.user("support"))
                .build());

        cat.save(ModerationCase.builder()
                .reference("MOD-000001").status(ModerationCaseStatus.RESOLVED)
                .reason(ModerationReason.MISLEADING_LISTING)
                .subjectType("PRODUCT").subjectId(cat.product("waxfabric").getId())
                .subjectLabel("Wax print fabric, 6 yards")
                .accountable(cat.user("lamin")).vendor(cat.vendor("kololi-style"))
                .raisedBy(cat.user("support")).source("STAFF")
                .description("Listing claims Dutch wax; the seller's own invoice shows otherwise.")
                .evidenceUrls("https://cdn.sujula.gm/sample/disputes/000001-listing-screenshot.png")
                .assignedTo(cat.user("support")).assignedAt(cat.daysAgo(12))
                .dueBy(cat.daysAgo(10))
                .resolvedBy(cat.user("support")).resolvedAt(cat.daysAgo(11))
                .outcome("LISTING_SUSPENDED")
                .resolutionNote("Listing suspended and the seller warned.")
                .sanction(fabricWarning)
                .build());

        cat.save(ModerationCase.builder()
                .reference("MOD-000002").status(ModerationCaseStatus.RESOLVED)
                .reason(ModerationReason.NON_DELIVERY)
                .subjectType("VENDOR").subjectId(cat.vendor("kololi-style").getId())
                .subjectLabel("Kololi Style")
                .accountable(cat.user("lamin")).vendor(cat.vendor("kololi-style"))
                .raisedBy(cat.user("admin")).source("STAFF")
                .description("Three non-delivery complaints in a fortnight.")
                .assignedTo(cat.user("admin")).assignedAt(cat.daysAgo(11))
                .dueBy(cat.daysAgo(9))
                .resolvedBy(cat.user("admin")).resolvedAt(cat.daysAgo(11))
                .outcome("STORE_SUSPENDED")
                .resolutionNote("Store suspended and payouts held for thirty days.")
                .sanction(storeSuspension)
                .build());

        // Open, assigned, and already past its deadline.
        cat.save(ModerationCase.builder()
                .reference("MOD-000003").status(ModerationCaseStatus.IN_REVIEW)
                .reason(ModerationReason.RATING_MANIPULATION)
                .subjectType("REVIEW").subjectId(cat.review("wax-fanta").getId())
                .subjectLabel("Review on Wax print fabric, 6 yards")
                .accountable(cat.user("fanta"))
                .raisedBy(cat.user("lamin")).source("VENDOR_REPORT")
                .description("Three reports on one review; the author never bought the product.")
                .assignedTo(cat.user("support")).assignedAt(cat.daysAgo(9))
                .dueBy(cat.daysAgo(7))
                .sanction(restriction)
                .build());

        cat.save(ModerationCase.builder()
                .reference("MOD-000004").status(ModerationCaseStatus.OPEN)
                .reason(ModerationReason.PROHIBITED_ITEM)
                .subjectType("PRODUCT").subjectId(cat.product("bonga").getId())
                .subjectLabel("Dried bonga fish, 1 kg")
                .accountable(cat.user("alieu")).vendor(cat.vendor("farafenni-foods"))
                .raisedBy(cat.user("admin")).source("AUTOMATED")
                .description("Perishable food listed without a handling certificate.")
                .dueBy(cat.daysAhead(3))
                .build());

        cat.save(ModerationCase.builder()
                .reference("MOD-000005").status(ModerationCaseStatus.DISMISSED)
                .reason(ModerationReason.ABUSIVE_CONDUCT)
                .subjectType("REVIEW").subjectId(cat.review("pot-yankuba").getId())
                .subjectLabel("Review on Cast iron cooking pot, 8 litre")
                .accountable(cat.user("yankuba"))
                .raisedBy(cat.user("awa")).source("VENDOR_REPORT")
                .description("Seller reported a two-star review as abusive.")
                .assignedTo(cat.user("support")).assignedAt(cat.daysAgo(5))
                .resolvedBy(cat.user("support")).resolvedAt(cat.daysAgo(4))
                .outcome("NO_ACTION")
                .resolutionNote("A low opinion is not a rule breach.")
                .build());

        cat.save(ModerationCase.builder()
                .reference("MOD-000006").status(ModerationCaseStatus.RESOLVED)
                .reason(ModerationReason.IDENTITY_FRAUD)
                .subjectType("USER").subjectId(cat.user("baboucarr").getId())
                .subjectLabel("Baboucarr Jatta")
                .accountable(cat.user("baboucarr"))
                .raisedBy(cat.user("admin")).source("AUTOMATED")
                .description("Three accounts sharing one national identity number.")
                .assignedTo(cat.user("admin")).assignedAt(cat.daysAgo(8))
                .resolvedBy(cat.user("admin")).resolvedAt(cat.daysAgo(7))
                .outcome("ACCOUNT_BANNED")
                .resolutionNote("Account blocked and the duplicates closed.")
                .sanction(ban)
                .build());

        cat.save(ModerationCase.builder()
                .reference("MOD-000007").status(ModerationCaseStatus.OPEN)
                .reason(ModerationReason.OFF_PLATFORM_PAYMENT)
                .subjectType("MESSAGE").subjectId(1L)
                .subjectLabel("Thread: Tecno Spark 10 — cash price")
                .accountable(cat.user("yankuba"))
                .raisedBy(cat.user("fatou")).source("VENDOR_REPORT")
                .description("Buyer asked to settle in cash outside the platform.")
                .dueBy(cat.daysAhead(2))
                .build());

        cat.save(ModerationCase.builder()
                .reference("MOD-000008").status(ModerationCaseStatus.OPEN)
                .reason(ModerationReason.OTHER)
                .subjectType("DRIVER").subjectId(cat.driver("aminata").getId())
                .subjectLabel("Aminata Sarr")
                .accountable(cat.user("aminata"))
                .raisedBy(cat.user("support")).source("STAFF")
                .description("Two parcels released without a code being presented.")
                .assignedTo(cat.user("admin")).assignedAt(cat.daysAgo(2))
                .dueBy(cat.daysAhead(1))
                .build());
    }
}
