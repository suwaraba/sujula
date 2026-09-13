package com.sujula.service.vendorcatalogue;

import com.sujula.model.catalogue.CatalogueJob;
import com.sujula.model.catalogue.CatalogueJobError;
import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.model.constant.CatalogueJobType;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.constant.ProductStatus;
import com.sujula.model.products.Brand;
import com.sujula.model.products.Category;
import com.sujula.model.products.Product;
import com.sujula.model.user.Vendor;
import com.sujula.repository.catalogue.CatalogueJobErrorRepository;
import com.sujula.repository.catalogue.CatalogueJobRepository;
import com.sujula.repository.product.BrandRepository;
import com.sujula.repository.product.CategoryRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.service.StorageService;
import com.sujula.service.vendorcatalogue.impl.CatalogueJobProcessor;
import com.sujula.service.vendorcatalogue.impl.SpreadsheetReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reading a seller's spreadsheet into their catalogue.
 *
 * <p>Two promises are worth testing here and nothing else much is. The first is
 * that a bad row fails alone: a seller with four hundred products and one typo
 * gets 399 listings and one error, not a rejected file. The second is that an
 * import cannot publish - otherwise "upload a spreadsheet" is the way past
 * moderation, and the whole review queue is decorative.
 */
class CatalogueJobProcessorTest {

    private static final Long VENDOR = 6100L;
    private static final Long JOB = 8000L;

    private CatalogueJobRepository jobs;
    private CatalogueJobErrorRepository jobErrors;
    private ProductRepository products;
    private CategoryRepository categories;
    private BrandRepository brands;
    private StorageService storage;
    private CatalogueJobProcessor processor;

    private CatalogueJob job;

    @BeforeEach
    void setUp() {
        jobs = mock(CatalogueJobRepository.class);
        jobErrors = mock(CatalogueJobErrorRepository.class);
        products = mock(ProductRepository.class);
        categories = mock(CategoryRepository.class);
        brands = mock(BrandRepository.class);
        storage = mock(StorageService.class);

        processor = new CatalogueJobProcessor(jobs, jobErrors, products, categories, brands,
                storage, new SpreadsheetReader(), new ProductLifecycle());

        ReflectionTestUtils.setField(processor, "maxRecordedErrors", 200);
        ReflectionTestUtils.setField(processor, "exportTtl", Duration.ofDays(7));

        job = CatalogueJob.builder()
                .id(JOB).reference("IMP-TEST").vendor(vendor())
                .type(CatalogueJobType.IMPORT).status(CatalogueJobStatus.QUEUED)
                .sourceUrl("imports/catalogue.csv")
                .errors(new ArrayList<>())
                .build();

        when(jobs.findById(JOB)).thenReturn(Optional.of(job));
        when(jobs.save(any(CatalogueJob.class))).thenAnswer(call -> call.getArgument(0));
        when(products.save(any(Product.class))).thenAnswer(call -> call.getArgument(0));
        when(products.existsBySkuIgnoreCaseAndVendorId(anyString(), anyLong())).thenReturn(false);
        when(categories.findAll()).thenReturn(List.of(category("Phones", "phones")));
        when(brands.findAll()).thenReturn(List.of());
    }

    private static Vendor vendor() {
        return Vendor.builder()
                .id(VENDOR).status(PartnerStatus.APPROVED)
                .settlementCurrency("GMD").addressCountryCode("GM")
                .latitude(13.4383).longitude(-16.6781)
                .build();
    }

    private static Category category(String name, String slug) {
        Category category = new Category();
        category.setId(500L);
        category.setName(name);
        category.setSlug(slug);
        return category;
    }

    private void upload(String csv) {
        when(storage.download("imports/catalogue.csv"))
                .thenReturn(Optional.of(csv.getBytes(StandardCharsets.UTF_8)));
    }

    // -- The two promises ----------------------------------------------------

    @Test
    void aBadRowFailsAloneAndTheRestGoIn() {
        upload("""
                name,price,category
                Kettle,450,Phones
                Broken,not-a-price,Phones
                Speaker,1200,Phones
                """);

        processor.runOne(JOB);

        assertEquals(CatalogueJobStatus.COMPLETED_WITH_ERRORS, job.getStatus());
        assertEquals(3, job.getTotalRows());
        assertEquals(2, job.getSucceededRows());
        assertEquals(1, job.getFailedRows());
        verify(products, org.mockito.Mockito.times(2)).save(any(Product.class));
    }

    @Test
    void everyImportedRowIsADraftWhateverTheFileSays() {
        // A status column is deliberately not a thing an import reads. If it
        // were, "upload a spreadsheet" would be the way past moderation.
        upload("""
                name,price,status,active
                Kettle,450,PUBLISHED,true
                """);

        processor.runOne(JOB);

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).save(saved.capture());

        assertEquals(ProductStatus.DRAFT, saved.getValue().getStatus());
        assertFalse(saved.getValue().isActive());
    }

    // -- What the seller is told ---------------------------------------------

    @Test
    void aRowErrorNamesTheRowAsTheirSpreadsheetNumbersIt() {
        upload("""
                name,price
                Kettle,450
                Broken,nonsense
                """);

        processor.runOne(JOB);

        CatalogueJobError error = capturedErrors().get(0);
        // Header is row 1, so the second data row is row 3 - which is the number
        // their spreadsheet shows them. Any other convention makes them count.
        assertEquals(3, error.getRowNumber());
        assertEquals("price", error.getField());
    }

    @Test
    void andQuotesTheirOwnTextBackAtThem() {
        upload("""
                name,price,category
                Kettle,450,Phonez
                """);

        processor.runOne(JOB);

        CatalogueJobError error = capturedErrors().get(0);
        // "Category does not exist" is half an answer. This is the whole one,
        // and it is the difference between a five-minute fix and giving up.
        assertTrue(error.getMessage().contains("Phonez"), error.getMessage());
        assertEquals("Phonez", error.getValue());
        assertEquals("category", error.getField());
    }

    @Test
    void aDuplicateCodeIsRejectedWithTheCodeInTheMessage() {
        when(products.existsBySkuIgnoreCaseAndVendorId("KET-01", VENDOR)).thenReturn(true);
        upload("""
                name,price,sku
                Kettle,450,KET-01
                """);

        processor.runOne(JOB);

        assertEquals(1, job.getFailedRows());
        assertTrue(capturedErrors().get(0).getMessage().contains("KET-01"));
    }

    @Test
    void anUnknownConditionListsTheOnesThatWork() {
        upload("""
                name,price,condition
                Kettle,450,slightly-dented
                """);

        processor.runOne(JOB);

        String message = capturedErrors().get(0).getMessage();
        assertTrue(message.contains("NEW"), message);
    }

    // -- Whole-file failures, which are a different thing ---------------------

    @Test
    void aFileThatIsNotThereFailsTheJobRatherThanEveryRow() {
        when(storage.download(anyString())).thenReturn(Optional.empty());

        processor.runOne(JOB);

        assertEquals(CatalogueJobStatus.FAILED, job.getStatus());
        assertTrue(job.getFailureReason().contains("upload"), job.getFailureReason());
        verify(products, never()).save(any());
    }

    @Test
    void aFileWithNoPriceColumnSaysSoOnceRatherThanOncePerRow() {
        upload("""
                name,description
                Kettle,Nice
                Speaker,Loud
                """);

        processor.runOne(JOB);

        // Four hundred identical row errors would be worse than useless.
        assertEquals(CatalogueJobStatus.FAILED, job.getStatus());
        assertTrue(job.getFailureReason().contains("price"), job.getFailureReason());
        verify(jobErrors, never()).saveAll(any());
    }

    @Test
    void aCleanFileCompletesWithoutQualification() {
        upload("""
                name,price,category,stock
                Kettle,450,Phones,12
                Speaker,"1,200",phones,3
                """);

        processor.runOne(JOB);

        assertEquals(CatalogueJobStatus.COMPLETED, job.getStatus());
        assertEquals(2, job.getSucceededRows());
        assertEquals(0, job.getFailedRows());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    void theListingCurrencyComesFromTheStoreAndNotTheSpreadsheet() {
        upload("""
                name,price,currency,price_currency
                Kettle,450,EUR,EUR
                """);

        processor.runOne(JOB);

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(products).save(saved.capture());

        // A currency column would put an unsnapshotted conversion between what
        // a buyer is charged and what the seller is owed.
        assertEquals("GMD", saved.getValue().getPriceCurrency());
    }

    // -- Export --------------------------------------------------------------

    /**
     * The quoting is the mirror of the parser, so a file we write reads back.
     */
    @Test
    void anExportQuotesFieldsSoACommaDoesNotShiftEveryColumn() {
        assertEquals("\"Six yards, cut to order\"",
                SpreadsheetReader.csvCell("Six yards, cut to order"));
        assertEquals("\"A 32\"\" screen\"", SpreadsheetReader.csvCell("A 32\" screen"));
        assertEquals("Kettle", SpreadsheetReader.csvCell("Kettle"));
        assertEquals("", SpreadsheetReader.csvCell(null));
    }

    @Test
    void anExportWritesAFileAndHandsBackALinkThatExpires() {
        job.setType(CatalogueJobType.EXPORT);
        job.setOriginalFilename("current");
        when(products.findAllForExport(anyLong(), anyBoolean())).thenReturn(List.of(
                Product.builder().id(1L).name("Kettle").sku("KET-01")
                        .price(new java.math.BigDecimal("450.00")).priceCurrency("GMD")
                        .status(ProductStatus.PUBLISHED).stock(12).build()));
        when(storage.upload(anyString(), anyString(), any(), anyString()))
                .thenReturn("https://storage.invalid/exports/imp-test.csv");

        processor.runOne(JOB);

        assertEquals(CatalogueJobStatus.COMPLETED, job.getStatus());
        assertEquals(1, job.getTotalRows());
        assertNotNull(job.getResultUrl());
        // A link that never expired would be a permanent, unauthenticated copy
        // of a seller's whole catalogue.
        assertNotNull(job.getResultExpiresAt());

        ArgumentCaptor<byte[]> written = ArgumentCaptor.forClass(byte[].class);
        verify(storage).upload(anyString(), anyString(), written.capture(), anyString());
        String csv = new String(written.getValue(), StandardCharsets.UTF_8);

        // A byte-order mark, deliberately: without it Excel opens a UTF-8 CSV
        // as the local code page and a Senegalese seller's accented names come
        // out as mojibake.
        assertTrue(csv.startsWith("﻿"), "no BOM");
        assertTrue(csv.contains("KET-01"), csv);
    }

    @SuppressWarnings("unchecked")
    private List<CatalogueJobError> capturedErrors() {
        ArgumentCaptor<List<CatalogueJobError>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobErrors).saveAll(captor.capture());
        return captor.getValue();
    }
}
