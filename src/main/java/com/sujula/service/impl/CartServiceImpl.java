package com.sujula.service.impl;

import com.sujula.dto.request.order.CartItemRequest;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartItem;
import com.sujula.model.products.ProductVariant;
import com.sujula.model.user.User;
import com.sujula.repository.order.CartItemRepository;
import com.sujula.repository.order.CartRepository;
import com.sujula.repository.product.ProductRepository;
import com.sujula.repository.product.ProductVariantRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    /** Guest carts expire after 7 days of inactivity. */
    private static final int GUEST_CART_TTL_DAYS = 7;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    // ── Authenticated-user cart ───────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        return toResponse(getOrCreateUserCart(userId));
    }

    @Override
    @Transactional
    public CartResponse addItem(Long userId, CartItemRequest request) {
        return toResponse(doAddItem(getOrCreateUserCart(userId), request));
    }

    @Override
    @Transactional
    public CartResponse updateItem(Long userId, Long cartItemId, CartItemRequest request) {
        return toResponse(doUpdateItem(getOrCreateUserCart(userId), cartItemId, request));
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long userId, Long cartItemId) {
        return toResponse(doRemoveItem(getOrCreateUserCart(userId), cartItemId));
    }

//    @Override
//    @Transactional
//    public CartResponse applyCoupon(Long userId, ApplyCouponRequest request) {
//        return toResponse(doApplyCoupon(getOrCreateUserCart(userId), request, userId));
//    }
//
//    @Override
//    @Transactional
//    public CartResponse removeCoupon(Long userId) {
//        return toResponse(doRemoveCoupon(getOrCreateUserCart(userId)));
//    }

    @Override
    @Transactional
    public void clearCart(Long userId) {
        Cart cart = getOrCreateUserCart(userId);
        cart.getItems().clear();
        cart.setCoupon(null);
        cartRepository.save(cart);
    }

    // ── Guest cart ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse getGuestCart(String sessionId) {
        return toResponse(getOrCreateGuestCart(sessionId));
    }

    @Override
    @Transactional
    public CartResponse addItemGuest(String sessionId, CartItemRequest request) {
        return toResponse(doAddItem(getOrCreateGuestCart(sessionId), request));
    }

    @Override
    @Transactional
    public CartResponse updateItemGuest(String sessionId, Long cartItemId, CartItemRequest request) {
        return toResponse(doUpdateItem(getOrCreateGuestCart(sessionId), cartItemId, request));
    }

    @Override
    @Transactional
    public CartResponse removeItemGuest(String sessionId, Long cartItemId) {
        return toResponse(doRemoveItem(getOrCreateGuestCart(sessionId), cartItemId));
    }

    @Override
    @Transactional
    public com.sujula.dto.response.order.CartResponse applyCouponGuest(String sessionId, ApplyCouponRequest request) {
        // Guest has no userId — per-user coupon limits are skipped (null userId)
        return toResponse(doApplyCoupon(getOrCreateGuestCart(sessionId), request, null));
    }

    @Override
    @Transactional
    public CartResponse removeCouponGuest(String sessionId) {
        return toResponse(doRemoveCoupon(getOrCreateGuestCart(sessionId)));
    }

    // ── Cart merge ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse mergeGuestCart(Long userId, String sessionId) {
        Optional<Cart> guestOpt = cartRepository.findBySessionId(sessionId);
        if (guestOpt.isEmpty()) {
            // Nothing to merge — return the existing user cart as-is
            return toResponse(getOrCreateUserCart(userId));
        }

        Cart guestCart = guestOpt.get();
        Cart userCart  = getOrCreateUserCart(userId);

        for (CartItem guestItem : guestCart.getItems()) {
            Long guestProductId = guestItem.getProduct().getId();
            Long guestVariantId = guestItem.getVariant() != null
                    ? guestItem.getVariant().getId() : null;

            Optional<CartItem> existing = userCart.getItems().stream()
                    .filter(ui -> ui.getProduct().getId().equals(guestProductId)
                            && variantIdsMatch(ui.getVariant(), guestVariantId))
                    .findFirst();

            if (existing.isPresent()) {
                // Both carts have this item — take the higher quantity (no silent doubling)
                CartItem userItem  = existing.get();
                int      mergedQty = Math.max(userItem.getQuantity(), guestItem.getQuantity());
                userItem.setQuantity(mergedQty);
                cartItemRepository.save(userItem);
            } else {
                // Only in guest cart — move to user cart
                CartItem newItem = CartItem.builder()
                        .cart(userCart)
                        .product(guestItem.getProduct())
                        .variant(guestItem.getVariant())
                        .quantity(guestItem.getQuantity())
                        .unitPrice(guestItem.getUnitPrice())
                        .build();
                userCart.getItems().add(cartItemRepository.save(newItem));
            }
        }

        // Transfer guest coupon only if user cart has no coupon applied
        if (userCart.getCoupon() == null && guestCart.getCoupon() != null) {
            userCart.setCoupon(guestCart.getCoupon());
        }

        cartRepository.save(userCart);

        // Delete the guest cart — it has been fully consumed
        cartRepository.delete(guestCart);

        return toResponse(userCart);
    }

    // ── Core cart operations (cart-type-agnostic) ─────────────────────────────

    private Cart doAddItem(Cart cart, CartItemRequest request) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", request.getProductId()));

        if (!product.isActive()) {
            throw new BadRequestException("Product is not available: " + product.getName());
        }

        ProductVariant variant   = null;
        BigDecimal     unitPrice = product.getPrice();

        if (request.getVariantId() != null) {
            variant = variantRepository.findById(request.getVariantId())
                    .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", request.getVariantId()));
            if (!variant.getProduct().getId().equals(product.getId())) {
                throw new BadRequestException("Variant does not belong to this product");
            }
            if (!variant.isAvailable()) {
                throw new BadRequestException("Selected variant is unavailable");
            }
            if (variant.getPrice() != null) {
                unitPrice = variant.getPrice();
            }
        }

        int availableStock = (variant != null)
                ? variant.getStockQuantity() : product.getStockQuantity();
        if (availableStock < request.getQuantity()) {
            throw new BadRequestException("Insufficient stock. Available: " + availableStock);
        }

        final ProductVariant finalVariant = variant;
        Optional<CartItem> existing = cart.getItems().stream()
                .filter(i -> i.getProduct().getId().equals(product.getId())
                        && variantIdsMatch(i.getVariant(),
                            finalVariant != null ? finalVariant.getId() : null))
                .findFirst();

        if (existing.isPresent()) {
            CartItem item   = existing.get();
            int      newQty = item.getQuantity() + request.getQuantity();
            if (availableStock < newQty) {
                throw new BadRequestException("Cannot add more. Available stock: " + availableStock);
            }
            item.setQuantity(newQty);
            cartItemRepository.save(item);
        } else {
            CartItem newItem = CartItem.builder()
                    .cart(cart)
                    .product(product)
                    .variant(variant)
                    .quantity(request.getQuantity())
                    .unitPrice(unitPrice)
                    .build();
            cart.getItems().add(cartItemRepository.save(newItem));
        }

        return refreshTtl(cartRepository.save(cart));
    }

    private Cart doUpdateItem(Cart cart, Long cartItemId, CartItemRequest request) {
        CartItem item = cart.getItems().stream()
                .filter(i -> i.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", cartItemId));

        int stock = (item.getVariant() != null)
                ? item.getVariant().getStockQuantity()
                : item.getProduct().getStockQuantity();

        if (request.getQuantity() > stock) {
            throw new BadRequestException("Requested quantity exceeds available stock: " + stock);
        }

        item.setQuantity(request.getQuantity());
        cartItemRepository.save(item);
        return refreshTtl(cart);
    }

    private Cart doRemoveItem(Cart cart, Long cartItemId) {
        CartItem item = cart.getItems().stream()
                .filter(i -> i.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("CartItem", cartItemId));

        cart.getItems().remove(item);
        cartItemRepository.delete(item);
        return refreshTtl(cartRepository.save(cart));
    }

    private Cart doApplyCoupon(Cart cart, ApplyCouponRequest request, Long userId) {
        Coupon coupon = couponRepository.findByCode(request.getCouponCode().toUpperCase().trim())
                .orElseThrow(() -> new BadRequestException("Invalid coupon code"));

        validateCoupon(coupon, userId, calculateSubtotal(cart));
        cart.setCoupon(coupon);
        return cartRepository.save(cart);
    }

    private Cart doRemoveCoupon(Cart cart) {
        cart.setCoupon(null);
        return cartRepository.save(cart);
    }

    // ── Cart resolution ───────────────────────────────────────────────────────

    private Cart getOrCreateUserCart(Long userId) {
        return cartRepository.findByUserId(userId).orElseGet(() -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId));
            return cartRepository.save(Cart.builder().user(user).build());
        });
    }

    /**
     * Finds an existing guest cart or creates a fresh one with a 7-day TTL.
     */
    private Cart getOrCreateGuestCart(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BadRequestException("X-Session-Id header is required for guest cart access");
        }
        return cartRepository.findBySessionId(sessionId).orElseGet(() ->
            cartRepository.save(Cart.builder()
                    .sessionId(sessionId)
                    .expiresAt(LocalDateTime.now().plusDays(GUEST_CART_TTL_DAYS))
                    .build())
        );
    }

    /** Refreshes the TTL on every mutation; no-op for user carts. */
    private Cart refreshTtl(Cart cart) {
        if (cart.getUser() == null && cart.getSessionId() != null) {
            cart.setExpiresAt(LocalDateTime.now().plusDays(GUEST_CART_TTL_DAYS));
            return cartRepository.save(cart);
        }
        return cart;
    }

    // ── Coupon validation ─────────────────────────────────────────────────────

//    private void validateCoupon(Coupon coupon, Long userId, BigDecimal subtotal) {
//        if (!coupon.isActive()) {
//            throw new BadRequestException("Coupon is no longer active");
//        }
//        LocalDateTime now = LocalDateTime.now();
//        if (coupon.getStartsAt() != null && now.isBefore(coupon.getStartsAt())) {
//            throw new BadRequestException("Coupon is not yet valid");
//        }
//        if (coupon.getExpiresAt() != null && now.isAfter(coupon.getExpiresAt())) {
//            throw new BadRequestException("Coupon has expired");
//        }
//        if (coupon.getMinimumOrderAmount() != null
//                && subtotal.compareTo(coupon.getMinimumOrderAmount()) < 0) {
//            throw new BadRequestException(
//                "Minimum order amount for this coupon is " + coupon.getMinimumOrderAmount());
//        }
//        if (coupon.getUsageLimit() != null && coupon.getUsageCount() >= coupon.getUsageLimit()) {
//            throw new BadRequestException("Coupon usage limit has been reached");
//        }
//        // Per-user limit check only applies to authenticated users
//        if (userId != null && coupon.getPerUserLimit() != null) {
//            long used = couponUsageRepository.countByCouponIdAndUserId(coupon.getId(), userId);
//            if (used >= coupon.getPerUserLimit()) {
//                throw new BadRequestException(
//                    "You have already used this coupon the maximum number of times");
//            }
//        }
//    }

    // ── Totals ────────────────────────────────────────────────────────────────

    private BigDecimal calculateSubtotal(Cart cart) {
        return cart.getItems().stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

//    private BigDecimal calculateDiscount(Cart cart, BigDecimal subtotal) {
//        Coupon coupon = cart.getCoupon();
//        if (coupon == null) return BigDecimal.ZERO;
//
//        BigDecimal discount;
//        if (coupon.getType() == CouponType.PERCENTAGE) {
//            discount = subtotal.multiply(coupon.getValue())
//                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
//            if (coupon.getMaximumDiscountAmount() != null) {
//                discount = discount.min(coupon.getMaximumDiscountAmount());
//            }
//        } else {
//            discount = coupon.getValue().min(subtotal);
//        }
//        return discount;
//    }

    // ── Response builder ──────────────────────────────────────────────────────

    private CartResponse toResponse(Cart cart) {
        BigDecimal subtotal = calculateSubtotal(cart);
        //BigDecimal discount = calculateDiscount(cart, subtotal);
        //BigDecimal total    = subtotal.subtract(discount);

        List<CartResponse.CartItemResponse> itemResponses = cart.getItems().stream()
                .map(item -> {
                    String imageUrl = item.getProduct().getImages().stream()
                            .filter(ProductImage::isDefault)
                            .findFirst()
                            .or(() -> item.getProduct().getImages().stream().findFirst())
                            .map(ProductImage::getImageUrl)
                            .orElse(null);

                    int stock = (item.getVariant() != null)
                            ? item.getVariant().getStockQuantity()
                            : item.getProduct().getStock();

                    String priceCurrency = item.getProduct().getPriceCurrency() != null
                            ? item.getProduct().getPriceCurrency() : "GMD";

                    return CartResponse.CartItemResponse.builder()
                            .itemId(item.getId())
                            .productId(item.getProduct().getId())
                            .productName(item.getProduct().getName())
                            .productSlug(item.getProduct().getSlug())
                            .imageUrl(imageUrl)
                            .variantId(item.getVariant() != null ? item.getVariant().getId() : null)
                            .variantSku(item.getVariant() != null ? item.getVariant().getSku() : null)
                            .quantity(item.getQuantity())
                            .unitPrice(item.getUnitPrice())
                            .totalPrice(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .availableStock(stock)
                            .priceCurrency(priceCurrency)
                            .build();
                })
                .toList();

        boolean isGuest = (cart.getUser() == null);

        return CartResponse.builder()
                .cartId(cart.getId())
                .sessionId(isGuest ? cart.getSessionId() : null)
                .guest(isGuest ? Boolean.TRUE : null)
                .items(itemResponses)
                .couponCode(cart.getCoupon() != null ? cart.getCoupon().getCode() : null)
                .subtotal(subtotal)
                .discount(discount)
                .total(total)
                .itemCount(cart.getItems().size())
                .currency("GMD")
                .build();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Null-safe variant ID comparison.
     * Both null → match (product with no variant selected).
     * One null  → no match.
     */
    private boolean variantIdsMatch(ProductVariant variant, Long targetVariantId) {
        if (variant == null && targetVariantId == null) return true;
        if (variant == null || targetVariantId == null) return false;
        return variant.getId().equals(targetVariantId);
    }
}
