package com.sujula.service.impl;

import com.sujula.dto.request.order.ApplyCouponRequest;
import com.sujula.dto.request.order.CartItemRequest;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.dto.response.order.CartResponse.CartIssue;
import com.sujula.dto.response.order.CartResponse.CartItemResponse;
import com.sujula.dto.response.order.CartResponse.VendorGroup;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.CartIssueType;
import com.sujula.model.constant.CouponScope;
import com.sujula.model.constant.PartnerStatus;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartCoupon;
import com.sujula.model.order.CartItem;
import com.sujula.model.products.Coupon;
import com.sujula.model.products.Product;
import com.sujula.model.products.ProductImage;
import com.sujula.model.products.ProductOptionValue;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.Vendor;
import com.sujula.repository.order.CartItemRepository;
import com.sujula.repository.order.CartRepository;
import com.sujula.repository.product.CouponRepository;
import com.sujula.repository.product.CouponUsageRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.service.CartService;
import com.sujula.service.ExchangeRateService;
import com.sujula.service.cart.CartOwner;
import com.sujula.service.cart.CartProvisioner;
import com.sujula.service.cart.RateTable;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Multivendor, multicurrency cart.
 *
 * <p>Three properties drive the design:
 *
 * <ul>
 *   <li><b>The cart is a quote, not a contract.</b> Prices are re-read from the
 *       live catalogue on every load and the shopper is told when something
 *       moved. Nothing here guarantees a price; checkout pins it.</li>
 *   <li><b>Vendor is a first-class axis.</b> Items are grouped by vendor because
 *       availability, discounts and eventually shipping are all per-vendor.</li>
 *   <li><b>Currency is never converted in place.</b> Each line stays in its
 *       vendor's listing currency; conversion into the shopper's display
 *       currency happens at read time from one batched rate lookup.</li>
 * </ul>
 *
 * <p>Every mutation takes a row lock on the cart first, which is what makes
 * concurrent add/update/merge safe without duplicate-line races.
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    /** Vendor states allowed to sell. */
    private static final Set<PartnerStatus> SELLABLE =
            EnumSet.of(PartnerStatus.APPROVED, PartnerStatus.ACTIVE);

    private static final int MAX_QUANTITY = CartItemRequest.MAX_QUANTITY_PER_LINE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final ExchangeRateService exchangeRateService;
    private final CartProvisioner cartProvisioner;

    @Value("${sujula.cart.guest-ttl-days:7}")
    private int guestTtlDays;

    /** Fallback when a product or coupon has no currency recorded. */
    @Value("${sujula.cart.default-currency:GMD}")
    private String defaultCurrency;

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * Not {@code readOnly}: revalidation writes back corrected prices and
     * clamped quantities. Hibernate emits no UPDATE when nothing changed, so an
     * unchanged cart still costs only reads.
     */
    @Override
    @Transactional
    public CartResponse getCart(CartOwner owner, String displayCurrency) {
        Optional<Cart> cart = findCart(owner);
        if (cart.isEmpty()) {
            return emptyResponse(owner, resolveCurrency(displayCurrency, null));
        }
        return present(cart.get(), displayCurrency);
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse addItem(CartOwner owner, CartItemRequest request, String displayCurrency) {
        Cart cart = lockedCart(getOrCreateCart(owner, displayCurrency));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.getProductId()));

        requireSellable(product);

        ProductVariant variant = resolveVariant(request.getVariantId(), product);
        int available = availableStock(product, variant);
        String currency = listingCurrency(product);
        BigDecimal unitPrice = listingPrice(product, variant);

        CartItem existing = cartItemRepository
                .findByCartIdAndProductIdAndVariantId(
                        cart.getId(), product.getId(), variant != null ? variant.getId() : null)
                .orElse(null);

        int currentQty = existing != null ? existing.getQuantity() : 0;
        int desiredQty = currentQty + request.getQuantity();

        if (available <= 0) {
            throw new BadRequestException("Out of stock: " + product.getName());
        }
        if (desiredQty > available) {
            throw new BadRequestException(
                    "Only " + available + " left of " + product.getName()
                            + (currentQty > 0 ? " and your cart already holds " + currentQty : ""));
        }
        if (desiredQty > MAX_QUANTITY) {
            throw new BadRequestException("At most " + MAX_QUANTITY + " units per item");
        }

        if (existing != null) {
            existing.setQuantity(desiredQty);
            existing.setUnitPrice(unitPrice);
            existing.setUnitPriceCurrency(currency);
            existing.setPriceCheckedAt(LocalDateTime.now());
            cartItemRepository.save(existing);
        } else {
            CartItem item = CartItem.builder()
                    .cart(cart)
                    .product(product)
                    .variant(variant)
                    .vendor(product.getVendor())
                    .quantity(request.getQuantity())
                    .unitPrice(unitPrice)
                    .unitPriceCurrency(currency)
                    .priceCheckedAt(LocalDateTime.now())
                    .build();
            cart.getItems().add(cartItemRepository.save(item));
        }

        touch(cart);
        return present(cart, displayCurrency);
    }

    @Override
    @Transactional
    public CartResponse updateItem(CartOwner owner, Long cartItemId,
                                   CartItemRequest request, String displayCurrency) {
        Cart cart = lockedCart(requireCart(owner));
        CartItem item = requireItem(cart, cartItemId);

        int available = availableStock(item.getProduct(), item.getVariant());
        if (available <= 0) {
            throw new BadRequestException("Out of stock: " + item.getProduct().getName());
        }
        if (request.getQuantity() > available) {
            throw new BadRequestException("Only " + available + " left of " + item.getProduct().getName());
        }

        item.setQuantity(request.getQuantity());
        cartItemRepository.save(item);

        touch(cart);
        return present(cart, displayCurrency);
    }

    @Override
    @Transactional
    public CartResponse removeItem(CartOwner owner, Long cartItemId, String displayCurrency) {
        Cart cart = lockedCart(requireCart(owner));
        CartItem item = requireItem(cart, cartItemId);

        cart.getItems().remove(item);
        cartItemRepository.delete(item);

        touch(cart);
        return present(cart, displayCurrency);
    }

    @Override
    @Transactional
    public void clearCart(CartOwner owner) {
        findCart(owner).ifPresent(found -> {
            Cart cart = lockedCart(found);
            cart.getItems().clear();          // orphanRemoval deletes the rows
            cart.getAppliedCoupons().clear(); // coupons cannot outlive the items
            touch(cart);
        });
    }

    @Override
    @Transactional
    public CartResponse setDisplayCurrency(CartOwner owner, String displayCurrency) {
        String currency = requireCurrencyCode(displayCurrency);
        Cart cart = lockedCart(getOrCreateCart(owner, currency));
        cart.setDisplayCurrency(currency);
        touch(cart);
        return present(cart, currency);
    }

    // ── Coupons ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse applyCoupon(CartOwner owner, ApplyCouponRequest request, String displayCurrency) {
        Cart cart = lockedCart(requireCart(owner));
        hydrate(cart);

        if (cart.getItems().isEmpty()) {
            throw new BadRequestException("Add something to your cart before applying a coupon");
        }

        String code = request.getCouponCode().trim().toUpperCase();
        Coupon coupon = couponRepository.findByCodeWithVendor(code)
                .orElseThrow(() -> new BadRequestException("Invalid coupon code"));

        String currency = resolveCurrency(displayCurrency, cart);
        RateTable rates = buildRateTable(cart, currency, coupon);

        Long vendorId = validateCouponScope(cart, coupon);
        validateCoupon(coupon, owner.isGuest() ? null : owner.userId(),
                couponBase(cart, vendorId, rates), rates);

        // Replace whatever occupies this coupon's slot (platform, or this vendor)
        cart.getAppliedCoupons().removeIf(cc ->
                coupon.getScope() == CouponScope.PLATFORM
                        ? cc.getVendor() == null
                        : cc.getVendor() != null && cc.getVendor().getId().equals(vendorId));

        cart.getAppliedCoupons().add(CartCoupon.builder()
                .cart(cart)
                .coupon(coupon)
                .vendor(coupon.getScope() == CouponScope.VENDOR ? coupon.getVendor() : null)
                .build());

        touch(cart);
        return present(cart, displayCurrency);
    }

    @Override
    @Transactional
    public CartResponse removeCoupon(CartOwner owner, Long vendorId, String displayCurrency) {
        Cart cart = lockedCart(requireCart(owner));

        boolean removed = cart.getAppliedCoupons().removeIf(cc -> vendorId == null
                ? cc.getVendor() == null
                : cc.getVendor() != null && cc.getVendor().getId().equals(vendorId));

        if (removed) {
            touch(cart);
        }
        return present(cart, displayCurrency);
    }

    // ── Merge ─────────────────────────────────────────────────────────────────

    /**
     * Guest quantities are <em>summed</em> into the user cart rather than
     * max-merged — the shopper genuinely put both there — then clamped to live
     * stock, with anything unavailable dropped and reported.
     */
    @Override
    @Transactional
    public CartResponse mergeGuestCart(Long userId, String sessionId, String displayCurrency) {
        CartOwner userOwner = CartOwner.user(userId);

        Optional<Cart> guestOpt = cartRepository.findBySessionId(sessionId);
        if (guestOpt.isEmpty()) {
            return getCart(userOwner, displayCurrency);
        }

        Cart guestCart = lockedCart(guestOpt.get());
        Cart userCart = lockedCart(getOrCreateCart(userOwner, displayCurrency));
        hydrate(guestCart);
        hydrate(userCart);

        List<CartIssue> mergeIssues = new ArrayList<>();

        for (CartItem guestItem : List.copyOf(guestCart.getItems())) {
            Product product = guestItem.getProduct();
            ProductVariant variant = guestItem.getVariant();

            if (!isSellable(product) || (variant != null && !variant.isActive())) {
                mergeIssues.add(CartIssue.builder()
                        .type(isSellable(product) ? CartIssueType.VARIANT_UNAVAILABLE
                                                  : CartIssueType.PRODUCT_UNAVAILABLE)
                        .message(safeName(product) + " is no longer available and was not moved to your cart")
                        .build());
                continue;
            }

            int available = availableStock(product, variant);
            if (available <= 0) {
                mergeIssues.add(CartIssue.builder()
                        .type(CartIssueType.OUT_OF_STOCK)
                        .message(safeName(product) + " sold out and was not moved to your cart")
                        .build());
                continue;
            }

            Long variantId = variant != null ? variant.getId() : null;
            CartItem target = userCart.getItems().stream()
                    .filter(ui -> ui.getProduct().getId().equals(product.getId()))
                    .filter(ui -> java.util.Objects.equals(ui.variantIdOrNull(), variantId))
                    .findFirst()
                    .orElse(null);

            int wanted = (target != null ? target.getQuantity() : 0) + guestItem.getQuantity();
            int granted = Math.min(Math.min(wanted, available), MAX_QUANTITY);

            if (granted < wanted) {
                mergeIssues.add(CartIssue.builder()
                        .type(CartIssueType.QUANTITY_REDUCED)
                        .message(safeName(product) + " reduced to " + granted + " (stock limit)")
                        .build());
            }

            if (target != null) {
                target.setQuantity(granted);
                cartItemRepository.save(target);
            } else {
                CartItem moved = CartItem.builder()
                        .cart(userCart)
                        .product(product)
                        .variant(variant)
                        .vendor(product.getVendor())
                        .quantity(granted)
                        .unitPrice(listingPrice(product, variant))
                        .unitPriceCurrency(listingCurrency(product))
                        .priceCheckedAt(LocalDateTime.now())
                        .build();
                userCart.getItems().add(cartItemRepository.save(moved));
            }
        }

        // Move coupons into slots the user cart has not already filled
        for (CartCoupon guestCoupon : List.copyOf(guestCart.getAppliedCoupons())) {
            boolean slotTaken = guestCoupon.getVendor() == null
                    ? userCart.platformCoupon().isPresent()
                    : userCart.vendorCoupon(guestCoupon.getVendor().getId()).isPresent();
            if (!slotTaken) {
                userCart.getAppliedCoupons().add(CartCoupon.builder()
                        .cart(userCart)
                        .coupon(guestCoupon.getCoupon())
                        .vendor(guestCoupon.getVendor())
                        .build());
            }
        }

        guestCart.getItems().clear();
        guestCart.getAppliedCoupons().clear();
        cartRepository.delete(guestCart);

        CartResponse response = present(userCart, displayCurrency);
        if (!mergeIssues.isEmpty()) {
            List<CartIssue> combined = new ArrayList<>(mergeIssues);
            if (response.getIssues() != null) combined.addAll(response.getIssues());
            response.setIssues(combined);
        }
        return response;
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public int purgeExpiredGuestCarts() {
        int deleted = cartRepository.deleteExpiredGuestCarts(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Purged {} expired guest cart(s)", deleted);
        }
        return deleted;
    }

    // ── Cart resolution ───────────────────────────────────────────────────────

    private Optional<Cart> findCart(CartOwner owner) {
        return owner.isGuest()
                ? cartRepository.findBySessionIdWithItems(owner.sessionId())
                : cartRepository.findByUserIdWithItems(owner.userId());
    }

    private Cart requireCart(CartOwner owner) {
        return findCart(owner).orElseThrow(() -> new ResourceNotFoundException("Cart", "no cart for this session"));
    }

    private Cart getOrCreateCart(CartOwner owner, String displayCurrency) {
        Optional<Cart> existing = findCart(owner);
        if (existing.isPresent()) {
            return existing.get();
        }

        String currency = resolveCurrency(displayCurrency, null);
        try {
            return owner.isGuest()
                    ? cartProvisioner.createGuestCart(owner.sessionId(), currency, guestTtlDays)
                    : cartProvisioner.createUserCart(owner.userId(), currency);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // Another request created it first; its transaction has committed.
            return findCart(owner).orElseThrow(() ->
                    new BadRequestException("Could not open a cart, please retry"));
        }
    }

    /** Takes the row lock that serialises all writes to this cart. */
    private Cart lockedCart(Cart cart) {
        return cartRepository.findByIdForUpdate(cart.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Cart", cart.getId()));
    }

    private CartItem requireItem(Cart cart, Long cartItemId) {
        hydrate(cart);
        return cart.getItems().stream()
                .filter(i -> i.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", cartItemId));
    }

    /** Refreshes the guest TTL on every mutation. No-op for user carts. */
    private void touch(Cart cart) {
        if (cart.isGuestCart() && cart.getSessionId() != null) {
            cart.setExpiresAt(LocalDateTime.now().plusDays(guestTtlDays));
        }
        cartRepository.save(cart);
    }

    // ── Hydration ─────────────────────────────────────────────────────────────

    /**
     * Loads the whole item graph in two queries regardless of cart size, instead
     * of the ~3 lazy hits per line a naive traversal would cost.
     */
    private void hydrate(Cart cart) {
        if (cart.getId() == null) return;
        cartItemRepository.findByCartIdWithProductGraph(cart.getId());
        cartItemRepository.findByCartIdWithVariantGraph(cart.getId());
    }

    // ── Catalogue checks ──────────────────────────────────────────────────────

    private ProductVariant resolveVariant(Long variantId, Product product) {
        if (variantId == null) return null;

        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId));

        if (variant.getProduct() == null || !variant.getProduct().getId().equals(product.getId())) {
            throw new BadRequestException("Variant does not belong to this product");
        }
        if (!variant.isActive()) {
            throw new BadRequestException("Selected variant is unavailable");
        }
        return variant;
    }

    private void requireSellable(Product product) {
        if (!product.isActive()) {
            throw new BadRequestException("Product is not available: " + product.getName());
        }
        Vendor vendor = product.getVendor();
        if (vendor == null) {
            throw new BadRequestException("Product has no vendor and cannot be purchased");
        }
        if (!SELLABLE.contains(vendor.getStatus())) {
            throw new BadRequestException(vendor.getStoreName() + " is not currently accepting orders");
        }

        // A listing priced outside its vendor's settlement currency is a data
        // fault, not a shopper problem. Refusing it here is the only safe move:
        // guessing the intended currency would silently mis-state what the
        // vendor is owed.
        String listing = product.getPriceCurrency();
        String settlement = settlementCurrency(vendor);
        if (listing != null && !listing.isBlank() && !listing.equalsIgnoreCase(settlement)) {
            log.error("Product {} is priced in {} but vendor {} settles in {}",
                    product.getId(), listing, vendor.getId(), settlement);
            throw new BadRequestException(
                    "This listing is misconfigured and cannot be purchased right now");
        }
    }

    private boolean isSellable(Product product) {
        return product != null
                && product.isActive()
                && product.getVendor() != null
                && SELLABLE.contains(product.getVendor().getStatus());
    }

    /** Effective purchasable quantity, honouring the product's backorder setting. */
    private int availableStock(Product product, ProductVariant variant) {
        if (product != null && product.isAllowBackorder()) {
            return MAX_QUANTITY;
        }
        Integer stock = variant != null ? variant.getStock()
                                        : (product != null ? product.getStock() : null);
        return stock != null ? Math.max(stock, 0) : 0;
    }

    /** Live listing price, including variant option surcharges. */
    private BigDecimal listingPrice(Product product, ProductVariant variant) {
        BigDecimal price = variant != null ? variant.getEffectivePrice() : product.getPrice();
        if (price == null) {
            throw new BadRequestException("No price is set for " + safeName(product));
        }
        return price.setScale(RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The currency a line is denominated in.
     *
     * <p>Taken from the <em>vendor</em>, not the product. The vendor's settlement
     * currency is the one figure that must stay stable across their whole
     * catalogue, their order reports and their payouts — deriving it per product
     * would let a single mispriced listing split a vendor's books in two.
     */
    private String settlementCurrency(Vendor vendor) {
        String currency = vendor != null ? vendor.getSettlementCurrency() : null;
        return (currency == null || currency.isBlank()) ? defaultCurrency : currency.toUpperCase();
    }

    private String listingCurrency(Product product) {
        return settlementCurrency(product != null ? product.getVendor() : null);
    }

    // ── Revalidation ──────────────────────────────────────────────────────────

    /**
     * Reconciles every line against live catalogue state, correcting prices and
     * quantities in place and returning what changed. Lines that cannot be
     * bought at all are marked unpurchasable but left in the cart, so the
     * shopper sees why rather than finding items silently gone.
     */
    private Map<Long, List<CartIssue>> revalidate(Cart cart) {
        Map<Long, List<CartIssue>> byItem = new LinkedHashMap<>();

        for (CartItem item : cart.getItems()) {
            List<CartIssue> issues = new ArrayList<>();
            Product product = item.getProduct();
            ProductVariant variant = item.getVariant();

            if (product == null || !product.isActive()) {
                issues.add(itemIssue(item, CartIssueType.PRODUCT_UNAVAILABLE,
                        safeName(product) + " is no longer available"));
            } else if (product.getVendor() == null || !SELLABLE.contains(product.getVendor().getStatus())) {
                issues.add(itemIssue(item, CartIssueType.VENDOR_UNAVAILABLE,
                        "This seller is not currently accepting orders"));
            }

            if (variant != null && !variant.isActive()) {
                issues.add(itemIssue(item, CartIssueType.VARIANT_UNAVAILABLE,
                        "The selected option for " + safeName(product) + " is no longer available"));
            }

            if (product != null) {
                // Re-price against the live listing
                BigDecimal current = variant != null ? variant.getEffectivePrice() : product.getPrice();
                if (current != null) {
                    current = current.setScale(RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
                    if (item.getUnitPrice() == null || current.compareTo(item.getUnitPrice()) != 0) {
                        BigDecimal previous = item.getUnitPrice();
                        item.setUnitPrice(current);
                        issues.add(itemIssue(item, CartIssueType.PRICE_CHANGED,
                                "Price for " + safeName(product) + " changed from "
                                        + previous + " to " + current));
                    }
                }
                item.setUnitPriceCurrency(listingCurrency(product));
                item.setPriceCheckedAt(LocalDateTime.now());

                int available = availableStock(product, variant);
                if (available <= 0) {
                    issues.add(itemIssue(item, CartIssueType.OUT_OF_STOCK,
                            safeName(product) + " is out of stock"));
                } else if (item.getQuantity() > available) {
                    issues.add(itemIssue(item, CartIssueType.QUANTITY_REDUCED,
                            safeName(product) + " reduced to " + available + " (all we have left)"));
                    item.setQuantity(available);
                }
            }

            if (!issues.isEmpty()) {
                byItem.put(item.getId(), issues);
            }
        }
        return byItem;
    }

    /** Drops coupons that expired, ran out, or whose vendor is no longer in the cart. */
    private List<CartIssue> revalidateCoupons(Cart cart, Long userId) {
        List<CartIssue> issues = new ArrayList<>();
        Set<Long> vendorIds = cart.getItems().stream()
                .map(CartItem::getVendor)
                .filter(java.util.Objects::nonNull)
                .map(Vendor::getId)
                .collect(Collectors.toCollection(HashSet::new));

        cart.getAppliedCoupons().removeIf(applied -> {
            Coupon coupon = applied.getCoupon();
            String reason = couponInvalidReason(coupon, userId);

            if (reason == null && applied.getVendor() != null
                    && !vendorIds.contains(applied.getVendor().getId())) {
                reason = "your cart no longer has items from that seller";
            }
            if (reason == null) {
                return false;
            }
            issues.add(CartIssue.builder()
                    .type(CartIssueType.COUPON_REMOVED)
                    .vendorId(applied.getVendor() != null ? applied.getVendor().getId() : null)
                    .message("Coupon " + (coupon != null ? coupon.getCode() : "") + " removed: " + reason)
                    .build());
            return true;
        });
        return issues;
    }

    // ── Coupon validation ─────────────────────────────────────────────────────

    /** @return the vendor id a VENDOR-scoped coupon targets, or null for platform coupons */
    private Long validateCouponScope(Cart cart, Coupon coupon) {
        if (coupon.getScope() != CouponScope.VENDOR) {
            return null;
        }
        Vendor vendor = coupon.getVendor();
        if (vendor == null) {
            throw new BadRequestException("This coupon is misconfigured and cannot be applied");
        }
        boolean hasVendorItems = cart.getItems().stream()
                .anyMatch(i -> i.getVendor() != null && i.getVendor().getId().equals(vendor.getId()));
        if (!hasVendorItems) {
            throw new BadRequestException(
                    "This coupon only applies to items from " + vendor.getStoreName());
        }
        return vendor.getId();
    }

    private void validateCoupon(Coupon coupon, Long userId, BigDecimal baseInDisplay, RateTable rates) {
        String reason = couponInvalidReason(coupon, userId);
        if (reason != null) {
            throw new BadRequestException("Coupon cannot be used: " + reason);
        }
        if (coupon.getMinimumOrderAmount() != null) {
            BigDecimal minimum = rates.convert(coupon.getMinimumOrderAmount(), couponCurrency(coupon));
            if (minimum == null) {
                throw new BadRequestException(
                        "Cannot check this coupon's minimum in " + rates.target() + " right now");
            }
            if (baseInDisplay.compareTo(minimum) < 0) {
                throw new BadRequestException(
                        "Minimum spend for this coupon is " + minimum + " " + rates.target());
            }
        }
    }

    /** @return a human-readable reason the coupon is unusable, or null if it is fine */
    private String couponInvalidReason(Coupon coupon, Long userId) {
        if (coupon == null) {
            return "it no longer exists";
        }
        if (!coupon.isActive()) {
            return "it is no longer active";
        }
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())) {
            return "it is not valid yet";
        }
        if (coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt())) {
            return "it has expired";
        }
        if (coupon.getUsageLimit() != null
                && coupon.getUsageCount() != null
                && coupon.getUsageCount() >= coupon.getUsageLimit()) {
            return "it has reached its usage limit";
        }
        // Per-user limits are unenforceable for guests, who have no stable identity
        if (userId != null && coupon.getPerUserLimit() != null
                && couponUsageRepository.countByCouponIdAndUserId(coupon.getId(), userId)
                   >= coupon.getPerUserLimit()) {
            return "you have already used it the maximum number of times";
        }
        return null;
    }

    private String couponCurrency(Coupon coupon) {
        String currency = coupon.getCurrency();
        return (currency == null || currency.isBlank()) ? defaultCurrency : currency.toUpperCase();
    }

    /**
     * Subtotal the coupon would be measured against, in display currency —
     * the whole cart for a platform coupon, one vendor's lines for a vendor coupon.
     */
    private BigDecimal couponBase(Cart cart, Long vendorId, RateTable rates) {
        return cart.getItems().stream()
                .filter(i -> vendorId == null
                        || (i.getVendor() != null && i.getVendor().getId().equals(vendorId)))
                .map(i -> rates.convert(i.nativeLineTotal(), i.getUnitPriceCurrency()))
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Discount a coupon yields against {@code base}, in display currency.
     * Always clamped to {@code [0, base]} so a coupon can never make a group negative.
     */
    private BigDecimal couponDiscount(Coupon coupon, BigDecimal base, RateTable rates) {
        if (coupon == null || base.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal discount;
        switch (coupon.getType()) {
            case PERCENTAGE -> {
                discount = base.multiply(coupon.getValue())
                        .divide(HUNDRED, RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
                BigDecimal cap = rates.convert(coupon.getMaximumDiscountAmount(), couponCurrency(coupon));
                if (cap != null) {
                    discount = discount.min(cap);
                }
            }
            case FIXED_AMOUNT -> {
                BigDecimal value = rates.convert(coupon.getValue(), couponCurrency(coupon));
                // No rate for the coupon's currency means we cannot price it honestly
                discount = value != null ? value : BigDecimal.ZERO;
            }
            // Shipping is not modelled in the cart yet, so this discounts nothing here
            case FREE_SHIPPING -> discount = BigDecimal.ZERO;
            default -> discount = BigDecimal.ZERO;
        }

        return discount.max(BigDecimal.ZERO).min(base);
    }

    // ── Currency ──────────────────────────────────────────────────────────────

    private String resolveCurrency(String requested, Cart cart) {
        if (requested != null && !requested.isBlank()) {
            return requireCurrencyCode(requested);
        }
        if (cart != null && cart.getDisplayCurrency() != null && !cart.getDisplayCurrency().isBlank()) {
            return cart.getDisplayCurrency().toUpperCase();
        }
        return defaultCurrency.toUpperCase();
    }

    private String requireCurrencyCode(String code) {
        if (code == null || code.trim().length() != 3 || !code.trim().chars().allMatch(Character::isLetter)) {
            throw new BadRequestException("Currency must be a 3-letter ISO 4217 code");
        }
        return code.trim().toUpperCase();
    }

    /**
     * One rate lookup covering every currency this cart touches — vendor listing
     * currencies plus any applied coupon's currency.
     */
    private RateTable buildRateTable(Cart cart, String target, Coupon extraCoupon) {
        Set<String> currencies = new HashSet<>();
        cart.getItems().forEach(i -> currencies.add(
                i.getUnitPriceCurrency() != null ? i.getUnitPriceCurrency().toUpperCase() : defaultCurrency));
        cart.getAppliedCoupons().forEach(cc -> currencies.add(couponCurrency(cc.getCoupon())));
        if (extraCoupon != null) {
            currencies.add(couponCurrency(extraCoupon));
        }
        currencies.remove(target); // identity rate, no lookup needed

        Map<String, BigDecimal> rates = currencies.isEmpty()
                ? Map.of()
                : exchangeRateService.getLatestRates(target, currencies);

        return new RateTable(target, rates);
    }

    // ── Response assembly ─────────────────────────────────────────────────────

    private CartResponse present(Cart cart, String requestedCurrency) {
        hydrate(cart);

        String currency = resolveCurrency(requestedCurrency, cart);
        Long userId = cart.getUser() != null ? cart.getUser().getId() : null;

        Map<Long, List<CartIssue>> itemIssues = revalidate(cart);
        List<CartIssue> cartIssues = new ArrayList<>(revalidateCoupons(cart, userId));

        RateTable rates = buildRateTable(cart, currency, null);
        List<VendorGroup> groups = buildVendorGroups(cart, rates, itemIssues, cartIssues);

        applyDiscounts(cart, groups, rates);

        boolean totalsComplete = groups.stream().allMatch(VendorGroup::isConvertible);

        BigDecimal subtotal = sum(groups, VendorGroup::getSubtotal);
        BigDecimal discount = sum(groups, VendorGroup::getDiscount);

        return CartResponse.builder()
                .cartId(cart.getId())
                .sessionId(cart.isGuestCart() ? cart.getSessionId() : null)
                .guest(cart.isGuestCart() ? Boolean.TRUE : null)
                .displayCurrency(currency)
                .pricedAt(LocalDateTime.now())
                .totalsComplete(totalsComplete)
                .vendors(groups)
                .subtotal(subtotal)
                .discount(discount)
                .total(subtotal.subtract(discount))
                .itemCount(cart.getItems().stream().mapToInt(CartItem::getQuantity).sum())
                .lineCount(cart.getItems().size())
                .platformCouponCode(cart.platformCoupon()
                        .map(cc -> cc.getCoupon().getCode()).orElse(null))
                .issues(cartIssues.isEmpty() ? null : cartIssues)
                .build();
    }

    private List<VendorGroup> buildVendorGroups(Cart cart, RateTable rates,
                                                Map<Long, List<CartIssue>> itemIssues,
                                                List<CartIssue> cartIssues) {
        // LinkedHashMap keeps a stable vendor order across reloads
        Map<Long, List<CartItem>> byVendor = cart.getItems().stream()
                .filter(i -> i.getVendor() != null)
                .collect(Collectors.groupingBy(i -> i.getVendor().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        List<VendorGroup> groups = new ArrayList<>(byVendor.size());

        for (Map.Entry<Long, List<CartItem>> entry : byVendor.entrySet()) {
            List<CartItem> items = entry.getValue();
            Vendor vendor = items.get(0).getVendor();

            // A vendor may list across several currencies. Only claim a single
            // native currency (and a native subtotal) when there genuinely is one,
            // otherwise those figures would be a sum of unlike units.
            Set<String> groupCurrencies = items.stream()
                    .map(CartItem::getUnitPriceCurrency)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            boolean singleCurrency = groupCurrencies.size() == 1;
            String nativeCurrency = singleCurrency ? groupCurrencies.iterator().next() : null;
            boolean convertible = groupCurrencies.stream().allMatch(rates::canConvert);

            List<CartItemResponse> itemResponses = new ArrayList<>(items.size());
            BigDecimal subtotalNative = BigDecimal.ZERO;
            BigDecimal subtotal = BigDecimal.ZERO;
            boolean checkoutable = true;

            for (CartItem item : items) {
                List<CartIssue> issues = itemIssues.getOrDefault(item.getId(), List.of());
                boolean purchasable = issues.stream().noneMatch(CartServiceImpl::blocksCheckout);
                checkoutable &= purchasable;

                BigDecimal lineNative = item.nativeLineTotal();
                BigDecimal lineConverted = rates.convert(lineNative, item.getUnitPriceCurrency());

                subtotalNative = subtotalNative.add(lineNative);
                if (lineConverted != null) {
                    // Sum the rounded line totals so the displayed figures add up exactly
                    subtotal = subtotal.add(lineConverted);
                }

                itemResponses.add(CartItemResponse.builder()
                        .itemId(item.getId())
                        .productId(item.getProduct().getId())
                        .productName(item.getProduct().getName())
                        .productSlug(item.getProduct().getSlug())
                        .imageUrl(primaryImageUrl(item.getProduct()))
                        .vendorId(vendor.getId())
                        .variantId(item.variantIdOrNull())
                        .variantSku(item.getVariant() != null ? item.getVariant().getSku() : null)
                        .variantLabel(variantLabel(item.getVariant()))
                        .quantity(item.getQuantity())
                        .availableStock(availableStock(item.getProduct(), item.getVariant()))
                        .nativeCurrency(item.getUnitPriceCurrency())
                        .unitPriceNative(item.getUnitPrice())
                        .lineTotalNative(lineNative)
                        .unitPrice(rates.convert(item.getUnitPrice(), item.getUnitPriceCurrency()))
                        .lineTotal(lineConverted)
                        .purchasable(purchasable)
                        .issues(issues.isEmpty() ? null : issues)
                        .build());
            }

            if (!convertible) {
                String missing = groupCurrencies.stream()
                        .filter(c -> !rates.canConvert(c))
                        .collect(Collectors.joining(", "));
                cartIssues.add(CartIssue.builder()
                        .type(CartIssueType.RATE_UNAVAILABLE)
                        .vendorId(vendor.getId())
                        .message("No " + missing + " to " + rates.target()
                                + " rate available, so this seller's items are shown in "
                                + missing + " only")
                        .build());
                checkoutable = false;
            }

            groups.add(VendorGroup.builder()
                    .vendorId(vendor.getId())
                    .storeName(vendor.getStoreName())
                    .storeSlug(vendor.getStoreSlug())
                    .logoUrl(vendor.getLogoUrl())
                    .nativeCurrency(nativeCurrency)
                    .exchangeRate(singleCurrency ? rates.rateFor(nativeCurrency) : null)
                    .convertible(convertible)
                    .items(itemResponses)
                    .subtotalNative(singleCurrency ? subtotalNative : null)
                    .subtotal(convertible ? subtotal : BigDecimal.ZERO)
                    .checkoutable(checkoutable)
                    .vendorCouponCode(cart.vendorCoupon(vendor.getId())
                            .map(cc -> cc.getCoupon().getCode()).orElse(null))
                    .build());
        }
        return groups;
    }

    /**
     * Vendor coupons first, then the platform coupon on what remains — so the two
     * cannot stack past the cart value — with the platform discount prorated
     * across vendor groups by their share of the post-vendor-discount subtotal.
     * Proration matters because each group's share is what the vendor's payout
     * gets reduced by.
     */
    private void applyDiscounts(Cart cart, List<VendorGroup> groups, RateTable rates) {
        List<VendorGroup> priced = groups.stream().filter(VendorGroup::isConvertible).toList();

        // Pass 1 — vendor-funded coupons
        Map<Long, BigDecimal> afterVendor = new LinkedHashMap<>();
        for (VendorGroup group : priced) {
            BigDecimal vendorDiscount = cart.vendorCoupon(group.getVendorId())
                    .map(cc -> couponDiscount(cc.getCoupon(), group.getSubtotal(), rates))
                    .orElse(BigDecimal.ZERO);
            group.setDiscount(vendorDiscount);
            afterVendor.put(group.getVendorId(), group.getSubtotal().subtract(vendorDiscount));
        }

        // Pass 2 — platform-funded coupon, prorated
        BigDecimal platformBase = afterVendor.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal platformDiscount = cart.platformCoupon()
                .map(cc -> couponDiscount(cc.getCoupon(), platformBase, rates))
                .orElse(BigDecimal.ZERO);

        if (platformDiscount.signum() > 0 && platformBase.signum() > 0) {
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 0; i < priced.size(); i++) {
                VendorGroup group = priced.get(i);
                BigDecimal share;
                if (i == priced.size() - 1) {
                    // Last group absorbs the rounding remainder so the parts sum exactly
                    share = platformDiscount.subtract(allocated);
                } else {
                    share = platformDiscount
                            .multiply(afterVendor.get(group.getVendorId()))
                            .divide(platformBase, RateTable.MONEY_SCALE, RoundingMode.HALF_UP);
                    allocated = allocated.add(share);
                }
                share = share.max(BigDecimal.ZERO).min(afterVendor.get(group.getVendorId()));
                group.setPlatformDiscountShare(share);
                group.setDiscount(group.getDiscount().add(share));
            }
        }

        for (VendorGroup group : groups) {
            if (!group.isConvertible()) {
                group.setSubtotal(null);
                group.setDiscount(null);
                group.setTotal(null);
                continue;
            }
            group.setTotal(group.getSubtotal().subtract(group.getDiscount()));

            // Native figures are only meaningful when no conversion took place
            if (group.getNativeCurrency() != null
                    && group.getNativeCurrency().equalsIgnoreCase(rates.target())) {
                group.setDiscountNative(group.getDiscount());
                group.setTotalNative(group.getTotal());
            }
        }
    }

    private CartResponse emptyResponse(CartOwner owner, String currency) {
        return CartResponse.builder()
                .sessionId(owner.isGuest() ? owner.sessionId() : null)
                .guest(owner.isGuest() ? Boolean.TRUE : null)
                .displayCurrency(currency)
                .pricedAt(LocalDateTime.now())
                .totalsComplete(true)
                .vendors(List.of())
                .subtotal(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .itemCount(0)
                .lineCount(0)
                .build();
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static boolean blocksCheckout(CartIssue issue) {
        return switch (issue.getType()) {
            case OUT_OF_STOCK, PRODUCT_UNAVAILABLE, VARIANT_UNAVAILABLE, VENDOR_UNAVAILABLE -> true;
            // A price change or a clamped quantity is informational; the line is still buyable
            default -> false;
        };
    }

    private static BigDecimal sum(List<VendorGroup> groups,
                                  java.util.function.Function<VendorGroup, BigDecimal> field) {
        return groups.stream()
                .map(field)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static CartIssue itemIssue(CartItem item, CartIssueType type, String message) {
        return CartIssue.builder()
                .type(type)
                .itemId(item.getId())
                .vendorId(item.getVendor() != null ? item.getVendor().getId() : null)
                .message(message)
                .build();
    }

    private static String safeName(Product product) {
        return product != null && product.getName() != null ? product.getName() : "This item";
    }

    private static String primaryImageUrl(Product product) {
        if (product == null || product.getImages() == null) return null;
        return product.getImages().stream()
                .filter(ProductImage::isDefault)
                .findFirst()
                .or(() -> product.getImages().stream()
                        .min(Comparator.comparing(ProductImage::getSortOrder,
                                Comparator.nullsLast(Comparator.naturalOrder()))))
                .map(ProductImage::getImageUrl)
                .orElse(null);
    }

    /**
     * Built from the option values alone — deliberately not from
     * {@code value.getOption().getName()}, which would lazy-load one extra row
     * per option on every cart read.
     */
    private static String variantLabel(ProductVariant variant) {
        if (variant == null || variant.getSelectedValues() == null
                || variant.getSelectedValues().isEmpty()) {
            return null;
        }
        return variant.getSelectedValues().stream()
                .sorted(Comparator.comparing(ProductOptionValue::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(ProductOptionValue::getDisplayValue)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(", "));
    }
}
