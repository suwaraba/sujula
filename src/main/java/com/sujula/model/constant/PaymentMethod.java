package com.sujula.model.constant;

/**
 * How a buyer pays for an order.
 *
 * <p>Each method belongs to a {@link PaymentChannel}, and the channel — not the
 * method — decides how the money is confirmed: an online method is confirmed by
 * the gateway callback, a transfer by finance/admin matching a reference, and an
 * in-person method by whoever hands the goods over.
 */
public enum PaymentMethod {

    /** Card payment through the configured online gateway. */
    CARD("Card", PaymentChannel.ONLINE),

    /** PayPal checkout through the configured online gateway. */
    PAYPAL("PayPal", PaymentChannel.ONLINE),

    /** Buyer transfers to the platform's bank account and quotes the payment reference. */
    BANK_TRANSFER("Bank transfer", PaymentChannel.OFFLINE_TRANSFER),

    /** Buyer pays the vendor in person and collects the goods at the store. */
    CASH_IN_STORE("Pay in store", PaymentChannel.IN_PERSON),

    /** Buyer pays the pickup-point operator when collecting the parcel. */
    PAY_AT_PICKUP("Pay at pickup point", PaymentChannel.IN_PERSON),

    /** Cash on delivery — the driver collects at the door. */
    PAY_ON_DELIVERY("Pay on delivery", PaymentChannel.IN_PERSON);

    private final String displayName;
    private final PaymentChannel channel;

    PaymentMethod(String displayName, PaymentChannel channel) {
        this.displayName = displayName;
        this.channel = channel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public PaymentChannel getChannel() {
        return channel;
    }

    /** True when a payment gateway has to create a checkout session for this method. */
    public boolean requiresGateway() {
        return channel == PaymentChannel.ONLINE;
    }

    /** True when the money is handed to a person (driver, operator, store) rather than a system. */
    public boolean isCollectedInPerson() {
        return channel == PaymentChannel.IN_PERSON;
    }

    /**
     * True when the order may be fulfilled before the money arrives — every
     * in-person method, since the payment happens at handover.
     */
    public boolean isPayLater() {
        return channel == PaymentChannel.IN_PERSON;
    }

    /** The delivery arrangement an in-person method is tied to; null for the rest. */
    public DeliveryMode requiredDeliveryMode() {
        return switch (this) {
            case CASH_IN_STORE  -> DeliveryMode.VENDOR_PICKUP;
            case PAY_AT_PICKUP  -> DeliveryMode.PICKUP_POINT;
            case PAY_ON_DELIVERY -> DeliveryMode.HOME_DELIVERY;
            default -> null;
        };
    }
}
