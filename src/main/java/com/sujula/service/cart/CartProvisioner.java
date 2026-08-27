package com.sujula.service.cart;

import com.sujula.exceptions.ResourceNotFoundException;
import com.sujula.model.order.Cart;
import com.sujula.model.user.User;
import com.sujula.repository.order.CartRepository;
import com.sujula.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Creates carts in their own transaction.
 *
 * <p>Two concurrent first-time requests from the same shopper both see "no cart
 * yet" and both insert, so one loses to the unique index on {@code user_id} /
 * {@code session_id}. A constraint violation poisons the persistence context it
 * happens in, so the insert is isolated in a {@code REQUIRES_NEW} transaction:
 * the loser's transaction rolls back on its own and the caller simply re-reads
 * the winner's row.
 */
@Component
@RequiredArgsConstructor
public class CartProvisioner {

    private final CartRepository cartRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Cart createUserCart(Long userId, String displayCurrency) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        return cartRepository.saveAndFlush(Cart.builder()
                .user(user)
                .displayCurrency(displayCurrency)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Cart createGuestCart(String sessionId, String displayCurrency, int ttlDays) {
        return cartRepository.saveAndFlush(Cart.builder()
                .sessionId(sessionId)
                .displayCurrency(displayCurrency)
                .expiresAt(LocalDateTime.now().plusDays(ttlDays))
                .build());
    }
}
