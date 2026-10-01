package com.sujula.service.payment;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sujula.model.constant.NotificationEvent;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.constant.PaymentStatus;
import com.sujula.model.order.Order;
import com.sujula.model.order.OrderStatusHistory;
import com.sujula.model.order.Payment;
import com.sujula.model.user.User;
import com.sujula.repository.order.OrderRepository;
import com.sujula.repository.order.OrderStatusHistoryRepository;
import com.sujula.service.EmailService;
import com.sujula.service.NotificationService;

import lombok.extern.slf4j.Slf4j;

/** Canonical in-transaction mutation for every path that receives payment. */
@Slf4j
@Component
public class PaymentSettlementService {

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final EmailService email;
    private final NotificationService notifications;

    public PaymentSettlementService(OrderRepository orders,
                                    OrderStatusHistoryRepository history,
                                    EmailService email,
                                    NotificationService notifications) {
        this.orders = orders;
        this.history = history;
        this.email = email;
        this.notifications = notifications;
    }

    /**
     * Applies the single PAID mutation. Callers must hold Order then Payment
     * locks and must have accepted the transition through the provider policy
     * (or their own stricter administrative authorization).
     */
    public void settle(Payment payment, User confirmedBy, String collectionReference, String note) {
        LocalDateTime paidAt = LocalDateTime.now();
        payment.setStatus(PaymentStatus.PAID);
        payment.setPaidAt(paidAt);
        payment.setFailureReason(null);
        if (collectionReference != null) {
            payment.setCollectionReference(collectionReference);
        }
        if (note != null) {
            payment.setNote(note);
        }
        if (confirmedBy != null) {
            payment.setConfirmedBy(confirmedBy);
        }

        Order order = payment.getOrder();
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(payment.getMethod());
        order.setPaidAt(paidAt);

        if (order.getStatus() == OrderStatus.PENDING) {
            order.setStatus(OrderStatus.CONFIRMED);
            history.save(OrderStatusHistory.builder()
                    .order(order)
                    .fromStatus(OrderStatus.PENDING)
                    .toStatus(OrderStatus.CONFIRMED)
                    .notes("Payment received via " + payment.getMethod().getDisplayName()
                            + " (" + payment.getReference() + ")")
                    .build());
        }
        orders.save(order);
        announceAfterCommit(order, payment);
    }

    private void announceAfterCommit(Order order, Payment payment) {
        Long customerId = order.getCustomer() == null ? null : order.getCustomer().getId();
        String contactEmail = order.getContactEmail();
        String displayName = order.getDisplayName();
        String orderNumber = order.getOrderNumber();
        String method = payment.getMethod().getDisplayName().toLowerCase();

        Runnable dispatch = () -> {
            try {
                if (contactEmail != null && !contactEmail.isBlank()) {
                    email.sendOrderConfirmationEmail(contactEmail, displayName, orderNumber);
                }
            } catch (Exception ex) {
                log.warn("[Payment] Could not email the payment confirmation for {}: {}",
                        orderNumber, ex.getMessage());
            }
            if (customerId != null) {
                try {
                    notifications.send(customerId, "Payment Received",
                            "We have received your " + method + " payment for order "
                                    + orderNumber + ".",
                            NotificationEvent.ORDER_UPDATE, orderNumber);
                } catch (Exception ex) {
                    log.warn("[Payment] Could not notify the buyer of order {}: {}",
                            orderNumber, ex.getMessage());
                }
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatch.run();
                        }
                    });
        } else {
            // Unit tests and non-transactional callers retain deterministic
            // behavior; production settlement methods are transactional.
            dispatch.run();
        }
    }
}
