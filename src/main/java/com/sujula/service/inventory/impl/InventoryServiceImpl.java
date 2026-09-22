package com.sujula.service.inventory.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.inventory.InventoryRequests;
import com.sujula.dto.response.inventory.InventoryResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.ImeiGrade;
import com.sujula.model.constant.ImeiStatus;
import com.sujula.model.constant.StockMovementReason;
import com.sujula.model.inventory.ImeiUnit;
import com.sujula.model.inventory.StockMovement;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.User;
import com.sujula.model.user.Vendor;
import com.sujula.repository.inventory.ImeiUnitRepository;
import com.sujula.repository.inventory.StockMovementRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.inventory.Imei;
import com.sujula.service.inventory.InventoryService;
import com.sujula.service.inventory.StockLedger;

import lombok.extern.slf4j.Slf4j;

/**
 * {@inheritDoc}
 *
 * <p>Nothing here writes a stock figure. Every change goes through
 * {@link StockLedger}, which records why, so the audit and the number cannot
 * come apart.
 */
@Slf4j
@Service
public class InventoryServiceImpl implements InventoryService {

    private static final int MAX_BULK_LINES = 500;
    private static final int MAX_UNITS_PER_REGISTRATION = 200;

    private final VendorRepository vendors;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final StockMovementRepository movements;
    private final ImeiUnitRepository handsets;
    private final UserRepository users;
    private final StockLedger ledger;

    public InventoryServiceImpl(VendorRepository vendors, ProductRepository products,
                                ProductVariantRepository variants,
                                StockMovementRepository movements, ImeiUnitRepository handsets,
                                UserRepository users, StockLedger ledger) {
        this.vendors = vendors;
        this.products = products;
        this.variants = variants;
        this.movements = movements;
        this.handsets = handsets;
        this.users = users;
        this.ledger = ledger;
    }

    // -- Reads ---------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public InventoryResponses.Page list(Long userId, boolean lowStockOnly, boolean outOfStockOnly,
                                        String search, Pageable pageable) {
        Vendor vendor = requireVendor(userId);

        Page<ProductVariant> page = variants.findForVendorInventory(
                vendor.getId(), lowStockOnly, outOfStockOnly, blankToNull(search), pageable);

        // Which lines are serialised, in one query rather than one per row.
        List<Long> ids = page.getContent().stream().map(ProductVariant::getId).toList();
        Map<Long, Long> sellableUnits = new HashMap<>();
        Map<Long, java.time.LocalDateTime> lastMoved = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : handsets.countSellableForVariants(ids)) {
                sellableUnits.put((Long) row[0], ((Number) row[1]).longValue());
            }
            for (Object[] row : movements.lastMovementForVariants(ids)) {
                lastMoved.put((Long) row[0], (java.time.LocalDateTime) row[1]);
            }
        }

        return new InventoryResponses.Page(
                page.getContent().stream()
                        .map(variant -> toItem(variant, sellableUnits.get(variant.getId()),
                                lastMoved.get(variant.getId())))
                        .toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                variants.countLowStockForVendor(vendor.getId()),
                variants.countOutOfStockForVendor(vendor.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResponses.Movements movements(Long userId, Long variantId, Pageable pageable) {
        Vendor vendor = requireVendor(userId);
        ProductVariant variant = requireVariant(vendor, variantId);

        Page<StockMovement> page = movements.findForVariant(variantId, vendor.getId(), pageable);

        return new InventoryResponses.Movements(
                variantId, variant.getSku(),
                variant.getStock() == null ? 0 : variant.getStock(),
                page.getContent().stream().map(InventoryServiceImpl::toMovement).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    // -- Adjusting -----------------------------------------------------------

    @Override
    @Transactional
    public InventoryResponses.Adjusted adjust(Long userId, Long variantId,
                                              InventoryRequests.AdjustStock request) {
        Vendor vendor = requireVendor(userId);
        ProductVariant variant = requireVariant(vendor, variantId);

        if ((request.setTo() == null) == (request.delta() == null)) {
            throw new BadRequestException(
                    "Send either setTo, for a figure you counted, or delta, for a change you know. "
                    + "Not both, and not neither.");
        }
        requireNotSerialised(variant);

        int before = variant.getStock() == null ? 0 : variant.getStock();
        int after;

        if (request.isAbsolute()) {
            // An absolute figure is only meaningful against the figure it was
            // read from. Without the version, two people counting the same shelf
            // and saving 10 and 12 leave whichever committed last, and the other
            // is simply wrong with nothing anywhere to say so.
            if (request.version() == null) {
                throw new BadRequestException(
                        "Setting a stock figure needs the version you read it at, so a count made "
                        + "from a stale screen is refused rather than silently overwriting "
                        + "somebody else's. Send a delta instead if you only know what moved.");
            }
            if (!request.version().equals(variant.getVersion())) {
                throw new ObjectOptimisticLockingFailureException(ProductVariant.class, variantId);
            }
            after = ledger.setVariant(variant, request.setTo(), request.reason(),
                    request.reference(), request.note(), actor(userId));
        } else {
            // A delta needs no version: +5 is +5 whoever else is writing.
            after = ledger.adjustVariant(variant, request.delta(), request.reason(),
                    request.reference(), request.note(), actor(userId));
        }

        log.info("[Inventory] Vendor {} moved variant {} from {} to {} ({})",
                vendor.getId(), variantId, before, after, request.reason());

        return new InventoryResponses.Adjusted(variantId, before, after, variant.getVersion(),
                describe(before, after));
    }

    @Override
    @Transactional
    public InventoryResponses.BulkAdjusted bulkAdjust(Long userId,
                                                      InventoryRequests.BulkAdjust request) {
        Vendor vendor = requireVendor(userId);

        if (request.lines().size() > MAX_BULK_LINES) {
            throw new BadRequestException(
                    "That is more than " + MAX_BULK_LINES + " lines. Split it up.");
        }

        List<InventoryResponses.Adjusted> applied = new ArrayList<>();
        List<InventoryResponses.LineError> errors = new ArrayList<>();

        for (InventoryRequests.Line line : request.lines()) {
            try {
                ProductVariant variant = requireVariant(vendor, line.variantId());
                requireNotSerialised(variant);

                int before = variant.getStock() == null ? 0 : variant.getStock();
                int after = ledger.adjustVariant(variant, line.delta(), request.reason(),
                        request.reference(), request.note(), actor(userId));

                applied.add(new InventoryResponses.Adjusted(line.variantId(), before, after,
                        variant.getVersion(), describe(before, after)));
            } catch (BadRequestException | ResourceNotFoundException refused) {
                // One bad line fails alone. A seller correcting forty counts
                // should not lose thirty-nine of them to one typo.
                errors.add(new InventoryResponses.LineError(line.variantId(), refused.getMessage()));
            }
        }

        log.info("[Inventory] Vendor {} bulk-adjusted {} of {} lines",
                vendor.getId(), applied.size(), request.lines().size());

        return new InventoryResponses.BulkAdjusted(
                request.lines().size(), applied.size(), applied, errors);
    }

    // -- Handsets ------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public InventoryResponses.HandsetPage handsets(Long userId, Long variantId, ImeiStatus status,
                                                   Pageable pageable) {
        Vendor vendor = requireVendor(userId);
        Page<ImeiUnit> page = handsets.findForVendor(vendor.getId(), variantId, status, pageable);

        return new InventoryResponses.HandsetPage(
                page.getContent().stream().map(InventoryServiceImpl::toHandset).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                variantId == null ? 0 : handsets.countSellableForVariant(variantId));
    }

    @Override
    @Transactional
    public InventoryResponses.Registered registerHandsets(
            Long userId, InventoryRequests.RegisterImeiUnits request) {

        Vendor vendor = requireVendor(userId);
        Product product = products.findByIdAndVendorId(request.productId(), vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.productId()));

        ProductVariant variant = request.variantId() == null ? null
                : requireVariant(vendor, request.variantId());

        if (variant != null && !variant.getProduct().getId().equals(product.getId())) {
            throw new BadRequestException("That option does not belong to that listing.");
        }
        if (request.units().size() > MAX_UNITS_PER_REGISTRATION) {
            throw new BadRequestException(
                    "That is more than " + MAX_UNITS_PER_REGISTRATION + " handsets in one go.");
        }

        User actor = actor(userId);
        List<InventoryResponses.Handset> registered = new ArrayList<>();
        List<InventoryResponses.LineError> rejected = new ArrayList<>();

        for (InventoryRequests.ImeiUnitEntry entry : request.units()) {
            try {
                String imei = Imei.normalise(entry.imei());

                // Checked platform-wide, not per seller. The same handset on two
                // shelves is a phone somebody has sold twice, and the cheapest
                // place to find that out is here.
                Optional<ImeiUnit> existing = handsets.findByImei(imei);
                if (existing.isPresent()) {
                    rejected.add(new InventoryResponses.LineError(null,
                            existing.get().getVendor().getId().equals(vendor.getId())
                                    ? imei + " is already registered to you."
                                    : imei + " is already registered on this platform. If you "
                                            + "bought this handset, contact support before "
                                            + "listing it."));
                    continue;
                }

                ImeiGrade grade = entry.grade() == null ? ImeiGrade.A_GRADE : entry.grade();
                requireGradeNote(grade, entry.gradeNote());

                ImeiUnit unit = handsets.save(ImeiUnit.builder()
                        .imei(imei)
                        .imei2(entry.imei2() == null ? null : Imei.normalise(entry.imei2()))
                        .serialNumber(trimToNull(entry.serialNumber()))
                        .vendor(vendor)
                        .product(product)
                        .variant(variant)
                        .status(ImeiStatus.IN_STOCK)
                        .grade(grade)
                        .gradeNote(trimToNull(entry.gradeNote()))
                        .costPrice(entry.costPrice())
                        .batteryHealth(entry.batteryHealth())
                        .warrantyExpiresOn(entry.warrantyExpiresOn())
                        .note(trimToNull(entry.note()))
                        .registeredBy(actor)
                        .build());

                registered.add(toHandset(unit));
            } catch (BadRequestException refused) {
                // A box of twenty with one mistyped code puts nineteen on the shelf.
                rejected.add(new InventoryResponses.LineError(null, refused.getMessage()));
            }
        }

        Integer stockAfter = registered.isEmpty() ? null
                : resyncSerialisedStock(product, variant, actor,
                        registered.size() + " handset(s) registered");

        log.info("[Inventory] Vendor {} registered {} of {} handsets on listing {}",
                vendor.getId(), registered.size(), request.units().size(), product.getId());

        return new InventoryResponses.Registered(
                request.units().size(), registered.size(), registered, rejected, stockAfter,
                rejected.isEmpty()
                        ? registered.size() + " handset(s) registered."
                        : registered.size() + " registered, " + rejected.size()
                          + " not - see the reasons.");
    }

    @Override
    @Transactional
    public InventoryResponses.Handset updateHandset(Long userId, Long unitId,
                                                    InventoryRequests.UpdateImeiUnit request) {
        Vendor vendor = requireVendor(userId);
        ImeiUnit unit = handsets.findByIdAndVendorId(unitId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Handset", unitId));

        if (!unit.getStatus().isSellerChangeable()) {
            throw new BadRequestException(unit.getStatus() == ImeiStatus.BLOCKED
                    ? "This handset is blocked. It cannot be changed from here - contact support."
                    : "This handset has been sold. Its record cannot be edited.");
        }

        if (request.grade() != null) {
            requireGradeNote(request.grade(), request.gradeNote() != null
                    ? request.gradeNote() : unit.getGradeNote());
            unit.setGrade(request.grade());
        }
        if (request.gradeNote() != null) {
            unit.setGradeNote(trimToNull(request.gradeNote()));
        }
        if (request.batteryHealth() != null) {
            unit.setBatteryHealth(request.batteryHealth());
        }
        if (request.warrantyExpiresOn() != null) {
            unit.setWarrantyExpiresOn(request.warrantyExpiresOn());
        }
        if (request.note() != null) {
            unit.setNote(trimToNull(request.note()));
        }

        boolean wasSellable = unit.isSellable();
        if (request.status() != null) {
            requireSellerMayMoveTo(request.status());
            unit.setStatus(request.status());
        }

        ImeiUnit saved = handsets.save(unit);

        // The shelf count follows the units, not the other way round. Writing
        // off a handset without the count moving is how a shop sells something
        // it does not have.
        if (wasSellable != saved.isSellable()) {
            resyncSerialisedStock(saved.getProduct(), saved.getVariant(), actor(userId),
                    "Handset " + saved.getImei() + " is now " + saved.getStatus());
        }

        log.info("[Inventory] Vendor {} updated handset {} to {} / {}",
                vendor.getId(), unitId, saved.getStatus(), saved.getGrade());

        return toHandset(saved);
    }

    /**
     * Makes the stock figure equal the number of sellable handsets.
     *
     * <p>Where units are tracked individually, a separate count is a second
     * opinion about the same shelf, and the two disagree the first time a
     * handset is written off without somebody remembering to decrement. So the
     * units are the authority and the count is derived - the same relationship
     * as a listing's visibility and its status.
     *
     * <p>The re-count still goes through the ledger, so the movement says why.
     */
    private Integer resyncSerialisedStock(Product product, ProductVariant variant, User actor,
                                          String note) {
        if (variant != null) {
            long sellable = handsets.countSellableForVariant(variant.getId());
            return ledger.setVariant(variant, (int) sellable, StockMovementReason.SERIALISED_UNIT,
                    null, note, actor);
        }
        long sellable = handsets.countSellableForProduct(product.getId());
        return ledger.setProduct(product, (int) sellable, StockMovementReason.SERIALISED_UNIT,
                null, note, actor);
    }

    /**
     * Refuses a hand-set count on a line whose units are tracked.
     *
     * <p>Not pedantry: a seller typing 12 into a screen that holds nine
     * registered handsets has created three phones that do not exist, and the
     * first buyer to order one finds out.
     */
    private void requireNotSerialised(ProductVariant variant) {
        if (handsets.existsByVariantId(variant.getId())) {
            throw new BadRequestException(
                    "This item is tracked handset by handset, so its stock is however many are in "
                    + "stock. Register, sell or write off a unit instead of typing a number.");
        }
    }

    /** A grade that admits a fault has to name it. */
    private static void requireGradeNote(ImeiGrade grade, String note) {
        if (grade == ImeiGrade.FOR_PARTS && (note == null || note.isBlank())) {
            throw new BadRequestException(
                    "Say what is wrong with it. 'For parts' with no reason is the listing that "
                    + "generates the dispute.");
        }
    }

    /**
     * The states a seller may move a handset into.
     *
     * <p>BLOCKED is not one of them, in either direction. A seller who could
     * set it would flag a rival's stock; a seller who could clear it would
     * launder a stolen handset through the platform, and recording an IMEI at
     * all is mostly about making that harder.
     */
    private static void requireSellerMayMoveTo(ImeiStatus status) {
        if (status == ImeiStatus.BLOCKED) {
            throw new BadRequestException(
                    "A handset is only blocked by us, after a report. Contact support if you "
                    + "believe this one is stolen.");
        }
        if (status == ImeiStatus.SOLD) {
            throw new BadRequestException(
                    "A handset is marked sold by the order it goes out on, not by hand.");
        }
    }

    // -- Helpers -------------------------------------------------------------

    private Vendor requireVendor(Long userId) {
        return vendors.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Store", "you do not have a store yet"));
    }

    /** Ownership is the query: a variant reached through another seller's product is not found. */
    private ProductVariant requireVariant(Vendor vendor, Long variantId) {
        return variants.findByIdAndVendorId(variantId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Item", variantId));
    }

    private User actor(Long userId) {
        return userId == null ? null : users.findById(userId).orElse(null);
    }

    private static String describe(int before, int after) {
        if (after == before) {
            return "Counted, and the figure was already right.";
        }
        return after > before
                ? "Added " + (after - before) + ". Now " + after + "."
                : "Removed " + (before - after) + ". Now " + after + ".";
    }

    private static InventoryResponses.Item toItem(ProductVariant variant, Long sellableUnits,
                                                  java.time.LocalDateTime lastMovementAt) {
        Product product = variant.getProduct();
        int stock = variant.getStock() == null ? 0 : variant.getStock();
        int threshold = product.getLowStockThreshold() == null ? 0 : product.getLowStockThreshold();

        return new InventoryResponses.Item(
                product.getId(), variant.getId(), product.getName(), variant.getSku(),
                variant.getVariantLabel(), product.getStatus(), product.isActive(),
                stock, threshold, stock <= threshold && stock > 0, stock == 0,
                product.isAllowBackorder(),
                variant.getPriceOverride() != null ? variant.getPriceOverride() : product.getPrice(),
                product.getPriceCurrency(),
                variant.getVersion(),
                sellableUnits != null,
                sellableUnits == null ? null : sellableUnits.intValue(),
                lastMovementAt);
    }

    private static InventoryResponses.Movement toMovement(StockMovement movement) {
        return new InventoryResponses.Movement(
                movement.getId(), movement.getReason(), movement.getQuantityChange(),
                movement.getStockBefore(), movement.getStockAfter(),
                movement.getReference(), movement.getNote(),
                // A name, not an id. The seller reading this knows their staff
                // by name and has never seen a user id.
                movement.getRecordedBy() == null ? null : movement.getRecordedBy().getFullName(),
                movement.getRecordedAt());
    }

    private static InventoryResponses.Handset toHandset(ImeiUnit unit) {
        return new InventoryResponses.Handset(
                unit.getId(), unit.getImei(), unit.getImei2(), unit.getSerialNumber(),
                unit.getProduct() == null ? null : unit.getProduct().getId(),
                unit.getProduct() == null ? null : unit.getProduct().getName(),
                unit.getVariant() == null ? null : unit.getVariant().getId(),
                unit.getVariant() == null ? null : unit.getVariant().getVariantLabel(),
                unit.getStatus(), unit.getGrade(), unit.getGradeNote(),
                unit.getCostPrice(), unit.getBatteryHealth(), unit.getWarrantyExpiresOn(),
                unit.getSoldOnOrderNumber(), unit.getSoldAt(), unit.getNote(),
                unit.getCreatedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToNull(String value) {
        return trimToNull(value);
    }
}
