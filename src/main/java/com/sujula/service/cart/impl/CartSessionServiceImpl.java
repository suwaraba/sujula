package com.sujula.service.cart.impl;

import com.sujula.dto.request.cart.CartRequests;
import com.sujula.dto.request.order.ApplyCouponRequest;
import com.sujula.dto.request.order.CartItemRequest;
import com.sujula.dto.response.cart.CartQuoteResponse;
import com.sujula.dto.response.order.CartResponse;
import com.sujula.exceptions.BadRequestException;
import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.constant.DeliveryMode;
import com.sujula.model.money.FxSnapshot;
import com.sujula.model.order.Cart;
import com.sujula.model.order.CartQuote;
import com.sujula.model.order.CartQuoteLine;
import com.sujula.model.user.User;
import com.sujula.repository.order.CartQuoteRepository;
import com.sujula.repository.order.CartRepository;
import com.sujula.repository.user.UserRepository;
import com.sujula.service.CartService;
import com.sujula.service.cart.CartOwner;
import com.sujula.service.cart.CartSessionService;
import com.sujula.service.delivery.DeliveryContextService;
import com.sujula.service.reference.CurrencyCatalogue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The addressable cart, and the quote that makes checkout honest.
 *
 * <p>Pricing is delegated to {@link CartService} rather than reimplemented.
 * There is one priced cart in this application, and a second one here would
 * eventually disagree with it — which is the same class of bug as quoting a
 * shopper one total and charging another, only harder to find.
 */
@Slf4j
@Service
public class CartSessionServiceImpl implements CartSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** How long a held quote is honoured. */
    @Value("${sujula.cart.quote-ttl:PT15M}")
    private Duration quoteTtl;

    @Value("${sujula.cart.guest-ttl-days:30}")
    private int guestTtlDays;

    private final CartRepository carts;
    private final CartQuoteRepository quotes;
    private final UserRepository users;
    private final CartService cartService;
    private final DeliveryContextService deliveryContexts;
    private final CurrencyCatalogue currencies;

    public CartSessionServiceImpl(CartRepository carts, CartQuoteRepository quotes,
                                  UserRepository users, CartService cartService,
                                  DeliveryContextService deliveryContexts,
                                  CurrencyCatalogue currencies) {
        this.carts = carts;
        this.quotes = quotes;
        this.users = users;
        this.cartService = cartService;
        this.deliveryContexts = deliveryContexts;
        this.currencies = currencies;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse create(CartRequests.Create request, Long userId) {
        String currency = request.currency() == null || request.currency().isBlank()
                ? currencies.baseCurrency()
                : currencies.require(request.currency());

        Cart cart = Cart.builder()
                .token(newToken())
                .displayCurrency(currency)
                .deliveryContextId(blankToNull(request.deliveryContextId()))
                .build();

        if (userId != null) {
            cart.setUser(users.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId)));
        } else {
            // A guest cart still needs the session id the older surface keys on,
            // so the same basket is reachable from both. It expires; a user cart
            // does not.
            cart.setSessionId(UUID.randomUUID().toString());
            cart.setExpiresAt(LocalDateTime.now().plusDays(guestTtlDays));
        }

        Cart saved = carts.save(cart);
        log.debug("[Cart] Opened {} for {}", saved.getToken(),
                userId == null ? "a guest" : "user " + userId);
        return present(saved, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse get(String token, Long userId) {
        return present(require(token, userId), userId);
    }

    // ── Items ────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse addItem(String token, Long userId, CartRequests.AddItem request) {
        Cart cart = require(token, userId);
        cartService.addItem(ownerOf(cart), CartItemRequest.builder()
                .productId(request.productId())
                .variantId(request.variantId())
                .quantity(request.quantity())
                .build(), cart.getDisplayCurrency());
        return present(reload(cart), userId);
    }

    @Override
    @Transactional
    public CartResponse updateQuantity(String token, Long userId, Long itemId,
                                       CartRequests.UpdateQuantity request) {
        Cart cart = require(token, userId);

        if (request.quantity() == 0) {
            // A stepper stepped to zero means remove. Treating it as an invalid
            // quantity makes a client special-case the last decrement.
            cartService.removeItem(ownerOf(cart), itemId, cart.getDisplayCurrency());
        } else {
            cartService.updateItem(ownerOf(cart), itemId,
                    CartItemRequest.builder().quantity(request.quantity()).build(),
                    cart.getDisplayCurrency());
        }
        return present(reload(cart), userId);
    }

    @Override
    @Transactional
    public CartResponse removeItem(String token, Long userId, Long itemId) {
        Cart cart = require(token, userId);
        cartService.removeItem(ownerOf(cart), itemId, cart.getDisplayCurrency());
        return present(reload(cart), userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The shopper filled a basket before signing in, which is the normal order
     * of events on a marketplace people reach from a link. Their account cart is
     * kept and the anonymous one folded into it — both were filled by the same
     * person, and discarding either loses something they chose.
     */
    @Override
    @Transactional
    public CartResponse merge(String targetToken, Long userId, CartRequests.Merge request) {
        if (userId == null) {
            throw new BadRequestException("Merging a cart requires signing in first.");
        }
        Cart source = carts.findByToken(request.sourceCartToken())
                .orElseThrow(() -> new ResourceNotFoundException("Cart", request.sourceCartToken()));

        if (source.getUser() != null && !source.getUser().getId().equals(userId)) {
            // Somebody else's cart. Not-found rather than forbidden: confirming
            // it exists tells whoever guessed the token that they guessed right.
            throw new ResourceNotFoundException("Cart", request.sourceCartToken());
        }
        if (source.getSessionId() == null) {
            throw new BadRequestException("That cart is not an anonymous cart.");
        }

        cartService.mergeGuestCart(userId, source.getSessionId(), source.getDisplayCurrency());

        Cart target = carts.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart", userId));

        // The destination survives the merge: a shopper who set one before
        // signing in should not have to set it again.
        if (target.getToken() == null) {
            target.setToken(newToken());
        }
        if (target.getDeliveryContextId() == null) {
            target.setDeliveryContextId(source.getDeliveryContextId());
        }
        carts.save(target);

        return present(target, userId);
    }

    // ── The two independent settings ─────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Touches the destination and nothing else. A buyer in Madrid who switches
     * the recipient from Serrekunda to Dakar is still paying with a European
     * card, and re-deriving their currency from the new destination would quote
     * them in a currency they cannot pay with.
     */
    @Override
    @Transactional
    public CartResponse setDeliveryContext(String token, Long userId,
                                           CartRequests.SetDeliveryContext request) {
        Cart cart = require(token, userId);

        // Resolved through the service so its ownership and expiry rules apply:
        // a context belonging to somebody else is not found.
        deliveryContexts.require(request.deliveryContextId().trim(), userId);

        cart.setDeliveryContextId(request.deliveryContextId().trim());
        carts.save(cart);

        // The read re-prices: delivery legs and per-line serviceability are
        // computed from the destination every time the cart is presented.
        return present(cart, userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Touches what the prices read as and nothing else. The parcel goes where
     * it was always going.
     */
    @Override
    @Transactional
    public CartResponse setCurrency(String token, Long userId, CartRequests.SetCurrency request) {
        Cart cart = require(token, userId);
        String currency = currencies.require(request.currency());

        cartService.setDisplayCurrency(ownerOf(cart), currency);
        return present(reload(cart), userId);
    }

    // ── Coupons ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartResponse applyCoupon(String token, Long userId, CartRequests.ApplyCoupon request) {
        Cart cart = require(token, userId);
        cartService.applyCoupon(ownerOf(cart),
                ApplyCouponRequest.builder().couponCode(request.code()).build(),
                cart.getDisplayCurrency());
        return present(reload(cart), userId);
    }

    @Override
    @Transactional
    public CartResponse removeCoupon(String token, Long userId, String code) {
        Cart cart = require(token, userId);
        Long vendorId = cart.getAppliedCoupons().stream()
                .filter(applied -> applied.getCoupon() != null
                        && code.equalsIgnoreCase(applied.getCoupon().getCode()))
                .map(applied -> applied.getVendor() == null ? null : applied.getVendor().getId())
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Coupon", code));

        cartService.removeCoupon(ownerOf(cart), vendorId, cart.getDisplayCurrency());
        return present(reload(cart), userId);
    }

    // ── The quote ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public CartQuoteResponse quote(String token, Long userId, CartRequests.Quote request) {
        Cart cart = require(token, userId);
        CartResponse priced = present(cart, userId);

        if (priced.getVendors() == null || priced.getVendors().isEmpty()) {
            throw new BadRequestException("There is nothing in this cart to quote.");
        }

        List<String> blockers = new ArrayList<>();
        if (!priced.isTotalsComplete()) {
            blockers.add("Some items could not be converted into " + priced.getDisplayCurrency()
                    + " — no exchange rate is available.");
        }
        if (cart.getDeliveryContextId() == null) {
            blockers.add("Set a delivery context before quoting: shipping is priced from where the "
                    + "goods are going.");
        }
        if (Boolean.FALSE.equals(priced.getDeliverable())) {
            blockers.add("Some items cannot be delivered to that destination.");
        }

        DeliveryMode mode = request.deliveryMode() == null
                ? DeliveryMode.HOME_DELIVERY : request.deliveryMode();

        LocalDateTime now = LocalDateTime.now();
        CartQuote quote = CartQuote.builder()
                .id(newToken())
                .cart(cart)
                .user(cart.getUser())
                .cartFingerprint(fingerprintOf(priced))
                .displayCurrency(priced.getDisplayCurrency())
                .deliveryContextId(cart.getDeliveryContextId())
                .deliveryMode(mode)
                .pickupPointId(request.pickupPointId())
                .subtotal(orZero(priced.getSubtotal()))
                .discount(orZero(priced.getDiscount()))
                .shipping(orZero(priced.getShipping()))
                .tax(BigDecimal.ZERO)
                .total(orZero(priced.getTotal()))
                .complete(priced.isTotalsComplete() && blockers.isEmpty())
                .createdAt(now)
                .expiresAt(now.plus(quoteTtl))
                .build();

        List<CartQuoteLine> lines = new ArrayList<>();
        for (CartResponse.VendorGroup group : priced.getVendors()) {
            for (CartResponse.CartItemResponse item : group.getItems()) {
                lines.add(toQuoteLine(quote, group, item, priced.getDisplayCurrency(), now));
            }
        }
        quote.setLines(lines);

        CartQuote saved = quotes.save(quote);
        log.info("[Cart] Quote {} held for cart {} — {} {} until {}",
                saved.getId(), cart.getToken(), saved.getTotal(), saved.getDisplayCurrency(),
                saved.getExpiresAt());

        return toResponse(saved, priced, blockers);
    }

    /**
     * One line, with the rate it was priced at frozen onto it.
     *
     * <p>The rate is taken from the group that priced this line rather than
     * looked up again. A second lookup can return a different number, and a line
     * recording a rate its own figures were not converted at is worse than one
     * recording nothing.
     */
    private static CartQuoteLine toQuoteLine(CartQuote quote, CartResponse.VendorGroup group,
                                             CartResponse.CartItemResponse item,
                                             String displayCurrency, LocalDateTime pricedAt) {
        String listing = item.getNativeCurrency() == null
                ? group.getNativeCurrency() : item.getNativeCurrency();

        FxSnapshot fx = null;
        if (listing != null) {
            fx = listing.equalsIgnoreCase(displayCurrency)
                    ? FxSnapshot.identity(listing, pricedAt)
                    : (group.getExchangeRate() == null ? null
                       : FxSnapshot.published(listing, displayCurrency,
                                              group.getExchangeRate(), pricedAt));
        }

        return CartQuoteLine.builder()
                .quote(quote)
                .productId(item.getProductId())
                .variantId(item.getVariantId())
                .vendorId(group.getVendorId())
                .quantity(item.getQuantity() == null ? 0 : item.getQuantity())
                .listingCurrency(listing)
                .unitPriceNative(item.getUnitPriceNative())
                .lineTotalNative(item.getLineTotalNative())
                .unitPrice(item.getUnitPrice())
                .lineTotal(item.getLineTotal())
                .deliveryCost(orZero(item.getDeliveryCost()))
                .distanceKm(item.getDistanceKm())
                .deliverable(item.getDeliverable() == null || item.getDeliverable())
                .issue(item.isPurchasable() ? null : "This line cannot be checked out as it stands.")
                .fx(fx)
                .build();
    }

    private CartQuoteResponse toResponse(CartQuote quote, CartResponse priced,
                                         List<String> blockers) {
        Map<Long, CartResponse.VendorGroup> groups = new LinkedHashMap<>();
        for (CartResponse.VendorGroup group : priced.getVendors()) {
            groups.put(group.getVendorId(), group);
        }

        List<CartQuoteResponse.Line> lines = quote.getLines().stream()
                .map(line -> new CartQuoteResponse.Line(
                        line.getProductId(), line.getVariantId(), line.getVendorId(),
                        line.getQuantity(), line.getListingCurrency(),
                        line.getUnitPriceNative(), line.getLineTotalNative(),
                        line.getUnitPrice(), line.getLineTotal(),
                        line.getDeliveryCost(), line.getDistanceKm(), line.getBillableWeightKg(),
                        line.getFx() == null ? null : line.getFx().getRate(),
                        line.isDeliverable(), line.getIssue()))
                .toList();

        List<CartQuoteResponse.VendorTotal> vendors = groups.values().stream()
                .map(group -> new CartQuoteResponse.VendorTotal(
                        group.getVendorId(), group.getStoreName(), group.getNativeCurrency(),
                        group.getSubtotal(), group.getShipping(), group.getTotal(),
                        group.getDeliverable() == null || group.getDeliverable()))
                .toList();

        long seconds = Math.max(0,
                Duration.between(LocalDateTime.now(), quote.getExpiresAt()).getSeconds());

        return new CartQuoteResponse(
                quote.getId(), quote.getId(), quote.getCart().getToken(),
                quote.getDisplayCurrency(), quote.getDeliveryContextId(),
                quote.getDeliveryMode(), quote.getPickupPointId(),
                quote.getSubtotal(), quote.getDiscount(), quote.getShipping(),
                quote.getTax(), quote.getTotal(),
                quote.isComplete(),
                blockers.isEmpty(),
                lines, vendors,
                quote.getCreatedAt(), quote.getExpiresAt(), seconds,
                blockers.isEmpty() ? null : blockers);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A cart this caller may use.
     *
     * <p>A cart bound to an account is readable only by that account; an
     * anonymous one belongs to whoever holds the token. Someone else's is
     * reported as not found, because confirming it exists tells a guesser they
     * guessed right — and a cart carries a destination and what a person is
     * about to spend.
     */
    private Cart require(String token, Long userId) {
        Cart cart = carts.findByTokenWithItems(token)
                .orElseThrow(() -> new ResourceNotFoundException("Cart", token));

        if (cart.getUser() != null && !cart.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Cart", token);
        }
        if (cart.getExpiresAt() != null && cart.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResourceNotFoundException("Cart", token);
        }
        return cart;
    }

    private Cart reload(Cart cart) {
        return carts.findByTokenWithItems(cart.getToken()).orElse(cart);
    }

    private CartResponse present(Cart cart, Long userId) {
        CartResponse response = cartService.getCart(ownerOf(cart), cart.getDisplayCurrency());
        // The token is how this surface addresses the cart; the numeric id is
        // not published.
        response.setSessionId(null);
        return response;
    }

    private static CartOwner ownerOf(Cart cart) {
        return cart.getUser() != null
                ? CartOwner.user(cart.getUser().getId())
                : CartOwner.guest(cart.getSessionId());
    }

    /**
     * What the quote was priced for.
     *
     * <p>Checkout compares this against the cart as it stands. A shopper who
     * quotes, opens a second tab, adds a television and then checks out with the
     * first quote would otherwise buy the television at the old total.
     */
    private static String fingerprintOf(CartResponse priced) {
        StringBuilder shape = new StringBuilder();
        for (CartResponse.VendorGroup group : priced.getVendors()) {
            for (CartResponse.CartItemResponse item : group.getItems()) {
                shape.append(item.getProductId()).append(':')
                     .append(item.getVariantId()).append(':')
                     .append(item.getQuantity()).append(';');
            }
        }
        shape.append('|').append(priced.getDisplayCurrency());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(shape.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    /** 256 bits. Sequential would put the next shopper's basket one increment away. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
