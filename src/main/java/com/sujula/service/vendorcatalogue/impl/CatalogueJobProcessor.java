package com.sujula.service.vendorcatalogue.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.exceptions.BadRequestException;
import com.sujula.model.catalogue.CatalogueJob;
import com.sujula.model.catalogue.CatalogueJobError;
import com.sujula.model.constant.CatalogueJobStatus;
import com.sujula.model.constant.CatalogueJobType;
import com.sujula.model.constant.DeliveryScope;
import com.sujula.model.constant.ProductCondition;
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
import com.sujula.service.vendorcatalogue.ProductLifecycle;
import com.sujula.util.Utils;

import lombok.extern.slf4j.Slf4j;

/**
 * Does the work of a bulk job.
 *
 * <p>Separate from {@link CatalogueJobWorker} for a reason this codebase has
 * been bitten by twice: Spring's {@code @Transactional} is applied by a proxy,
 * and a proxy is not involved when an object calls its own method. A
 * {@code REQUIRES_NEW} on a method invoked as {@code this.runOne(...)} is inert,
 * so the "each row in its own transaction" guarantee would silently not exist
 * and one bad row would roll back the whole file. Splitting the scheduler from
 * the work puts a real proxy between them.
 *
 * <p><strong>Every imported row is a DRAFT.</strong> A bulk import that could
 * publish would be the way past moderation: upload four hundred rows, skip the
 * queue. What the seller gets is four hundred drafts and a list of the ones that
 * did not parse.
 */
@Slf4j
@Component
public class CatalogueJobProcessor {

    private final CatalogueJobRepository jobs;
    private final CatalogueJobErrorRepository jobErrors;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final StorageService storage;
    private final SpreadsheetReader spreadsheets;
    private final ProductLifecycle lifecycle;

    /** Beyond this, the seller gets a count rather than a list they cannot read. */
    @Value("${sujula.catalogue.max-recorded-row-errors:200}")
    private int maxRecordedErrors;

    @Value("${sujula.catalogue.export-link-ttl:7d}")
    private Duration exportTtl;

    public CatalogueJobProcessor(CatalogueJobRepository jobs, CatalogueJobErrorRepository jobErrors,
                                 ProductRepository products, CategoryRepository categories,
                                 BrandRepository brands, StorageService storage,
                                 SpreadsheetReader spreadsheets, ProductLifecycle lifecycle) {
        this.jobs = jobs;
        this.jobErrors = jobErrors;
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.storage = storage;
        this.spreadsheets = spreadsheets;
        this.lifecycle = lifecycle;
    }

    /**
     * Runs one job to completion.
     *
     * <p>{@code REQUIRES_NEW} so a failure here cannot roll back the worker's
     * own bookkeeping, and so the job's final state is committed whatever
     * happens next in the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void runOne(Long jobId) {
        CatalogueJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.isFinished()) {
            return;
        }

        job.setStatus(CatalogueJobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        jobs.save(job);

        if (job.getType() == CatalogueJobType.IMPORT) {
            runImport(job);
        } else {
            runExport(job);
        }

        job.setFinishedAt(LocalDateTime.now());
        jobs.save(job);
    }

    /** Records a failure the run itself could not record, in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long jobId, String reason) {
        jobs.findById(jobId).ifPresent(job -> {
            job.setStatus(CatalogueJobStatus.FAILED);
            job.setFailureReason(truncate(reason, 500));
            job.setFinishedAt(LocalDateTime.now());
            jobs.save(job);
        });
    }

    // -- Import --------------------------------------------------------------

    private void runImport(CatalogueJob job) {
        Vendor vendor = job.getVendor();

        byte[] content;
        SpreadsheetReader.Sheet sheet;
        try {
            content = storage.download(job.getSourceUrl()).orElseThrow(() -> new BadRequestException(
                    "That file is not in storage. The upload may not have finished - try again."));
            String format = spreadsheets.detectFormat(content);
            job.setFormat(format);
            sheet = spreadsheets.read(content, format);
        } catch (RuntimeException unreadable) {
            // A problem with the file, not with a row. Distinct on purpose:
            // "this is not a spreadsheet" and "row 14 has no price" need
            // different words in front of a seller.
            job.setStatus(CatalogueJobStatus.FAILED);
            job.setFailureReason(truncate(unreadable.getMessage(), 500));
            log.warn("[Catalogue] Import {} could not be read: {}",
                    job.getReference(), unreadable.getMessage());
            return;
        }

        for (String required : CatalogueJobServiceImpl.REQUIRED_COLUMNS) {
            if (!sheet.headers().contains(required)) {
                job.setStatus(CatalogueJobStatus.FAILED);
                job.setFailureReason("That file has no '" + required + "' column. Required columns: "
                        + String.join(", ", CatalogueJobServiceImpl.REQUIRED_COLUMNS) + ".");
                return;
            }
        }

        int succeeded = 0;
        int failed = 0;
        int recorded = 0;
        List<CatalogueJobError> errors = new ArrayList<>();

        for (int index = 0; index < sheet.rows().size(); index++) {
            // The number the seller's own spreadsheet shows: header is row 1.
            int rowNumber = index + 2;
            Map<String, String> row = sheet.rows().get(index);

            try {
                Product product = toProduct(vendor, row);
                products.save(product);
                succeeded++;
            } catch (RowProblem problem) {
                failed++;
                if (recorded < maxRecordedErrors) {
                    errors.add(CatalogueJobError.builder()
                            .job(job)
                            .rowNumber(rowNumber)
                            .field(problem.field)
                            .message(truncate(problem.getMessage(), 400))
                            .value(truncate(problem.value, 200))
                            .build());
                    recorded++;
                }
            } catch (RuntimeException unexpected) {
                // One bad row never takes the file with it. That is the whole
                // promise of a row-level import, and it is why each row is
                // saved rather than the batch being flushed at the end.
                failed++;
                log.warn("[Catalogue] Import {} row {} failed unexpectedly",
                        job.getReference(), rowNumber, unexpected);
                if (recorded < maxRecordedErrors) {
                    errors.add(CatalogueJobError.builder()
                            .job(job).rowNumber(rowNumber)
                            .message("This row could not be imported.")
                            .build());
                    recorded++;
                }
            }
        }

        jobErrors.saveAll(errors);
        job.setTotalRows(sheet.size());
        job.setSucceededRows(succeeded);
        job.setFailedRows(failed);
        job.setStatus(failed == 0
                ? CatalogueJobStatus.COMPLETED
                : CatalogueJobStatus.COMPLETED_WITH_ERRORS);

        log.info("[Catalogue] Import {} finished: {} in, {} rejected",
                job.getReference(), succeeded, failed);
    }

    /**
     * Turns one row into a draft listing, or says exactly what is wrong with it.
     *
     * <p>Every refusal quotes the seller's own text back. "Category does not
     * exist" is half an answer; "category 'Phonez' does not exist" is the whole
     * one, and it is the difference between an import they can fix in five
     * minutes and one they give up on.
     */
    private Product toProduct(Vendor vendor, Map<String, String> row) {
        String name = value(row, "name");
        if (name == null) {
            throw new RowProblem("name", null, "This row has no name.");
        }

        BigDecimal price = SpreadsheetReader.money(value(row, "price"));
        if (price == null) {
            throw new RowProblem("price", value(row, "price"),
                    "'" + orBlank(value(row, "price")) + "' is not a price.");
        }
        if (price.signum() <= 0) {
            throw new RowProblem("price", value(row, "price"), "A price has to be more than zero.");
        }

        String sku = value(row, "sku");
        if (sku != null && products.existsBySkuIgnoreCaseAndVendorId(sku, vendor.getId())) {
            throw new RowProblem("sku", sku, "You already have a listing with the code " + sku + ".");
        }

        Category category = null;
        String categoryName = value(row, "category");
        if (categoryName != null) {
            category = findCategory(categoryName).orElseThrow(() -> new RowProblem(
                    "category", categoryName,
                    "There is no category called '" + categoryName + "'."));
        }

        Brand brand = null;
        String brandName = value(row, "brand");
        if (brandName != null) {
            brand = findBrand(brandName).orElseThrow(() -> new RowProblem(
                    "brand", brandName, "There is no brand called '" + brandName + "'."));
        }

        Product product = Product.builder()
                .vendor(vendor)
                .name(name)
                .slug(Utils.toSlug(name) + "-" + UUID.randomUUID().toString().substring(0, 6))
                .shortDescription(value(row, "short_description"))
                .description(value(row, "description"))
                .price(price)
                .compareAtPrice(SpreadsheetReader.money(value(row, "compare_at_price")))
                // Never taken from the file. The listing currency is the payout
                // currency, and a spreadsheet column saying EUR would put an
                // unsnapshotted conversion between what a buyer pays and what
                // the seller is owed.
                .priceCurrency(vendor.getSettlementCurrency())
                .sku(sku == null
                        ? "SKU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                        : sku)
                .stock(integer(row, "stock", 0))
                .lowStockThreshold(integer(row, "low_stock_threshold", 3))
                .allowBackorder(bool(row, "allow_backorder"))
                .category(category)
                .brand(brand)
                .condition(enumOf(row, "condition", ProductCondition.class, ProductCondition.NEW))
                .deliveryScope(enumOf(row, "delivery_scope", DeliveryScope.class,
                        DeliveryScope.REGIIONAL))
                .weightKg(decimal(row, "weight_kg"))
                .dimensions(value(row, "dimensions"))
                .country(value(row, "country") == null
                        ? vendor.getAddressCountryCode()
                        : value(row, "country").toUpperCase(Locale.ROOT))
                .latitude(vendor.getPickupLatitude() != null
                        ? vendor.getPickupLatitude() : vendor.getLatitude())
                .longitude(vendor.getPickupLongitude() != null
                        ? vendor.getPickupLongitude() : vendor.getLongitude())
                // A draft, always. An import that could publish would be the
                // way past moderation.
                .status(ProductStatus.DRAFT)
                .active(false)
                .build();

        lifecycle.syncVisibility(product);
        return product;
    }

    private Optional<Category> findCategory(String name) {
        return categories.findAll().stream()
                .filter(category -> name.equalsIgnoreCase(category.getName())
                        || name.equalsIgnoreCase(category.getSlug()))
                .findFirst();
    }

    private Optional<Brand> findBrand(String name) {
        return brands.findAll().stream()
                .filter(brand -> name.equalsIgnoreCase(brand.getName())
                        || name.equalsIgnoreCase(brand.getSlug()))
                .findFirst();
    }

    // -- Export --------------------------------------------------------------

    private void runExport(CatalogueJob job) {
        boolean includeArchived = "all".equals(job.getOriginalFilename());
        List<Product> rows = products.findAllForExport(job.getVendor().getId(), includeArchived);

        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", "id", "sku", "name", "status", "price", "currency", "stock",
                "category", "brand", "condition", "weight_kg", "short_description", "description"))
           .append('\n');

        for (Product product : rows) {
            csv.append(SpreadsheetReader.csvCell(String.valueOf(product.getId()))).append(',')
               .append(SpreadsheetReader.csvCell(product.getSku())).append(',')
               .append(SpreadsheetReader.csvCell(product.getName())).append(',')
               .append(SpreadsheetReader.csvCell(product.getStatus() == null ? "" : product.getStatus().name())).append(',')
               .append(SpreadsheetReader.csvCell(product.getPrice() == null ? "" : product.getPrice().toPlainString())).append(',')
               .append(SpreadsheetReader.csvCell(product.getPriceCurrency())).append(',')
               .append(SpreadsheetReader.csvCell(String.valueOf(product.getStock()))).append(',')
               .append(SpreadsheetReader.csvCell(product.getCategory() == null ? "" : product.getCategory().getName())).append(',')
               .append(SpreadsheetReader.csvCell(product.getBrand() == null ? "" : product.getBrand().getName())).append(',')
               .append(SpreadsheetReader.csvCell(product.getCondition() == null ? "" : product.getCondition().name())).append(',')
               .append(SpreadsheetReader.csvCell(product.getWeightKg() == null ? "" : String.valueOf(product.getWeightKg()))).append(',')
               .append(SpreadsheetReader.csvCell(product.getShortDescription())).append(',')
               .append(SpreadsheetReader.csvCell(product.getDescription()))
               .append('\n');
        }

        // A byte-order mark, deliberately. Without it Excel opens a UTF-8 CSV
        // as the local code page and every accented character in a Senegalese
        // seller's catalogue comes out as mojibake.
        byte[] content = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);

        String url = storage.upload("exports",
                job.getReference().toLowerCase(Locale.ROOT) + ".csv", content, "text/csv");

        job.setResultUrl(url);
        job.setResultExpiresAt(LocalDateTime.now().plus(exportTtl));
        job.setTotalRows(rows.size());
        job.setSucceededRows(rows.size());
        job.setFailedRows(0);
        job.setStatus(CatalogueJobStatus.COMPLETED);

        log.info("[Catalogue] Export {} finished: {} rows", job.getReference(), rows.size());
    }

    // -- Row reading ---------------------------------------------------------

    /** A problem with one row, carrying enough to tell the seller what to change. */
    static final class RowProblem extends RuntimeException {
        final String field;
        final String value;

        RowProblem(String field, String value, String message) {
            super(message);
            this.field = field;
            this.value = value;
        }
    }

    private static String value(Map<String, String> row, String column) {
        String raw = row.get(column);
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    private static Integer integer(Map<String, String> row, String column, int fallback) {
        String raw = value(row, column);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.valueOf(raw.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException notANumber) {
            throw new RowProblem(column, raw, "'" + raw + "' is not a whole number.");
        }
    }

    private static Double decimal(Map<String, String> row, String column) {
        BigDecimal parsed = SpreadsheetReader.money(value(row, column));
        return parsed == null ? null : parsed.doubleValue();
    }

    private static boolean bool(Map<String, String> row, String column) {
        String raw = value(row, column);
        return raw != null && (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes")
                || raw.equals("1") || raw.equalsIgnoreCase("y"));
    }

    private static <E extends Enum<E>> E enumOf(Map<String, String> row, String column,
                                                Class<E> type, E fallback) {
        String raw = value(row, column);
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new RowProblem(column, raw, "'" + raw + "' is not one of: "
                    + java.util.Arrays.stream(type.getEnumConstants())
                            .map(Enum::name).collect(java.util.stream.Collectors.joining(", ")) + ".");
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
