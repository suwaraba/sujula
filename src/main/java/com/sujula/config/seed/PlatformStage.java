package com.sujula.config.seed;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.sujula.model.AuditLog;
import com.sujula.model.catalogue.CatalogueJob;
import com.sujula.model.catalogue.CatalogueJobError;
import com.sujula.model.constant.AuditAction;
import com.sujula.model.constant.CallbackOutcome;
import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.model.constant.CatalogueJobType;
import com.sujula.model.constant.JobRunStatus;
import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.WebhookKind;
import com.sujula.model.constant.WebhookStatus;
import com.sujula.model.idempotency.IdempotencyRecord;
import com.sujula.model.platform.Announcement;
import com.sujula.model.platform.CallbackRequest;
import com.sujula.model.platform.FeatureFlag;
import com.sujula.model.platform.JobRun;
import com.sujula.model.user.User;
import com.sujula.model.webhook.WebhookEvent;
import com.sujula.service.platform.FeatureFlags;

/**
 * The platform's own tables: switches, jobs, webhooks, announcements and the
 * audit trail.
 *
 * <p>These are the rows nobody thinks to seed and everybody needs: a webhook
 * that arrived twice, a job that died halfway, an idempotency record that has to
 * return the first answer rather than doing the work again. Each is a failure
 * mode with no other way to reproduce it on a developer's machine.
 *
 * <p>The feature flags are written here rather than left to {@code FeatureFlags}
 * on startup so they carry a history — who last moved each one and why. That
 * bean runs after this stage and creates only what is missing, so the four
 * declared keys below are left exactly as set.
 */
@Component
class PlatformStage implements SeedStage {

    @Override
    public String name() {
        return "Flags, jobs, webhooks, announcements and the audit trail";
    }

    @Override
    public void seed(SeedCatalogue cat) {
        featureFlags(cat);
        announcements(cat);
        callbackRequests(cat);
        jobRuns(cat);
        catalogueJobs(cat);
        webhookEvents(cat);
        idempotencyRecords(cat);
        auditLog(cat);
    }

    // ── Feature flags ────────────────────────────────────────────────────────

    private void featureFlags(SeedCatalogue cat) {
        Long admin = cat.user("admin").getId();

        cat.save(FeatureFlag.builder()
                .flagKey(FeatureFlags.SAFE_DROP).label("Safe drop")
                .description("Lets a driver leave a parcel without a code where the recipient has "
                        + "authorised it in advance. Off, every delivery needs a code at the door "
                        + "— slower, and impossible for somebody who is out at work.")
                .enabled(true).clientVisible(true)
                .lastChangedByUserId(admin).lastChangedAt(cat.daysAgo(40))
                .lastChangeReason("Enabled after the geofence columns went live.")
                .build());
        cat.save(FeatureFlag.builder()
                .flagKey(FeatureFlags.GUEST_CHECKOUT).label("Guest checkout")
                .description("Lets somebody buy without an account. Off, every buyer must register "
                        + "first, which costs orders from people sending goods home once.")
                .enabled(true).clientVisible(true)
                .lastChangedByUserId(admin).lastChangedAt(cat.daysAgo(120))
                .lastChangeReason("On since launch.")
                .build());
        // Off, with the reason on the row. A flag switched off and unexplained
        // is one nobody dares switch back.
        cat.save(FeatureFlag.builder()
                .flagKey(FeatureFlags.VENDOR_SIGNUP).label("Seller applications")
                .description("Whether new sellers may apply. Off while a backlog is cleared — "
                        + "existing sellers are unaffected and keep trading.")
                .enabled(false).clientVisible(true)
                .lastChangedByUserId(admin).lastChangedAt(cat.daysAgo(5))
                .lastChangeReason("Paused while the KYC queue is cleared. Review on the 30th.")
                .build());
        cat.save(FeatureFlag.builder()
                .flagKey(FeatureFlags.IMPERSONATION).label("Administrator impersonation")
                .description("Lets an administrator open a session as another user for support "
                        + "work. Every use is audited. Off, support must work from what the person "
                        + "tells them.")
                .enabled(true).clientVisible(false)
                .lastChangedByUserId(admin).lastChangedAt(cat.daysAgo(60))
                .lastChangeReason("Enabled for the support team's first week.")
                .build());

        // Two keys that exist only for this dataset, so a flags screen has more
        // than four rows and something to page and filter. They are named
        // sample.* precisely so nobody mistakes them for switches with readers.
        cat.save(FeatureFlag.builder()
                .flagKey("sample.pickup-point-capacity-warnings")
                .label("Pickup capacity warnings (sample)")
                .description("Sample row. Not read anywhere in the application — it exists so the "
                        + "flags screen has a disabled, client-invisible row to render.")
                .enabled(false).clientVisible(false)
                .lastChangedByUserId(cat.user("support").getId()).lastChangedAt(cat.daysAgo(2))
                .lastChangeReason("Sample data.")
                .build());
        cat.save(FeatureFlag.builder()
                .flagKey("sample.fx-spread-banner")
                .label("FX spread banner (sample)")
                .description("Sample row. Not read anywhere in the application — it exists so the "
                        + "flags screen has an enabled, client-visible row that no code depends on.")
                .enabled(true).clientVisible(true)
                .lastChangedByUserId(cat.user("support").getId()).lastChangedAt(cat.daysAgo(1))
                .lastChangeReason("Sample data.")
                .build());
    }

    // ── Announcements ────────────────────────────────────────────────────────

    private void announcements(SeedCatalogue cat) {
        Long admin = cat.user("admin").getId();

        Announcement toEveryone = Announcement.builder()
                .reference("ANN-2024-0001")
                .title("Scheduled maintenance on Sunday")
                .body("The marketplace will be read-only between 02:00 and 04:00 GMT on Sunday. "
                        + "Orders already placed are unaffected and parcels keep moving.")
                .event(NotificationEvent.PLATFORM_NOTICE)
                .sentByUserId(admin).sentAt(cat.daysAgo(9))
                .build();
        toEveryone.applyReach(1840, 1792);
        cat.save(toEveryone);

        Announcement toSellers = Announcement.builder()
                .reference("ANN-2024-0002")
                .title("Commission on completed orders drops to 8%")
                .body("From the first of next month, stores past two hundred completed orders "
                        + "settle at 8% rather than 10%. Nothing to apply for.")
                .audienceRole(UserRole.VENDOR)
                .event(NotificationEvent.PLATFORM_NOTICE)
                .sentByUserId(admin).sentAt(cat.daysAgo(30))
                .build();
        toSellers.applyReach(7, 7);
        cat.save(toSellers);

        // Segmented by country: only the Senegalese side is told.
        Announcement toSenegal = Announcement.builder()
                .reference("ANN-2024-0003")
                .title("Nouvelle zone de livraison : Thiès")
                .body("Thiès est désormais desservie. Les frais suivent la grille tarifaire "
                        + "publiée aujourd'hui.")
                .countryCode("SN")
                .event(NotificationEvent.PLATFORM_NOTICE)
                .sentByUserId(admin).sentAt(cat.daysAgo(60))
                .build();
        toSenegal.applyReach(412, 398);
        cat.save(toSenegal);

        Announcement toDrivers = Announcement.builder()
                .reference("ANN-2024-0004")
                .title("Codes are now required at every handover")
                .body("From today, no parcel changes hands without a code being presented. "
                        + "Photographs alone will not close a delivery.")
                .audienceRole(UserRole.DELIVERY)
                .event(NotificationEvent.SECURITY_ALERT)
                .sentByUserId(admin).sentAt(cat.daysAgo(45))
                .build();
        toDrivers.applyReach(6, 6);
        cat.save(toDrivers);

        Announcement toGambianBuyers = Announcement.builder()
                .reference("ANN-2024-0005")
                .title("Tobaski delivery timetable")
                .body("Deliveries pause for two days over Tobaski. Orders placed before Thursday "
                        + "will reach their destination first.")
                .audienceRole(UserRole.CUSTOMER).countryCode("GM")
                .event(NotificationEvent.PLATFORM_NOTICE)
                .sentByUserId(cat.user("support").getId()).sentAt(cat.daysAgo(15))
                .build();
        toGambianBuyers.applyReach(1104, 1088);
        cat.save(toGambianBuyers);

        // Drafted and never sent. sentAt is null and the reach is zero, which is
        // the state a "send" button has to be able to act on.
        cat.save(Announcement.builder()
                .reference("ANN-2024-0006")
                .title("New pickup point in Bakau — draft")
                .body("Bakau Newtown Hardware reopens on the 30th. Draft; do not send until the "
                        + "refurbishment is confirmed finished.")
                .audienceRole(UserRole.CUSTOMER).countryCode("GM")
                .event(NotificationEvent.PLATFORM_NOTICE)
                .sentByUserId(cat.user("support").getId())
                .build());

        // Sent, and reached fewer people than the segment held: the difference
        // is opt-outs, and hiding it would make the number look like a lie.
        Announcement partial = Announcement.builder()
                .reference("ANN-2024-0007")
                .title("Promotions on now")
                .body("Ten percent off handsets at Banjul Phones this fortnight.")
                .audienceRole(UserRole.CUSTOMER)
                .event(NotificationEvent.PROMOTION)
                .sentByUserId(cat.user("support").getId()).sentAt(cat.daysAgo(6))
                .build();
        partial.applyReach(1512, 1104);
        cat.save(partial);
    }

    // ── Callbacks ────────────────────────────────────────────────────────────

    /**
     * Requests for somebody to be telephoned.
     *
     * <p>This exists because C5's world is real: the person who most needs
     * telling is often the one who cannot use a web form. Every outcome the
     * model defines is here, including the two that mean "try again".
     */
    private void callbackRequests(SeedCatalogue cat) {
        cat.save(CallbackRequest.builder()
                .disputeId(cat.dispute("under-review").getId())
                .phone("+221770333444").contactName("Cheikh Ndiaye")
                .preferredLanguage("fr")
                .reason("Veut expliquer l'état du colis à l'ouverture.")
                .requestedByUserId(cat.user("cheikh").getId())
                .callBy(cat.daysAhead(1))
                .attempts(0)
                .build());
        cat.save(CallbackRequest.builder()
                .disputeId(cat.dispute("open-overdue").getId())
                .phone("+2207200111").contactName("Binta Touray")
                .preferredLanguage("en")
                .reason("Says the driver never came. Needs telling what happens next.")
                .requestedByUserId(cat.user("support").getId())
                .callBy(cat.hoursAgo(4))
                .outcome(CallbackOutcome.NO_ANSWER)
                .calledByUserId(cat.user("support").getId()).calledAt(cat.hoursAgo(6))
                .notes("Rang twice, no answer. Will try again this evening.")
                .attempts(2)
                .build());
        cat.save(CallbackRequest.builder()
                .disputeId(cat.dispute("resolved-buyer").getId())
                .phone("+33600222333").contactName("Sally Mendy")
                .preferredLanguage("fr")
                .reason("Explication de la décision du litige.")
                .requestedByUserId(cat.user("support").getId())
                .callBy(cat.daysAgo(15))
                .outcome(CallbackOutcome.SPOKE)
                .calledByUserId(cat.user("support").getId()).calledAt(cat.daysAgo(15))
                .notes("Décision expliquée; remboursement confirmé. Satisfaite.")
                .attempts(1)
                .build());
        // No dispute attached: a recipient with no account rang the shop and
        // asked to be called back about a parcel.
        cat.save(CallbackRequest.builder()
                .phone("+2207700100").contactName("Aji Ceesay")
                .preferredLanguage("en")
                .reason("Recipient with no account; wants to change the delivery window.")
                .requestedByUserId(cat.user("support").getId())
                .callBy(cat.hoursAhead(6))
                .attempts(0)
                .build());
        cat.save(CallbackRequest.builder()
                .phone("+2207700300").contactName("Fatoumata Mendy")
                .preferredLanguage("en")
                .reason("Three failed attempts; arrange a pickup point instead.")
                .requestedByUserId(cat.user("support").getId())
                .callBy(cat.daysAgo(21))
                .outcome(CallbackOutcome.UNREACHABLE)
                .calledByUserId(cat.user("support").getId()).calledAt(cat.daysAgo(21))
                .notes("Number unobtainable on three attempts across two days.")
                .attempts(3)
                .build());
        cat.save(CallbackRequest.builder()
                .disputeId(cat.dispute("split").getId())
                .phone("+447700900111").contactName("Modou Jallow")
                .preferredLanguage("en")
                .reason("Wants the split decision explained.")
                .requestedByUserId(cat.user("modou").getId())
                .callBy(cat.daysAhead(2))
                .outcome(CallbackOutcome.RESCHEDULED)
                .calledByUserId(cat.user("support").getId()).calledAt(cat.hoursAgo(20))
                .notes("Asked for Saturday morning UK time.")
                .attempts(1)
                .build());
        cat.save(CallbackRequest.builder()
                .phone("+2207200444").contactName("Fanta Kanteh")
                .preferredLanguage("en")
                .reason("Review restriction; wants to appeal.")
                .requestedByUserId(cat.user("support").getId())
                .callBy(cat.daysAgo(3))
                .outcome(CallbackOutcome.DECLINED)
                .calledByUserId(cat.user("admin").getId()).calledAt(cat.daysAgo(3))
                .notes("Refused to discuss it over the phone. Told to appeal in writing.")
                .attempts(1)
                .build());
    }

    // ── Background jobs ──────────────────────────────────────────────────────

    private void jobRuns(SeedCatalogue cat) {
        cat.save(JobRun.builder()
                .jobName("cart-cleanup").status(JobRunStatus.SUCCEEDED)
                .startedAt(cat.hoursAgo(6)).finishedAt(cat.hoursAgo(6).plusSeconds(4))
                .itemsProcessed(17)
                .build());
        cat.save(JobRun.builder()
                .jobName("fx-refresh").status(JobRunStatus.SUCCEEDED)
                .startedAt(cat.hoursAgo(2)).finishedAt(cat.hoursAgo(2).plusSeconds(2))
                .itemsProcessed(11)
                .triggeredByUserId(cat.user("admin").getId())
                .build());
        cat.save(JobRun.builder()
                .jobName("escrow-release").status(JobRunStatus.SUCCEEDED)
                .startedAt(cat.hoursAgo(12)).finishedAt(cat.hoursAgo(12).plusSeconds(9))
                .itemsProcessed(3)
                .build());
        cat.save(JobRun.builder()
                .jobName("parcel-storage-overdue").status(JobRunStatus.SUCCEEDED)
                .startedAt(cat.hoursAgo(1)).finishedAt(cat.hoursAgo(1).plusSeconds(1))
                .itemsProcessed(1)
                .build());
        // Running now. No finish time, which is what makes it distinguishable
        // from one that died.
        cat.save(JobRun.builder()
                .jobName("catalogue-import").status(JobRunStatus.RUNNING)
                .startedAt(cat.hoursAgo(1).plusMinutes(50))
                .itemsProcessed(412)
                .triggeredByUserId(cat.user("admin").getId())
                .build());
        cat.save(JobRun.builder()
                .jobName("payout-batch-build").status(JobRunStatus.FAILED)
                .startedAt(cat.daysAgo(2)).finishedAt(cat.daysAgo(2).plusSeconds(31))
                .itemsProcessed(0)
                .failureReason("No exchange rate published for XOF→EUR on the run date, so the "
                        + "batch could not be totalled. Refused rather than guessing a rate.")
                .build());
        // Started and never heard from again — the process was killed. Marked
        // abandoned by the next run rather than left RUNNING for ever.
        cat.save(JobRun.builder()
                .jobName("product-view-rollup").status(JobRunStatus.ABANDONED)
                .startedAt(cat.daysAgo(4)).finishedAt(cat.daysAgo(4).plusHours(6))
                .itemsProcessed(88)
                .failureReason("No heartbeat for six hours; marked abandoned by the next run.")
                .build());
        cat.save(JobRun.builder()
                .jobName("notification-digest").status(JobRunStatus.SUCCEEDED)
                .startedAt(cat.daysAgo(1)).finishedAt(cat.daysAgo(1).plusSeconds(14))
                .itemsProcessed(233)
                .build());
    }

    // ── Bulk catalogue work ──────────────────────────────────────────────────

    private void catalogueJobs(SeedCatalogue cat) {
        cat.save(CatalogueJob.builder()
                .reference("CJ-2024-000001").vendor(cat.vendor("banjul-phones"))
                .requestedBy(cat.user("fatou"))
                .type(CatalogueJobType.IMPORT).status(CatalogueJobStatus.COMPLETED)
                .originalFilename("banjul-phones-june.csv").format("CSV")
                .totalRows(48).succeededRows(48).failedRows(0)
                .startedAt(cat.daysAgo(20)).finishedAt(cat.daysAgo(20).plusSeconds(11))
                .build());

        // Finished, with rows rejected. The job is not a failure and the
        // rejected rows are not invisible: both facts live on the same record.
        CatalogueJob partial = cat.save(CatalogueJob.builder()
                .reference("CJ-2024-000002").vendor(cat.vendor("dakar-tech"))
                .requestedBy(cat.user("omar"))
                .type(CatalogueJobType.IMPORT).status(CatalogueJobStatus.COMPLETED_WITH_ERRORS)
                .originalFilename("dakar-tech-catalogue.xlsx").format("XLSX")
                .totalRows(120).succeededRows(114).failedRows(6)
                .startedAt(cat.daysAgo(12)).finishedAt(cat.daysAgo(12).plusSeconds(29))
                .build());
        jobError(cat, partial, 14, "price", "Price must be a whole number of francs in XOF.",
                "78500.50");
        jobError(cat, partial, 22, "sku", "A product with this SKU already exists in your store.",
                "DT-SAM-A15-128");
        jobError(cat, partial, 47, "category", "No category matches this name.", "Téléphonie/Divers");
        jobError(cat, partial, 63, "stock", "Stock cannot be negative.", "-3");
        jobError(cat, partial, 91, "priceCurrency", "Currency not supported on this marketplace.",
                "GHS");
        jobError(cat, partial, 118, "name", "Name is required.", null);

        cat.save(CatalogueJob.builder()
                .reference("CJ-2024-000003").vendor(cat.vendor("banjul-phones"))
                .requestedBy(cat.user("fatou"))
                .type(CatalogueJobType.EXPORT).status(CatalogueJobStatus.COMPLETED)
                .format("CSV")
                .resultUrl("https://files.sujula.gm/catalogue/CJ-2024-000003.csv")
                .resultExpiresAt(cat.daysAhead(4))
                .totalRows(52).succeededRows(52).failedRows(0)
                .startedAt(cat.daysAgo(3)).finishedAt(cat.daysAgo(3).plusSeconds(3))
                .build());

        cat.save(CatalogueJob.builder()
                .reference("CJ-2024-000004").vendor(cat.vendor("serrekunda-home"))
                .requestedBy(cat.user("awa"))
                .type(CatalogueJobType.IMPORT).status(CatalogueJobStatus.RUNNING)
                .sourceUrl("https://files.sujula.gm/uploads/serrekunda-home-restock.csv")
                .originalFilename("serrekunda-home-restock.csv").format("CSV")
                .totalRows(300).succeededRows(188).failedRows(2)
                .startedAt(cat.hoursAgo(1))
                .build());

        cat.save(CatalogueJob.builder()
                .reference("CJ-2024-000005").vendor(cat.vendor("kerewan-crafts"))
                .requestedBy(cat.user("sona"))
                .type(CatalogueJobType.IMPORT).status(CatalogueJobStatus.QUEUED)
                .originalFilename("kerewan-first-upload.csv").format("CSV")
                .build());

        CatalogueJob failed = cat.save(CatalogueJob.builder()
                .reference("CJ-2024-000006").vendor(cat.vendor("kololi-style"))
                .requestedBy(cat.user("lamin"))
                .type(CatalogueJobType.IMPORT).status(CatalogueJobStatus.FAILED)
                .originalFilename("kololi-style.csv").format("CSV")
                .totalRows(0).succeededRows(0).failedRows(0)
                .failureReason("The file has no header row, so no column could be identified.")
                .startedAt(cat.daysAgo(8)).finishedAt(cat.daysAgo(8).plusSeconds(1))
                .build());
        jobError(cat, failed, 1, null, "Expected a header row naming the columns.", null);

        cat.save(CatalogueJob.builder()
                .reference("CJ-2024-000007").vendor(cat.vendor("dakar-tech"))
                .requestedBy(cat.user("omar"))
                .type(CatalogueJobType.EXPORT).status(CatalogueJobStatus.COMPLETED)
                .format("XLSX")
                .resultUrl("https://files.sujula.gm/catalogue/CJ-2024-000007.xlsx")
                // The link has already lapsed: the row stays, the file does not.
                .resultExpiresAt(cat.daysAgo(2))
                .totalRows(118).succeededRows(118).failedRows(0)
                .startedAt(cat.daysAgo(9)).finishedAt(cat.daysAgo(9).plusSeconds(7))
                .build());
    }

    private void jobError(SeedCatalogue cat, CatalogueJob job, int row, String field,
                          String message, String value) {
        cat.save(CatalogueJobError.builder()
                .job(job).rowNumber(row).field(field).message(message).value(value)
                .build());
    }

    // ── Webhooks ─────────────────────────────────────────────────────────────

    /**
     * What providers sent us, and what was done about it.
     *
     * <p>The duplicate is the row worth having. Payment providers retry, and a
     * system that processes the retry credits the same order twice — so the
     * second delivery of an event id already seen is recorded as
     * {@code DUPLICATE} and does nothing, which is only testable if a duplicate
     * exists.
     */
    private void webhookEvents(SeedCatalogue cat) {
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("stripe")
                .eventId("evt_sample_1001_paid").eventType("payment_intent.succeeded")
                .status(WebhookStatus.PROCESSED)
                .payload("{\"id\":\"evt_sample_1001_paid\",\"type\":\"payment_intent.succeeded\","
                        + "\"data\":{\"object\":{\"id\":\"ch_sample_1001_7f2a\",\"amount\":11681,"
                        + "\"currency\":\"eur\",\"metadata\":{\"orderNumber\":\"SJL-1001\"}}}}")
                .signatureValid(true)
                .providerTimestamp(cat.daysAgo(12)).receivedAt(cat.daysAgo(12))
                .processedAt(cat.daysAgo(12).plusSeconds(1)).attempts(1)
                .subjectReference("SJL-1001").outcome("Order marked PAID.")
                .build());
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("stripe")
                .eventId("evt_sample_1001_paid_retry").eventType("payment_intent.succeeded")
                .status(WebhookStatus.DUPLICATE)
                .payload("{\"id\":\"evt_sample_1001_paid_retry\","
                        + "\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":"
                        + "{\"id\":\"ch_sample_1001_7f2a\",\"amount\":11681,\"currency\":\"eur\"}}}")
                .signatureValid(true)
                .providerTimestamp(cat.daysAgo(12)).receivedAt(cat.daysAgo(12).plusMinutes(2))
                .processedAt(cat.daysAgo(12).plusMinutes(2)).attempts(1)
                .subjectReference("SJL-1001")
                .outcome("Charge already applied. Ignored without side effects.")
                .build());
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("paypal")
                .eventId("WH-SAMPLE-1003-CAPTURE").eventType("PAYMENT.CAPTURE.COMPLETED")
                .status(WebhookStatus.PROCESSED)
                .payload("{\"id\":\"WH-SAMPLE-1003-CAPTURE\","
                        + "\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":"
                        + "{\"id\":\"PAYID-SAMPLE-1003-KX\",\"amount\":{\"value\":\"101.65\","
                        + "\"currency_code\":\"GBP\"}}}")
                .signatureValid(true)
                .providerTimestamp(cat.daysAgo(5)).receivedAt(cat.daysAgo(5))
                .processedAt(cat.daysAgo(5).plusSeconds(2)).attempts(1)
                .subjectReference("SJL-1003").outcome("Order marked PAID.")
                .build());
        // Bad signature. Recorded and refused: a rejected delivery that leaves
        // no row is an attack nobody can see afterwards.
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("stripe")
                .eventId("evt_sample_forged_0001").eventType("payment_intent.succeeded")
                .status(WebhookStatus.REJECTED)
                .payload("{\"id\":\"evt_sample_forged_0001\",\"data\":{\"object\":"
                        + "{\"metadata\":{\"orderNumber\":\"SJL-1010\"}}}}")
                .signatureValid(false)
                .rejectionReason("Signature does not verify against the configured secret.")
                .receivedAt(cat.daysAgo(1)).attempts(1)
                .subjectReference("SJL-1010")
                .build());
        // Being retried: the handler threw and the provider will send again.
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("stripe")
                .eventId("evt_sample_1006_refund").eventType("charge.refunded")
                .status(WebhookStatus.RETRYING)
                .payload("{\"id\":\"evt_sample_1006_refund\",\"type\":\"charge.refunded\","
                        + "\"data\":{\"object\":{\"id\":\"ch_sample_1006_9de2\","
                        + "\"amount_refunded\":12500,\"currency\":\"xof\"}}}")
                .signatureValid(true)
                .providerTimestamp(cat.daysAgo(10)).receivedAt(cat.daysAgo(10))
                .attempts(3)
                .failureReason("Ledger write failed: no commission-reversal rate for the order "
                        + "date. Will retry once the rate is published.")
                .subjectReference("SJL-1006")
                .build());
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.PSP).provider("stripe")
                .eventId("evt_sample_1009_failed").eventType("payment_intent.payment_failed")
                .status(WebhookStatus.FAILED)
                .payload("{\"id\":\"evt_sample_1009_failed\","
                        + "\"type\":\"payment_intent.payment_failed\"}")
                .signatureValid(true)
                .providerTimestamp(cat.daysAgo(1)).receivedAt(cat.daysAgo(1))
                .attempts(6)
                .failureReason("Six attempts, all failing on the same missing order reference. "
                        + "Given up; needs a person.")
                .build());
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.MESSAGING).provider("africastalking")
                .eventId("sms-sample-0001").eventType("DeliveryReport")
                .status(WebhookStatus.PROCESSED)
                .payload("{\"id\":\"sms-sample-0001\",\"status\":\"Success\","
                        + "\"phoneNumber\":\"+2207700100\"}")
                .signatureValid(true)
                .providerTimestamp(cat.daysAgo(9)).receivedAt(cat.daysAgo(9))
                .processedAt(cat.daysAgo(9)).attempts(1)
                .subjectReference("SHP-000001").outcome("Parcel code delivered to the recipient.")
                .build());
        // Nothing to do with anything we handle. Stored and left alone rather
        // than treated as an error.
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.MESSAGING).provider("africastalking")
                .eventId("sms-sample-0002").eventType("SubscriptionNotification")
                .status(WebhookStatus.IGNORED)
                .payload("{\"id\":\"sms-sample-0002\",\"type\":\"SubscriptionNotification\"}")
                .signatureValid(true)
                .receivedAt(cat.daysAgo(4)).processedAt(cat.daysAgo(4)).attempts(1)
                .outcome("No handler for this event type; stored and ignored.")
                .build());
        cat.save(WebhookEvent.builder()
                .kind(WebhookKind.KYC).provider("smile-identity")
                .eventId("kyc-sample-0001").eventType("job.complete")
                .status(WebhookStatus.RECEIVED)
                .payload("{\"id\":\"kyc-sample-0001\",\"result\":\"Approved\","
                        + "\"partner_params\":{\"vendorSlug\":\"kerewan-crafts\"}}")
                .signatureValid(true)
                .providerTimestamp(cat.hoursAgo(1)).receivedAt(cat.hoursAgo(1)).attempts(0)
                .subjectReference("kerewan-crafts")
                .build());
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    /**
     * The answers already given, kept so a retried request gets the same one.
     *
     * <p>A buyer whose connection drops mid-checkout presses the button again.
     * Without these rows that is a second order and a second charge; with them
     * the stored response comes straight back. The fingerprint is what stops the
     * key being reused for a different request.
     */
    private void idempotencyRecords(SeedCatalogue cat) {
        idempotency(cat, "checkout", "idem-sample-isatou-1001",
                "9a1b4c7e2f5d8a0b9a1b4c7e2f5d8a0b9a1b4c7e2f5d8a0b9a1b4c7e2f5d8a0b",
                "{\"orderNumber\":\"SJL-1001\",\"total\":116.81,\"currency\":\"EUR\"}",
                201, cat.daysAgo(12), cat.daysAgo(12).plusHours(24));
        idempotency(cat, "checkout", "idem-sample-isatou-1002",
                "1b2c5d8f3a6e9b1c1b2c5d8f3a6e9b1c1b2c5d8f3a6e9b1c1b2c5d8f3a6e9b1c",
                "{\"orderNumber\":\"SJL-1002\",\"total\":263.30,\"currency\":\"EUR\"}",
                201, cat.daysAgo(4), cat.daysAhead(20));
        idempotency(cat, "checkout", "idem-sample-guest-1008",
                "2c3d6e9a4b7f0c2d2c3d6e9a4b7f0c2d2c3d6e9a4b7f0c2d2c3d6e9a4b7f0c2d",
                "{\"orderNumber\":\"SJL-1008\",\"total\":8650.00,\"currency\":\"GMD\"}",
                201, cat.hoursAgo(6), cat.hoursAhead(18));
        idempotency(cat, "refund", "idem-sample-refund-1006",
                "3d4e7f0b5c8a1d3e3d4e7f0b5c8a1d3e3d4e7f0b5c8a1d3e3d4e7f0b5c8a1d3e",
                "{\"reference\":\"RFD-2024-000001\",\"amount\":12500,\"currency\":\"XOF\"}",
                200, cat.daysAgo(10), cat.daysAhead(14));
        // A 4xx, stored deliberately. Replaying a rejected request must get the
        // same rejection rather than a second chance at succeeding.
        idempotency(cat, "checkout", "idem-sample-yankuba-declined",
                "4e5f8a1c6d9b2e4f4e5f8a1c6d9b2e4f4e5f8a1c6d9b2e4f4e5f8a1c6d9b2e4f",
                "{\"error\":\"PAYMENT_DECLINED\",\"message\":\"The card issuer declined it.\"}",
                402, cat.daysAgo(1), cat.daysAhead(23));
        // Already past its expiry. The cleanup job's input.
        idempotency(cat, "payout-request", "idem-sample-payout-expired",
                "5f6a9b2d7e0c3f5a5f6a9b2d7e0c3f5a5f6a9b2d7e0c3f5a5f6a9b2d7e0c3f5a",
                "{\"reference\":\"PO-2024-0001\",\"status\":\"COMPLETED\"}",
                200, cat.daysAgo(31), cat.daysAgo(30));
        // Same key, a different scope. The unique constraint is on the pair, and
        // this row is what proves it.
        idempotency(cat, "cart-merge", "idem-sample-isatou-1001",
                "6a7b0c3e8f1d4a6b6a7b0c3e8f1d4a6b6a7b0c3e8f1d4a6b6a7b0c3e8f1d4a6b",
                "{\"cartId\":1,\"merged\":true}",
                200, cat.daysAgo(12), cat.daysAhead(12));
    }

    private void idempotency(SeedCatalogue cat, String scope, String key, String fingerprint,
                             String body, int status, LocalDateTime createdAt,
                             LocalDateTime expiresAt) {
        cat.save(IdempotencyRecord.builder()
                .scope(scope).idempotencyKey(key).requestFingerprint(fingerprint)
                .responseBody(body).responseStatus(status)
                .createdAt(createdAt).expiresAt(expiresAt)
                .build());
    }

    // ── Audit trail ──────────────────────────────────────────────────────────

    /**
     * Who did what, with their name and address frozen into the row.
     *
     * <p>The actor's email and name are copied rather than joined, because the
     * point of an audit entry is to still read correctly after the account has
     * been renamed, disabled or erased. A trail that resolves through a foreign
     * key stops being a trail the moment somebody exercises their right to be
     * forgotten.
     */
    private void auditLog(SeedCatalogue cat) {
        User admin = cat.user("admin");
        User support = cat.user("support");

        audit(cat, admin, AuditAction.ADMIN_BOOTSTRAPPED, "USER", admin.getId(),
                "admin@sujula.gm", "Administrator account created on first start.", null);
        audit(cat, admin, AuditAction.STORE_APPROVED, "VENDOR",
                cat.vendor("banjul-phones").getId(), "Banjul Phones",
                "Store approved after KYC.", "Registration and national ID both accepted.");
        audit(cat, admin, AuditAction.STORE_COMMISSION_CHANGED, "VENDOR",
                cat.vendor("banjul-phones").getId(), "Banjul Phones",
                "Commission changed from 10% to 8%.",
                "Store passed 200 completed orders. Effective immediately.");
        audit(cat, admin, AuditAction.STORE_SUSPENDED, "VENDOR",
                cat.vendor("kololi-style").getId(), "Kololi Style",
                "Store suspended and payouts held.",
                "Three open non-delivery cases. Case MOD-000002.");
        audit(cat, admin, AuditAction.STORE_REJECTED, "VENDOR",
                cat.vendor("farafenni-foods").getId(), "Farafenni Foods",
                "Application refused.",
                "Registration number belongs to a different business.");
        audit(cat, admin, AuditAction.KYC_REJECTED, "VENDOR",
                cat.vendor("farafenni-foods").getId(), "Farafenni Foods",
                "Business registration rejected.", null);
        audit(cat, admin, AuditAction.PRODUCT_APPROVED, "PRODUCT",
                cat.product("spark10").getId(), "Tecno Spark 10",
                "Listing approved and published.", null);
        audit(cat, admin, AuditAction.PRODUCT_REJECTED, "PRODUCT",
                cat.product("bonga").getId(), "Dried bonga fish, 1 kg",
                "Listing refused.",
                "Perishable food needs a handling certificate on file first.");
        audit(cat, admin, AuditAction.PRODUCT_SUSPENDED, "PRODUCT",
                cat.product("waxfabric").getId(), "Wax print fabric, 6 yards",
                "Listing suspended pending case MOD-000001.", null);
        audit(cat, admin, AuditAction.USER_BLOCKED, "USER",
                cat.user("baboucarr").getId(), "Baboucarr Jatta",
                "Account blocked.", "Three accounts on one identity document.");
        audit(cat, admin, AuditAction.USER_FRAUD_FLAGGED, "USER",
                cat.user("fanta").getId(), "Fanta Kanteh",
                "Flagged for review manipulation.", null);
        audit(cat, support, AuditAction.USER_IMPERSONATED, "USER",
                cat.user("isatou").getId(), "Isatou Ceesay",
                "Support opened a session as this buyer.",
                "Buyer could not see the tracking page for SJL-1004.");
        audit(cat, support, AuditAction.USER_IMPERSONATION_ENDED, "USER",
                cat.user("isatou").getId(), "Isatou Ceesay",
                "Impersonated session ended.", null);
        audit(cat, admin, AuditAction.SANCTION_ISSUED, "USER",
                cat.user("lamin").getId(), "Lamin Sanneh",
                "Suspension issued for thirty days.", "Case MOD-000002.");
        audit(cat, support, AuditAction.SANCTION_LIFTED, "USER",
                cat.user("yankuba").getId(), "Yankuba Bah",
                "Messaging restriction lifted early.",
                "First offence and the messages never reached the seller.");
        audit(cat, support, AuditAction.DISPUTE_RESOLVED, "DISPUTE",
                cat.dispute("resolved-buyer").getId(), "DSP-000001",
                "Resolved for the buyer; full refund.", null);
        audit(cat, support, AuditAction.DISPUTE_CALLBACK_RECORDED, "DISPUTE",
                cat.dispute("resolved-buyer").getId(), "DSP-000001",
                "Callback recorded: spoke to the buyer.", null);
        audit(cat, support, AuditAction.PAYMENT_TRANSFER_CONFIRMED, "PAYMENT",
                null, "PAY-SJL-1004",
                "Bank transfer confirmed against the statement.", "Reference TBG-TRF-88410.");
        audit(cat, admin, AuditAction.PAYOUT_BATCH_APPROVED, "PAYOUT_BATCH", null,
                "PB-2024-0003", "Batch approved for settlement.",
                "Prepared by support; approved by a different person, as required.");
        audit(cat, admin, AuditAction.PAYOUT_BATCH_CANCELLED, "PAYOUT_BATCH", null,
                "PB-2024-0006", "Batch cancelled before settlement.",
                "Built against the wrong period.");
        audit(cat, admin, AuditAction.FX_SPREAD_CHANGED, "FX_SPREAD", null, "GMD→EUR",
                "Spread narrowed from 175 to 90 basis points.", null);
        audit(cat, admin, AuditAction.FX_REFRESHED, "EXCHANGE_RATE", null, "All pairs",
                "Rates refreshed by hand.", null);
        audit(cat, admin, AuditAction.FEATURE_FLAG_CHANGED, "FEATURE_FLAG", null,
                FeatureFlags.VENDOR_SIGNUP,
                "Seller applications switched off.", "Paused while the KYC queue is cleared.");
        audit(cat, admin, AuditAction.ZONE_CREATED, "DELIVERY_ZONE",
                cat.zone("thies").getId(), "SN-THIES",
                "Thiès zone created.", null);
        audit(cat, admin, AuditAction.RATE_CARD_CHANGED, "RATE_CARD", null,
                "Banjul — home delivery",
                "Fuel surcharge card ended.", "Fuel price back to its previous level.");
        audit(cat, admin, AuditAction.DRIVER_APPROVED, "DRIVER",
                cat.driver("jainaba").getId(), "Jainaba Drammeh",
                "Driver approved for the Banjul island zone.", null);
        audit(cat, admin, AuditAction.DRIVER_SUSPENDED, "DRIVER",
                cat.driver("aminata").getId(), "Aminata Sarr",
                "Driver suspended.", "Two parcels released without a code. Case MOD-000008.");
        audit(cat, admin, AuditAction.PICKUP_POINT_SUSPENDED, "PICKUP_POINT",
                cat.pickupPoint("thies-gare").getId(), "Thiès Gare Routière Boutique",
                "Pickup point suspended.", "Parcels released without codes.");
        audit(cat, admin, AuditAction.ANNOUNCEMENT_SENT, "ANNOUNCEMENT", null, "ANN-2024-0002",
                "Announcement sent to all sellers.", "Reached 7 of 7.");
        audit(cat, admin, AuditAction.JOB_TRIGGERED, "JOB", null, "fx-refresh",
                "Job triggered by hand from the back office.", null);
        audit(cat, admin, AuditAction.REPORT_EXPORTED, "REPORT_EXPORT", null, "REX-2024-000001",
                "Ledger export built.", "1 842 rows, GMD, last month.");
        // No actor at all: the system did it. The email and name columns carry
        // that rather than leaving the row looking like somebody's work.
        cat.save(AuditLog.builder()
                .actorName("System")
                .action(AuditAction.VENDOR_ORDER_STATUS_FORCED)
                .targetType("VENDOR_ORDER").targetId(cat.vendorOrder("1010-bp").getId())
                .targetLabel("SJL-1010 — Banjul Phones")
                .summary("Sub-order left pending after the payment window closed.")
                .details("No person acted; the scheduler did.")
                .build());
    }

    private void audit(SeedCatalogue cat, User actor, AuditAction action, String targetType,
                       Long targetId, String targetLabel, String summary, String details) {
        cat.save(AuditLog.builder()
                .actor(actor)
                .actorEmail(actor.getEmail()).actorName(actor.getFullName())
                .action(action)
                .targetType(targetType).targetId(targetId).targetLabel(targetLabel)
                .summary(summary).details(details)
                .ipAddress("203.0.113." + (Math.abs(summary.hashCode()) % 200 + 10))
                .build());
    }
}
