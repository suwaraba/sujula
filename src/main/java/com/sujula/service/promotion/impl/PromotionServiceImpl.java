package com.sujula.service.promotion.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sujula.dto.request.promotion.PromotionRequests;
import com.sujula.dto.response.promotion.PromotionResponses;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CouponScope;
import com.sujula.model.constant.CouponType;
import com.sujula.model.constant.PromotionStatus;
import com.sujula.model.constant.PromotionType;
import com.sujula.model.products.Coupon;
import com.sujula.model.products.CouponUsage;
import com.sujula.model.promotion.Promotion;
import com.sujula.model.user.Vendor;
import com.sujula.repository.product.CouponRepository;
import com.sujula.repository.product.CouponUsageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.promotion.PromotionRepository;
import com.sujula.repository.user.VendorRepository;
import com.sujula.service.promotion.PromotionService;

import lombok.extern.slf4j.Slf4j;

/**
 * {@inheritDoc}
 *
 * <p>Ownership is the query throughout: a promotion or coupon belonging to
 * another seller is not found rather than found and refused. What it would leak
 * is a shop's margin, its campaign performance and its customer list.
 */
@Slf4j
@Service
public class PromotionServiceImpl implements PromotionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PromotionRepository promotions;
    private final CouponRepository coupons;
    private final CouponUsageRepository redemptions;
    private final ProductRepository products;
    private final VendorRepository vendors;

    public PromotionServiceImpl(PromotionRepository promotions, CouponRepository coupons,
                                CouponUsageRepository redemptions, ProductRepository products,
                                VendorRepository vendors) {
        this.promotions = promotions;
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.products = products;
        this.vendors = vendors;
    }

    // -- Promotions ----------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PromotionResponses.PromotionPage list(Long userId, PromotionStatus status,
                                                 Pageable pageable) {
        Vendor vendor = requireVendor(userId);
        Page<Promotion> page = promotions.findForVendor(vendor.getId(), status, pageable);

        return new PromotionResponses.PromotionPage(
                page.getContent().stream().map(PromotionServiceImpl::toPromotion).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public PromotionResponses.Promotion create(Long userId,
                                               PromotionRequests.CreatePromotion request) {
        Vendor vendor = requireVendor(userId);
        requireWindowMakesSense(request.startsAt(), request.endsAt());

        Promotion promotion = Promotion.builder()
                .vendor(vendor)
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .type(request.type())
                // A DRAFT, always. Activation is where the overlap check lives,
                // and a status a client could set on the way in would be a way
                // straight past it.
                .status(PromotionStatus.DRAFT)
                .percentOff(request.percentOff())
                .amountOff(request.amountOff())
                // The vendor's own, never the buyer's. Stored rather than
                // derived, so a seller who later changes where they bank does
                // not silently re-denominate a discount already running.
                .currency(vendor.getSettlementCurrency())
                .buyQuantity(request.buyQuantity())
                .getQuantity(request.getQuantity())
                .getDiscountPercent(request.getDiscountPercent())
                .bundlePrice(request.bundlePrice())
                .minimumBasket(request.minimumBasket())
                .maximumDiscount(request.maximumDiscount())
                .productIds(ownedProducts(vendor, request.productIds()))
                .categoryIds(request.categoryIds() == null
                        ? new LinkedHashSet<>() : new LinkedHashSet<>(request.categoryIds()))
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .build();

        requireTypeIsComplete(promotion);

        Promotion saved = promotions.save(promotion);
        log.info("[Promotions] Vendor {} drafted {} promotion {}",
                vendor.getId(), saved.getType(), saved.getId());
        return toPromotion(saved);
    }

    @Override
    @Transactional
    public PromotionResponses.Promotion update(Long userId, Long promotionId,
                                               PromotionRequests.UpdatePromotion request) {
        Vendor vendor = requireVendor(userId);
        Promotion promotion = requireOwn(vendor, promotionId);

        if (promotion.getStatus() == PromotionStatus.EXPIRED
                || promotion.getStatus() == PromotionStatus.CANCELLED) {
            throw new BadRequestException(
                    "This promotion has finished. Copy it into a new one rather than editing it - "
                    + "orders placed under it have to keep explaining themselves.");
        }

        if (request.name() != null && !request.name().isBlank()) {
            promotion.setName(request.name().trim());
        }
        if (request.description() != null) {
            promotion.setDescription(trimToNull(request.description()));
        }
        if (request.percentOff() != null) {
            promotion.setPercentOff(request.percentOff());
        }
        if (request.amountOff() != null) {
            promotion.setAmountOff(request.amountOff());
        }
        if (request.buyQuantity() != null) {
            promotion.setBuyQuantity(request.buyQuantity());
        }
        if (request.getQuantity() != null) {
            promotion.setGetQuantity(request.getQuantity());
        }
        if (request.getDiscountPercent() != null) {
            promotion.setGetDiscountPercent(request.getDiscountPercent());
        }
        if (request.bundlePrice() != null) {
            promotion.setBundlePrice(request.bundlePrice());
        }
        if (request.minimumBasket() != null) {
            promotion.setMinimumBasket(request.minimumBasket());
        }
        if (request.maximumDiscount() != null) {
            promotion.setMaximumDiscount(request.maximumDiscount());
        }
        if (request.productIds() != null) {
            promotion.setProductIds(ownedProducts(vendor, request.productIds()));
        }
        if (request.categoryIds() != null) {
            promotion.setCategoryIds(new LinkedHashSet<>(request.categoryIds()));
        }
        if (request.startsAt() != null) {
            promotion.setStartsAt(request.startsAt());
        }
        if (request.endsAt() != null) {
            promotion.setEndsAt(request.endsAt());
        }
        requireWindowMakesSense(promotion.getStartsAt(), promotion.getEndsAt());

        if (request.paused() != null) {
            // Pausing and resuming, without going back through activation. A
            // resume still has to pass the overlap check: the shelf may have
            // changed while it was off.
            if (request.paused() && promotion.getStatus() == PromotionStatus.ACTIVE) {
                promotion.setStatus(PromotionStatus.PAUSED);
            } else if (!request.paused() && promotion.getStatus() == PromotionStatus.PAUSED) {
                refuseOnConflict(vendor, promotion);
                promotion.setStatus(PromotionStatus.ACTIVE);
            }
        }

        requireTypeIsComplete(promotion);

        // Re-checked after the edit, because widening a running promotion's
        // goods or window is exactly how it comes to overlap another.
        if (promotion.getStatus() == PromotionStatus.ACTIVE) {
            refuseOnConflict(vendor, promotion);
        }

        return toPromotion(promotions.save(promotion));
    }

    @Override
    @Transactional
    public void delete(Long userId, Long promotionId) {
        Vendor vendor = requireVendor(userId);
        Promotion promotion = requireOwn(vendor, promotionId);

        if (promotion.getTimesApplied() != null && promotion.getTimesApplied() > 0) {
            // Cancelled rather than deleted. An order placed under it has to
            // keep explaining why it cost what it did, years later.
            promotion.setStatus(PromotionStatus.CANCELLED);
            promotions.save(promotion);
            log.info("[Promotions] Promotion {} cancelled - it has been applied {} time(s)",
                    promotionId, promotion.getTimesApplied());
            return;
        }

        promotions.delete(promotion);
        log.info("[Promotions] Promotion {} deleted - never applied", promotionId);
    }

    @Override
    @Transactional
    public PromotionResponses.Activated activate(Long userId, Long promotionId) {
        Vendor vendor = requireVendor(userId);
        Promotion promotion = requireOwn(vendor, promotionId);

        if (promotion.getStatus() == PromotionStatus.ACTIVE) {
            return new PromotionResponses.Activated(promotionId, PromotionStatus.ACTIVE,
                    promotion.isRunningAt(LocalDateTime.now()), List.of(),
                    "This promotion was already active.");
        }
        if (promotion.getStatus() == PromotionStatus.CANCELLED
                || promotion.getStatus() == PromotionStatus.EXPIRED) {
            throw new BadRequestException("This promotion has finished and cannot be restarted.");
        }
        requireTypeIsComplete(promotion);

        List<PromotionResponses.Conflict> conflicts = conflictsFor(vendor, promotion);
        if (!conflicts.isEmpty()) {
            throw new BadRequestException(conflictMessage(conflicts));
        }

        promotion.setStatus(PromotionStatus.ACTIVE);
        promotion.setActivatedAt(LocalDateTime.now());
        promotions.save(promotion);

        boolean running = promotion.isRunningAt(LocalDateTime.now());
        log.info("[Promotions] Promotion {} activated for vendor {} ({})",
                promotionId, vendor.getId(), running ? "running now" : "scheduled");

        return new PromotionResponses.Activated(promotionId, PromotionStatus.ACTIVE, running,
                List.of(),
                running ? "Live now." : "Scheduled. It starts on " + promotion.getStartsAt() + ".");
    }

    /**
     * Other live promotions of this seller that would discount the same goods at
     * the same time.
     *
     * <p>Two of them on one product do not compound into a sensible price: they
     * compound into whichever the pricing code reaches first, which is a
     * different answer on different days and an argument with a buyer either
     * way. A store-wide promotion conflicts with everything, which is the case
     * sellers hit most.
     */
    private List<PromotionResponses.Conflict> conflictsFor(Vendor vendor, Promotion promotion) {
        List<PromotionResponses.Conflict> conflicts = new ArrayList<>();

        for (Promotion other : promotions.findOverlapping(vendor.getId(),
                promotion.getId() == null ? -1L : promotion.getId(),
                promotion.getStartsAt(), promotion.getEndsAt())) {

            // FREE_SHIPPING discounts the delivery leg and the others discount
            // the goods, so the two can run together without fighting.
            boolean sameThing = (promotion.getType() == PromotionType.FREE_SHIPPING)
                    == (other.getType() == PromotionType.FREE_SHIPPING);
            if (!sameThing) {
                continue;
            }

            List<Long> shared = sharedProducts(promotion, other);
            if (shared == null) {
                continue;
            }
            conflicts.add(new PromotionResponses.Conflict(
                    other.getId(), other.getName(), describeOverlap(other), shared));
        }
        return conflicts;
    }

    /**
     * Which products two promotions both cover, or null when they cover none.
     *
     * <p>An empty list means "both are store-wide", which is a conflict with no
     * particular product to name — distinct from null, which means they do not
     * touch. Two meanings for an empty list would be a bug waiting to happen,
     * hence the null.
     */
    private static List<Long> sharedProducts(Promotion a, Promotion b) {
        if (a.isStoreWide() || b.isStoreWide()) {
            return List.of();
        }
        Set<Long> overlap = new LinkedHashSet<>(a.getProductIds());
        overlap.retainAll(b.getProductIds());
        if (!overlap.isEmpty()) {
            return List.copyOf(overlap);
        }
        Set<Long> categories = new LinkedHashSet<>(a.getCategoryIds());
        categories.retainAll(b.getCategoryIds());
        return categories.isEmpty() ? null : List.of();
    }

    private void refuseOnConflict(Vendor vendor, Promotion promotion) {
        List<PromotionResponses.Conflict> conflicts = conflictsFor(vendor, promotion);
        if (!conflicts.isEmpty()) {
            throw new BadRequestException(conflictMessage(conflicts));
        }
    }

    /** Names the conflict. "Conflicts with an existing promotion" leaves a seller hunting. */
    private static String conflictMessage(List<PromotionResponses.Conflict> conflicts) {
        PromotionResponses.Conflict first = conflicts.get(0);
        return "'" + first.name() + "' is already discounting "
                + (first.productIds().isEmpty() ? "your whole shop"
                   : first.productIds().size() + " of the same product(s)")
                + " " + first.overlap() + ". Two discounts on one item do not add up to a price "
                + "anybody can predict, so end or narrow that one first.";
    }

    private static String describeOverlap(Promotion other) {
        if (other.getStartsAt() == null && other.getEndsAt() == null) {
            return "with no end date";
        }
        if (other.getEndsAt() == null) {
            return "from " + other.getStartsAt().toLocalDate();
        }
        if (other.getStartsAt() == null) {
            return "until " + other.getEndsAt().toLocalDate();
        }
        return "from " + other.getStartsAt().toLocalDate() + " to " + other.getEndsAt().toLocalDate();
    }

    /**
     * Each type needs its own fields, and a half-configured promotion is worse
     * than none: it is a discount that applies at a price nobody chose.
     */
    private static void requireTypeIsComplete(Promotion promotion) {
        switch (promotion.getType()) {
            case PERCENT -> {
                requirePresent(promotion.getPercentOff(), "a percentage off");
                if (promotion.getPercentOff().compareTo(HUNDRED) > 0) {
                    throw new BadRequestException(
                            "A discount over 100% would pay the buyer to take it.");
                }
            }
            case FIXED -> requirePresent(promotion.getAmountOff(), "an amount off");
            case BUY_X_GET_Y -> {
                requirePresent(promotion.getBuyQuantity(), "how many to buy");
                requirePresent(promotion.getGetQuantity(), "how many to get");
            }
            case BUNDLE -> {
                requirePresent(promotion.getBundlePrice(), "a price for the bundle");
                if (promotion.getProductIds() == null || promotion.getProductIds().size() < 2) {
                    throw new BadRequestException(
                            "A bundle needs at least two products - otherwise it is just a price.");
                }
            }
            case FREE_SHIPPING -> { /* needs nothing but its window */ }
        }
    }

    private static void requirePresent(Object value, String what) {
        if (value == null) {
            throw new BadRequestException("This kind of promotion needs " + what + ".");
        }
    }

    private static void requireWindowMakesSense(LocalDateTime startsAt, LocalDateTime endsAt) {
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new BadRequestException("The end has to come after the start.");
        }
        if (endsAt != null && endsAt.isBefore(LocalDateTime.now())) {
            throw new BadRequestException("That end date has already passed.");
        }
    }

    /**
     * Refuses product ids that are not this seller's, rather than dropping them.
     *
     * <p>Refused rather than filtered, because a seller who believed they had
     * discounted something and had not would find out from their sales. Checked
     * in one query: a promotion naming fifty products should not be fifty round
     * trips.
     */
    private Set<Long> ownedProducts(Vendor vendor, Set<Long> requested) {
        if (requested == null || requested.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<Long> owned = new LinkedHashSet<>(products.findOwnedIds(requested, vendor.getId()));

        for (Long productId : requested) {
            if (!owned.contains(productId)) {
                throw new BadRequestException("Listing " + productId + " is not one of yours.");
            }
        }
        // Rebuilt in the order the seller sent them, so the response reads the
        // way their request did.
        Set<Long> ordered = new LinkedHashSet<>();
        requested.forEach(ordered::add);
        return ordered;
    }

    // -- Coupons -------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PromotionResponses.CouponPage coupons(Long userId, boolean activeOnly,
                                                 Pageable pageable) {
        Vendor vendor = requireVendor(userId);
        Page<Coupon> page = coupons.findForVendor(vendor.getId(), activeOnly, pageable);

        return new PromotionResponses.CouponPage(
                page.getContent().stream().map(PromotionServiceImpl::toCoupon).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public PromotionResponses.Coupon createCoupon(Long userId,
                                                  PromotionRequests.CreateCoupon request) {
        Vendor vendor = requireVendor(userId);

        // Upper-cased and unique platform-wide. A buyer holding a code that
        // means one thing in one shop and another somewhere else is a support
        // ticket nobody can resolve.
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (coupons.existsByCodeIgnoreCase(code)) {
            throw new BadRequestException(
                    "The code " + code + " is already in use. Pick another.");
        }
        requireCouponValue(request.type(), request.value());
        requireWindowMakesSense(request.startsAt(), request.expiresAt());

        Coupon coupon = coupons.save(Coupon.builder()
                .code(code)
                .description(trimToNull(request.description()))
                .type(request.type())
                // Vendor-funded, always, on this surface. A seller cannot issue
                // a coupon the platform pays for, and the scope is what decides
                // whose money it is when commission is worked out.
                .scope(CouponScope.VENDOR)
                .vendor(vendor)
                .value(request.value())
                .currency(vendor.getSettlementCurrency())
                .minimumOrderAmount(request.minimumOrderAmount())
                .maximumDiscountAmount(request.maximumDiscountAmount())
                .usageLimit(request.usageLimit())
                .usageCount(0)
                .perUserLimit(request.perUserLimit())
                .active(true)
                .startsAt(request.startsAt())
                .expiresAt(request.expiresAt())
                .build());

        log.info("[Promotions] Vendor {} issued coupon {}", vendor.getId(), code);
        return toCoupon(coupon);
    }

    @Override
    @Transactional
    public PromotionResponses.Coupon updateCoupon(Long userId, Long couponId,
                                                  PromotionRequests.UpdateCoupon request) {
        Vendor vendor = requireVendor(userId);
        Coupon coupon = coupons.findByIdAndVendorId(couponId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Coupon", couponId));

        // The code is deliberately not editable. It is printed on flyers and
        // typed from memory, and changing it silently breaks every buyer who
        // already has it.
        if (request.description() != null) {
            coupon.setDescription(trimToNull(request.description()));
        }
        if (request.value() != null) {
            coupon.setValue(request.value());
        }
        if (request.minimumOrderAmount() != null) {
            coupon.setMinimumOrderAmount(request.minimumOrderAmount());
        }
        if (request.maximumDiscountAmount() != null) {
            coupon.setMaximumDiscountAmount(request.maximumDiscountAmount());
        }
        if (request.usageLimit() != null) {
            int used = coupon.getUsageCount() == null ? 0 : coupon.getUsageCount();
            if (request.usageLimit() < used) {
                throw new BadRequestException(
                        "This coupon has already been used " + used + " times, so the limit cannot "
                        + "be set below that. Turn it off instead.");
            }
            coupon.setUsageLimit(request.usageLimit());
        }
        if (request.perUserLimit() != null) {
            coupon.setPerUserLimit(request.perUserLimit());
        }
        if (request.startsAt() != null) {
            coupon.setStartsAt(request.startsAt());
        }
        if (request.expiresAt() != null) {
            coupon.setExpiresAt(request.expiresAt());
        }
        if (request.active() != null) {
            coupon.setActive(request.active());
        }
        requireCouponValue(coupon.getType(), coupon.getValue());

        return toCoupon(coupons.save(coupon));
    }

    @Override
    @Transactional(readOnly = true)
    public PromotionResponses.Redemptions redemptions(Long userId, Long couponId,
                                                      Pageable pageable) {
        Vendor vendor = requireVendor(userId);
        Coupon coupon = coupons.findByIdAndVendorId(couponId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Coupon", couponId));

        Page<CouponUsage> page = redemptions.findForCoupon(couponId, vendor.getId(), pageable);

        return new PromotionResponses.Redemptions(
                coupon.getCode(),
                coupon.getUsageCount() == null ? 0 : coupon.getUsageCount(),
                coupon.getUsageLimit(),
                page.getContent().stream()
                        .map(usage -> new PromotionResponses.Redemption(
                                usage.getId(),
                                // A name, not an email. A seller needs to
                                // recognise a customer, not to be handed a
                                // mailing list with their campaign report.
                                usage.getUser() == null ? null : usage.getUser().getFullName(),
                                usage.getOrderId(), usage.getUsedAt()))
                        .toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private static void requireCouponValue(CouponType type, BigDecimal value) {
        if (type == CouponType.FREE_SHIPPING) {
            return;
        }
        if (value == null || value.signum() <= 0) {
            throw new BadRequestException("Say how much this coupon takes off.");
        }
        if (type == CouponType.PERCENTAGE && value.compareTo(HUNDRED) > 0) {
            throw new BadRequestException("A discount over 100% would pay the buyer to take it.");
        }
    }

    // -- Helpers -------------------------------------------------------------

    private Vendor requireVendor(Long userId) {
        return vendors.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Store", "you do not have a store yet"));
    }

    private Promotion requireOwn(Vendor vendor, Long promotionId) {
        return promotions.findByIdAndVendorId(promotionId, vendor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Promotion", promotionId));
    }

    private static PromotionResponses.Promotion toPromotion(Promotion promotion) {
        LocalDateTime now = LocalDateTime.now();
        return new PromotionResponses.Promotion(
                promotion.getId(), promotion.getName(), promotion.getDescription(),
                promotion.getType(), promotion.getStatus(), promotion.isRunningAt(now),
                whyNotRunning(promotion, now),
                promotion.getPercentOff(), promotion.getAmountOff(), promotion.getCurrency(),
                promotion.getBuyQuantity(), promotion.getGetQuantity(),
                promotion.getGetDiscountPercent(), promotion.getBundlePrice(),
                promotion.getMinimumBasket(), promotion.getMaximumDiscount(),
                promotion.isStoreWide(),
                promotion.getProductIds() == null ? Set.of() : Set.copyOf(promotion.getProductIds()),
                promotion.getCategoryIds() == null ? Set.of() : Set.copyOf(promotion.getCategoryIds()),
                promotion.getStartsAt(), promotion.getEndsAt(),
                promotion.getTimesApplied() == null ? 0 : promotion.getTimesApplied(),
                promotion.getActivatedAt(), promotion.getCreatedAt());
    }

    /** Why a promotion is not discounting anything right now, or null when it is. */
    private static String whyNotRunning(Promotion promotion, LocalDateTime now) {
        if (promotion.isRunningAt(now)) {
            return null;
        }
        return switch (promotion.getStatus()) {
            case DRAFT -> "Not started. Activate it when you are ready.";
            case PAUSED -> "You paused this.";
            case EXPIRED -> "Its dates have passed.";
            case CANCELLED -> "Cancelled.";
            case ACTIVE -> promotion.getStartsAt() != null && now.isBefore(promotion.getStartsAt())
                    ? "Scheduled to start on " + promotion.getStartsAt().toLocalDate() + "."
                    : "Its dates have passed.";
        };
    }

    private static PromotionResponses.Coupon toCoupon(Coupon coupon) {
        LocalDateTime now = LocalDateTime.now();
        int used = coupon.getUsageCount() == null ? 0 : coupon.getUsageCount();
        Integer remaining = coupon.getUsageLimit() == null ? null
                : Math.max(coupon.getUsageLimit() - used, 0);

        String blocked = !coupon.isActive() ? "You turned this off."
                : coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt()) ? "Expired."
                : coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())
                        ? "Starts on " + coupon.getStartsAt().toLocalDate() + "."
                : remaining != null && remaining == 0 ? "Fully redeemed."
                : null;

        return new PromotionResponses.Coupon(
                coupon.getId(), coupon.getCode(), coupon.getDescription(), coupon.getType(),
                coupon.getValue(), coupon.getCurrency(),
                coupon.getMinimumOrderAmount(), coupon.getMaximumDiscountAmount(),
                coupon.getUsageLimit(), used, remaining, coupon.getPerUserLimit(),
                coupon.isActive(), blocked == null, blocked,
                coupon.getStartsAt(), coupon.getExpiresAt(), coupon.getCreatedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
