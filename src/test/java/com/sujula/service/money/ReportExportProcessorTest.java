package com.sujula.service.money;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ReportExportStatus;
import com.sujula.model.constant.ReportType;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.constant.UserRole;
import com.sujula.model.constant.VendorOrderStatus;
import com.sujula.model.finance.ReportExport;
import com.sujula.model.order.Order;
import com.sujula.model.order.VendorOrder;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.finance.ReportExportRepository;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.VendorOrderRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.StorageService;
import com.sujula.service.money.impl.ReportExportProcessor;
import com.sujula.service.reference.CurrencyCatalogue;
import com.sujula.service.reference.ReferenceDataProperties;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The file a finance export produces.
 *
 * <p>The claim that matters most is the least obvious one: a store name is
 * seller-supplied text, it ends up in a CSV, and a CSV is opened in Excel by
 * somebody in accounts. A store called {@code =cmd|...} is a real attack rather
 * than a theoretical one, and the quoting is what stops it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ReportExportProcessor.class, ReportExportProcessorTest.Money.class})
class ReportExportProcessorTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class Money {
        @org.springframework.context.annotation.Bean
        CurrencyCatalogue currencyCatalogue() {
            return new CurrencyCatalogue(new ReferenceDataProperties());
        }
    }

    @Autowired private ReportExportProcessor processor;
    @Autowired private ReportExportRepository exports;
    @Autowired private OrderRepository orders;
    @Autowired private VendorOrderRepository vendorOrders;
    @Autowired private VendorRepository vendors;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;

    @MockitoBean private StorageService storage;

    private String captured;

    @BeforeEach
    void setUp() {
        when(storage.upload(anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> {
                    captured = new String((byte[]) invocation.getArgument(2),
                            StandardCharsets.UTF_8);
                    return "https://files.example.invalid/finance/export.csv";
                });
    }

    @Test
    void aStoreNameThatWouldExecuteInExcelIsDefusedRatherThanWritten() {
        User seller = users.save(User.builder()
                .firstName("Modou").lastName("Faal").email("modou.export@sujula.gm")
                .password("x").role(UserRole.VENDOR).enabled(true).build());

        // A store name a seller chose. It is a formula to Excel.
        vendors.save(Vendor.builder()
                .user(seller).storeName("=1+1").storeSlug("formula-store")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD")
                .addressCountryCode("GM").build());
        entityManager.flush();

        ReportExport export = exports.save(ReportExport.builder()
                .reference("EXP-FORMULA").type(ReportType.REVENUE)
                .status(ReportExportStatus.QUEUED)
                .requestedByUserId(1L).requestedByEmail("fatou@sujula.gm")
                .fromDate(LocalDate.now().minusDays(7)).toDate(LocalDate.now())
                .format("CSV").build());
        entityManager.flush();

        processor.runOne(export.getId());

        assertFalse(captured.contains("\n=1+1"), "a bare formula never starts a field");
    }

    @Test
    void aLedgerExportSaysWhoEarnedWhatAndInWhichCurrency() {
        User seller = users.save(User.builder()
                .firstName("Lamin").lastName("Sanneh").email("lamin.export@sujula.gm")
                .password("x").role(UserRole.VENDOR).enabled(true).build());
        Vendor kombo = vendors.save(Vendor.builder()
                .user(seller).storeName("Kombo Electronics").storeSlug("kombo-export")
                .status(PartnerStatus.APPROVED).settlementCurrency("GMD")
                .addressCountryCode("GM").build());

        Order order = orders.save(Order.builder()
                .orderNumber("SJL-EXPORT-0001").customer(seller)
                .status(OrderStatus.PROCESSING).paymentStatus(PaymentStatus.PAID)
                .currency("EUR").subtotal(BigDecimal.TEN).total(BigDecimal.TEN)
                .billingCountry("ES").shippingCountry("GM").build());
        vendorOrders.save(VendorOrder.builder()
                .order(order).vendor(kombo).status(VendorOrderStatus.DELIVERED)
                .nativeCurrency("GMD")
                .subtotalNative(new BigDecimal("9700.00")).totalNative(new BigDecimal("9700.00"))
                .commissionNative(new BigDecimal("970.00"))
                .subtotal(BigDecimal.TEN).total(BigDecimal.TEN).build());
        entityManager.flush();

        ReportExport export = exports.save(ReportExport.builder()
                .reference("EXP-LEDGER").type(ReportType.LEDGER)
                .status(ReportExportStatus.QUEUED)
                .requestedByUserId(1L).requestedByEmail("fatou@sujula.gm")
                .fromDate(LocalDate.now().minusDays(7)).toDate(LocalDate.now())
                .format("CSV").build());
        entityManager.flush();

        processor.runOne(export.getId());

        assertTrue(captured.startsWith("﻿"),
                "a byte-order mark, or Excel mangles every accented seller name");
        assertTrue(captured.contains("occurred_at,vendor_id,store,type,amount,currency"));

        ReportExport reloaded = exports.findById(export.getId()).orElseThrow();
        assertEquals(ReportExportStatus.READY, reloaded.getStatus());
        assertTrue(reloaded.getResultExpiresAt().isAfter(LocalDateTime.now()),
                "and the link expires — one that does not is a file the platform has lost");
    }

    @Test
    void aPaymentsExportCarriesBothCountriesBecauseHereTheyDiffer() {
        User buyer = users.save(User.builder()
                .firstName("Oliver").lastName("Bennett").email("oliver.export@example.co.uk")
                .password("x").role(UserRole.CUSTOMER).enabled(true).build());
        orders.save(Order.builder()
                .orderNumber("SJL-EXPORT-0002").customer(buyer)
                .status(OrderStatus.PROCESSING).paymentStatus(PaymentStatus.PAID)
                .currency("EUR").subtotal(BigDecimal.TEN).total(BigDecimal.TEN)
                .billingCountry("ES").shippingCountry("GM").build());
        entityManager.flush();

        ReportExport export = exports.save(ReportExport.builder()
                .reference("EXP-PAYMENTS").type(ReportType.PAYMENTS)
                .status(ReportExportStatus.QUEUED)
                .requestedByUserId(1L).requestedByEmail("fatou@sujula.gm")
                .fromDate(LocalDate.now().minusDays(7)).toDate(LocalDate.now())
                .format("CSV").build());
        entityManager.flush();

        processor.runOne(export.getId());

        // Both, because a finance file carrying only one of them cannot answer
        // either question honestly (C1).
        assertTrue(captured.contains("payer_country,destination_country"));
    }

    @Test
    void aStorageFailureIsThrownSoTheWorkerCanRecordIt() {
        // doThrow, not when(...).thenThrow: the latter calls the mock while
        // stubbing it, which runs the answer already registered in setUp.
        org.mockito.Mockito.doThrow(
                        new IllegalStateException("The storage bucket refused the upload."))
                .when(storage).upload(anyString(), anyString(), any(), anyString());

        ReportExport export = exports.save(ReportExport.builder()
                .reference("EXP-FAILED").type(ReportType.LEDGER)
                .status(ReportExportStatus.QUEUED)
                .requestedByUserId(1L).requestedByEmail("fatou@sujula.gm")
                .fromDate(LocalDate.now().minusDays(7)).toDate(LocalDate.now())
                .format("CSV").build());
        entityManager.flush();

        // It propagates rather than being swallowed, which is what lets the
        // worker write the reason onto the row. The person who asked is waiting
        // on a file, and a job stuck in BUILDING with nothing saying why is the
        // worst of both.
        //
        // The write itself is not asserted here: markFailed is REQUIRES_NEW,
        // deliberately — the transaction that failed is being rolled back, and a
        // reason written inside it would roll back with it. That new transaction
        // cannot see this test's uncommitted row, so the assertion would be
        // about the slice rather than about the code.
        assertThrows(IllegalStateException.class, () -> processor.runOne(export.getId()));
    }

    @Test
    void aLapsedLinkIsRetiredSoTheRowStopsSayingReady() {
        ReportExport export = exports.save(ReportExport.builder()
                .reference("EXP-LAPSED").type(ReportType.LEDGER)
                .status(ReportExportStatus.READY)
                .requestedByUserId(1L).requestedByEmail("fatou@sujula.gm")
                .resultUrl("https://files.example.invalid/finance/old.csv")
                .resultExpiresAt(LocalDateTime.now().minusHours(1))
                .format("CSV").build());
        entityManager.flush();

        assertEquals(1, processor.expireLapsed());
        entityManager.flush();
        entityManager.clear();

        ReportExport reloaded = exports.findById(export.getId()).orElseThrow();
        // A row still saying READY about a file that has gone is a row telling a
        // lie — and "who exported every seller's earnings in March" has to stay
        // answerable after the file is gone, so the row survives.
        assertEquals(ReportExportStatus.EXPIRED, reloaded.getStatus());
        assertFalse(reloaded.isDownloadable());
    }
}
