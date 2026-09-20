package com.sujula.config.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

/**
 * Boots the application with the sample data switched on and checks what landed.
 *
 * <p>Two things are being asserted, and the second is the one that keeps
 * working over time. The first is that the seeder runs at all: it writes across
 * every table in one transaction, so a column that moved, a constraint that
 * tightened or an association that was re-pointed fails here rather than on
 * somebody's machine at a demonstration.
 *
 * <p>The second is coverage. The list of entities is read from Hibernate's own
 * metamodel rather than written down, so adding an entity to the application and
 * forgetting to seed it fails this test by construction. That is the property
 * worth having: a dataset that silently stops being complete is worse than one
 * that was never complete, because people go on trusting it.
 */
@SpringBootTest
@ActiveProfiles({"test", "sample"})
// Closed as soon as this class is finished rather than left in Spring's context
// cache. This one holds a database with nine hundred rows in it and is shared
// with nothing — the profile pair is unique to this class — so keeping it alive
// for the rest of the suite costs memory and a cache slot for no benefit, and
// pushing the cache past its limit evicts contexts other tests are still using.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Sample data")
class SampleDataSeederTest {

    /**
     * The floor every table has to clear.
     *
     * <p>Five is the point below which a table stops being able to show
     * anything: you cannot demonstrate ordering, paging, filtering or a status
     * breakdown with three rows.
     */
    private static final int MINIMUM_ROWS = 5;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("writes at least five rows for every entity in the model")
    void everyEntityIsPopulated() {
        Map<String, Long> counts = countEveryEntity();

        Map<String, Long> thin = new TreeMap<>();
        counts.forEach((entity, count) -> {
            if (count < MINIMUM_ROWS) {
                thin.put(entity, count);
            }
        });

        assertThat(thin)
                .as("every entity should carry at least %d sample rows; these do not: %s",
                        MINIMUM_ROWS, thin)
                .isEmpty();
    }

    @Test
    @DisplayName("covers the states that matter on the entities with states")
    void statesAreCovered() {
        // Orders, sub-orders, payments and shipments are where the branches are.
        // Counting distinct values rather than naming them keeps this honest
        // when a new constant is added: the assertion is against the enum's own
        // size, so a status nobody seeded fails here.
        assertDistinct("select count(distinct o.status) from Order o",
                com.sujula.model.constant.OrderStatus.values().length, "OrderStatus");
        assertDistinct("select count(distinct v.status) from VendorOrder v",
                com.sujula.model.constant.VendorOrderStatus.values().length, "VendorOrderStatus");
        assertDistinct("select count(distinct p.status) from Payment p",
                com.sujula.model.constant.PaymentStatus.values().length, "PaymentStatus");
        assertDistinct("select count(distinct p.method) from Payment p",
                com.sujula.model.constant.PaymentMethod.values().length, "PaymentMethod");
        assertDistinct("select count(distinct s.status) from Shipment s",
                com.sujula.model.constant.ShipmentStatus.values().length, "ShipmentStatus");
        assertDistinct("select count(distinct l.assignmentStatus) from ShipmentLeg l",
                com.sujula.model.constant.LegAssignmentStatus.values().length,
                "LegAssignmentStatus");
        assertDistinct("select count(distinct l.legType) from ShipmentLeg l",
                com.sujula.model.constant.LegType.values().length, "LegType");
        assertDistinct("select count(distinct e.type) from CustodyEvent e",
                com.sujula.model.constant.CustodyEventType.values().length, "CustodyEventType");
        assertDistinct("select count(distinct d.status) from Delivery d",
                com.sujula.model.constant.DeliveryStatus.values().length, "DeliveryStatus");
        assertDistinct("select count(distinct p.status) from Product p",
                com.sujula.model.constant.ProductStatus.values().length, "ProductStatus");
        assertDistinct("select count(distinct u.role) from User u",
                com.sujula.model.constant.UserRole.values().length, "UserRole");
        assertDistinct("select count(distinct v.status) from Vendor v",
                com.sujula.model.constant.PartnerStatus.values().length, "PartnerStatus");
        assertDistinct("select count(distinct d.status) from Driver d",
                com.sujula.model.constant.DriverStatus.values().length, "DriverStatus");
        assertDistinct("select count(distinct r.status) from ReturnRequest r",
                com.sujula.model.constant.ReturnStatus.values().length, "ReturnStatus");
        assertDistinct("select count(distinct d.status) from Dispute d",
                com.sujula.model.constant.DisputeStatus.values().length, "DisputeStatus");
        assertDistinct("select count(distinct r.status) from RefundRequest r",
                com.sujula.model.constant.RefundRequestStatus.values().length,
                "RefundRequestStatus");
        assertDistinct("select count(distinct p.status) from Payout p",
                com.sujula.model.constant.PayoutStatus.values().length, "PayoutStatus");
        assertDistinct("select count(distinct b.status) from PayoutBatch b",
                com.sujula.model.constant.PayoutBatchStatus.values().length, "PayoutBatchStatus");
        assertDistinct("select count(distinct e.type) from VendorLedgerEntry e",
                com.sujula.model.constant.LedgerEntryType.values().length, "LedgerEntryType");
        assertDistinct("select count(distinct m.reason) from StockMovement m",
                com.sujula.model.constant.StockMovementReason.values().length,
                "StockMovementReason");
        assertDistinct("select count(distinct i.status) from ImeiUnit i",
                com.sujula.model.constant.ImeiStatus.values().length, "ImeiStatus");
        assertDistinct("select count(distinct w.status) from WebhookEvent w",
                com.sujula.model.constant.WebhookStatus.values().length, "WebhookStatus");
        assertDistinct("select count(distinct j.status) from JobRun j",
                com.sujula.model.constant.JobRunStatus.values().length, "JobRunStatus");
        assertDistinct("select count(distinct c.status) from CatalogueJob c",
                com.sujula.model.constant.CatalogueJobStatus.values().length,
                "CatalogueJobStatus");
        assertDistinct("select count(distinct m.status) from ModerationCase m",
                com.sujula.model.constant.ModerationCaseStatus.values().length,
                "ModerationCaseStatus");
        assertDistinct("select count(distinct s.type) from Sanction s",
                com.sujula.model.constant.SanctionType.values().length, "SanctionType");
    }

    @Test
    @DisplayName("holds the two-location, two-currency, many-vendor order the rules describe")
    void theShapeOfTheMarketplaceIsPresent() {
        // C3: one payment, two sellers, two settlement currencies.
        Long multiVendor = em.createQuery(
                "select count(distinct v.vendor.id) from VendorOrder v "
                        + "where v.order.orderNumber = 'SJL-1002'", Long.class).getSingleResult();
        assertThat(multiVendor)
                .as("SJL-1002 should split across two sellers")
                .isEqualTo(2L);

        Long settlementCurrencies = em.createQuery(
                "select count(distinct v.nativeCurrency) from VendorOrder v "
                        + "where v.order.orderNumber = 'SJL-1002'", Long.class).getSingleResult();
        assertThat(settlementCurrencies)
                .as("its two sub-orders should settle in two different currencies")
                .isEqualTo(2L);

        // C1: the payer is in Spain and the parcel goes to the Gambia.
        Object[] locations = em.createQuery(
                "select o.billingCountry, o.shippingCountry from Order o "
                        + "where o.orderNumber = 'SJL-1002'", Object[].class).getSingleResult();
        assertThat(locations[0]).isEqualTo("ES");
        assertThat(locations[1]).isEqualTo("GM");

        // C2: every converted sub-order carries the rate it was converted at.
        Long unsnapshotted = em.createQuery(
                "select count(v) from VendorOrder v "
                        + "where v.fx.rate is null", Long.class).getSingleResult();
        assertThat(unsnapshotted)
                .as("no sub-order should carry a converted total with no rate behind it")
                .isZero();

        // C4: nothing reaches DELIVERED without custody events under it.
        Long deliveredWithoutEvents = em.createQuery(
                "select count(s) from Shipment s where s.status = "
                        + "com.sujula.model.constant.ShipmentStatus.DELIVERED "
                        + "and not exists (select e from CustodyEvent e where e.shipment = s)",
                Long.class).getSingleResult();
        assertThat(deliveredWithoutEvents)
                .as("a delivered shipment with no custody events is a chain with a hole in it")
                .isZero();

        // C5: the codes go to phone numbers, and the recipients have no accounts.
        Long codesToPhones = em.createQuery(
                "select count(c) from ParcelAccessCode c where c.sentTo is not null",
                Long.class).getSingleResult();
        assertThat(codesToPhones)
                .as("parcel codes should be addressed to a phone number")
                .isGreaterThanOrEqualTo(MINIMUM_ROWS);
    }

    private void assertDistinct(String jpql, int expected, String what) {
        Long distinct = em.createQuery(jpql, Long.class).getSingleResult();
        assertThat(distinct)
                .as("%s has %d constants; the sample data should use every one of them", what, expected)
                .isEqualTo((long) expected);
    }

    /** One count per mapped entity, read from Hibernate's own metamodel. */
    private Map<String, Long> countEveryEntity() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EntityType<?> entity : em.getMetamodel().getEntities()) {
            String name = entity.getName();
            Long count = em.createQuery("select count(e) from " + name + " e", Long.class)
                    .getSingleResult();
            counts.put(name, count);
        }
        return counts;
    }
}
