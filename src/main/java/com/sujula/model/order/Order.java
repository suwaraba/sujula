package com.sujula.model.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sujula.model.constant.OrderStatus;
import com.sujula.model.delivery.Delivery;
import com.sujula.model.products.Coupon;
import com.sujula.model.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "orders",
       indexes = {
           @Index(name = "idx_order_number",      columnList = "orderNumber",       unique = true),
           @Index(name = "idx_order_customer",    columnList = "customer_id"),
           @Index(name = "idx_order_guest_email", columnList = "guestEmail"),
           @Index(name = "idx_order_status",      columnList = "status")
       })
@NoArgsConstructor
@AllArgsConstructor
@Builder
// Prevent Jackson from trying to serialise uninitialised Hibernate proxies.
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String orderNumber;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")          // nullable=true for guest checkout
    @JsonIgnore
    private User customer;

    @Column(length = 200)
    private String guestName;

    @Column(length = 200)
    private String guestEmail;

    @Column(length = 30)
    private String guestPhone;

 
    @Column(length = 36)
    private String guestSessionId;

  

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();


    // ── Status ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    // ── Denormalised amounts for fast reads ───────────────────────────────────

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal shippingCost = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(length = 3)
    @Builder.Default
    private String currency = "GMD";   // GMD default for Gambia-first launch

    // ── Applied coupon ────────────────────────────────────────────────────────

    @JsonIgnore   // full Coupon entity not needed in API responses; couponCode snapshot is sufficient
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coupon_id")
    private Coupon coupon;

    @Column(length = 50)
    private String couponCode;          // snapshot — survives coupon deletion

    // ── Shipping address snapshot (denormalised at order time) ────────────────

    private String shippingFullName;
    private String shippingPhone;
    private String shippingStreet;
    private String shippingApartment;
    private String shippingCity;
    private String shippingState;
    private String shippingPostalCode;
    private String shippingCountry;

    // ── Billing address snapshot ──────────────────────────────────────────────

    private String billingFullName;
    private String billingStreet;
    private String billingCity;
    private String billingState;
    private String billingPostalCode;
    private String billingCountry;

    // ── Relations ─────────────────────────────────────────────────────────────

    // EAGER: payment status/method are needed in every order view.
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private Payment payment;

    @JsonIgnore   // avoid circular serialisation and lazy-init issues
    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Delivery delivery;

    // ── Notes ─────────────────────────────────────────────────────────────────

    private String notes;
    private String internalNotes;       // visible to admin/vendor only

    // ── Order Scheduling ──────────────────────────────────────────────────────

    private LocalDate scheduledDate;
    @Column(length = 20)
    private String scheduledTimeSlot;
    @Column(columnDefinition = "TEXT")
    private String deliveryInstructions;
    @Column(nullable = false)
    @Builder.Default
    private boolean contactlessDelivery = false;

    // ── Loyalty & Gift Card ───────────────────────────────────────────────────

    @Column(nullable = false)
    @Builder.Default
    private Long loyaltyPointsEarned = 0L;

    @Column(nullable = false)
    @Builder.Default
    private Long loyaltyPointsRedeemed = 0L;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal loyaltyDiscount = BigDecimal.ZERO;

    @Column(length = 25)
    private String giftCardCode;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal giftCardDiscount = BigDecimal.ZERO;

    // ── Timestamps ────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    
    
    // ── Helpers ───────────────────────────────────────────────────────────────

    public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getOrderNumber() {
		return orderNumber;
	}

	public void setOrderNumber(String orderNumber) {
		this.orderNumber = orderNumber;
	}

	public User getCustomer() {
		return customer;
	}

	public void setCustomer(User customer) {
		this.customer = customer;
	}

	public String getGuestName() {
		return guestName;
	}

	public void setGuestName(String guestName) {
		this.guestName = guestName;
	}

	public String getGuestEmail() {
		return guestEmail;
	}

	public void setGuestEmail(String guestEmail) {
		this.guestEmail = guestEmail;
	}

	public String getGuestPhone() {
		return guestPhone;
	}

	public void setGuestPhone(String guestPhone) {
		this.guestPhone = guestPhone;
	}

	public String getGuestSessionId() {
		return guestSessionId;
	}

	public void setGuestSessionId(String guestSessionId) {
		this.guestSessionId = guestSessionId;
	}

	public List<OrderItem> getItems() {
		return items;
	}

	public void setItems(List<OrderItem> items) {
		this.items = items;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public void setStatus(OrderStatus status) {
		this.status = status;
	}

	public BigDecimal getSubtotal() {
		return subtotal;
	}

	public void setSubtotal(BigDecimal subtotal) {
		this.subtotal = subtotal;
	}

	public BigDecimal getShippingCost() {
		return shippingCost;
	}

	public void setShippingCost(BigDecimal shippingCost) {
		this.shippingCost = shippingCost;
	}

	public BigDecimal getTaxAmount() {
		return taxAmount;
	}

	public void setTaxAmount(BigDecimal taxAmount) {
		this.taxAmount = taxAmount;
	}

	public BigDecimal getDiscount() {
		return discount;
	}

	public void setDiscount(BigDecimal discount) {
		this.discount = discount;
	}

	public BigDecimal getTotal() {
		return total;
	}

	public void setTotal(BigDecimal total) {
		this.total = total;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public Coupon getCoupon() {
		return coupon;
	}

	public void setCoupon(Coupon coupon) {
		this.coupon = coupon;
	}

	public String getCouponCode() {
		return couponCode;
	}

	public void setCouponCode(String couponCode) {
		this.couponCode = couponCode;
	}

	public String getShippingFullName() {
		return shippingFullName;
	}

	public void setShippingFullName(String shippingFullName) {
		this.shippingFullName = shippingFullName;
	}

	public String getShippingPhone() {
		return shippingPhone;
	}

	public void setShippingPhone(String shippingPhone) {
		this.shippingPhone = shippingPhone;
	}

	public String getShippingStreet() {
		return shippingStreet;
	}

	public void setShippingStreet(String shippingStreet) {
		this.shippingStreet = shippingStreet;
	}

	public String getShippingApartment() {
		return shippingApartment;
	}

	public void setShippingApartment(String shippingApartment) {
		this.shippingApartment = shippingApartment;
	}

	public String getShippingCity() {
		return shippingCity;
	}

	public void setShippingCity(String shippingCity) {
		this.shippingCity = shippingCity;
	}

	public String getShippingState() {
		return shippingState;
	}

	public void setShippingState(String shippingState) {
		this.shippingState = shippingState;
	}

	public String getShippingPostalCode() {
		return shippingPostalCode;
	}

	public void setShippingPostalCode(String shippingPostalCode) {
		this.shippingPostalCode = shippingPostalCode;
	}

	public String getShippingCountry() {
		return shippingCountry;
	}

	public void setShippingCountry(String shippingCountry) {
		this.shippingCountry = shippingCountry;
	}

	public String getBillingFullName() {
		return billingFullName;
	}

	public void setBillingFullName(String billingFullName) {
		this.billingFullName = billingFullName;
	}

	public String getBillingStreet() {
		return billingStreet;
	}

	public void setBillingStreet(String billingStreet) {
		this.billingStreet = billingStreet;
	}

	public String getBillingCity() {
		return billingCity;
	}

	public void setBillingCity(String billingCity) {
		this.billingCity = billingCity;
	}

	public String getBillingState() {
		return billingState;
	}

	public void setBillingState(String billingState) {
		this.billingState = billingState;
	}

	public String getBillingPostalCode() {
		return billingPostalCode;
	}

	public void setBillingPostalCode(String billingPostalCode) {
		this.billingPostalCode = billingPostalCode;
	}

	public String getBillingCountry() {
		return billingCountry;
	}

	public void setBillingCountry(String billingCountry) {
		this.billingCountry = billingCountry;
	}

	public Payment getPayment() {
		return payment;
	}

	public void setPayment(Payment payment) {
		this.payment = payment;
	}

	public Delivery getDelivery() {
		return delivery;
	}

	public void setDelivery(Delivery delivery) {
		this.delivery = delivery;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public String getInternalNotes() {
		return internalNotes;
	}

	public void setInternalNotes(String internalNotes) {
		this.internalNotes = internalNotes;
	}

	public LocalDate getScheduledDate() {
		return scheduledDate;
	}

	public void setScheduledDate(LocalDate scheduledDate) {
		this.scheduledDate = scheduledDate;
	}

	public String getScheduledTimeSlot() {
		return scheduledTimeSlot;
	}

	public void setScheduledTimeSlot(String scheduledTimeSlot) {
		this.scheduledTimeSlot = scheduledTimeSlot;
	}

	public String getDeliveryInstructions() {
		return deliveryInstructions;
	}

	public void setDeliveryInstructions(String deliveryInstructions) {
		this.deliveryInstructions = deliveryInstructions;
	}

	public boolean isContactlessDelivery() {
		return contactlessDelivery;
	}

	public void setContactlessDelivery(boolean contactlessDelivery) {
		this.contactlessDelivery = contactlessDelivery;
	}

	public Long getLoyaltyPointsEarned() {
		return loyaltyPointsEarned;
	}

	public void setLoyaltyPointsEarned(Long loyaltyPointsEarned) {
		this.loyaltyPointsEarned = loyaltyPointsEarned;
	}

	public Long getLoyaltyPointsRedeemed() {
		return loyaltyPointsRedeemed;
	}

	public void setLoyaltyPointsRedeemed(Long loyaltyPointsRedeemed) {
		this.loyaltyPointsRedeemed = loyaltyPointsRedeemed;
	}

	public BigDecimal getLoyaltyDiscount() {
		return loyaltyDiscount;
	}

	public void setLoyaltyDiscount(BigDecimal loyaltyDiscount) {
		this.loyaltyDiscount = loyaltyDiscount;
	}

	public String getGiftCardCode() {
		return giftCardCode;
	}

	public void setGiftCardCode(String giftCardCode) {
		this.giftCardCode = giftCardCode;
	}

	public BigDecimal getGiftCardDiscount() {
		return giftCardDiscount;
	}

	public void setGiftCardDiscount(BigDecimal giftCardDiscount) {
		this.giftCardDiscount = giftCardDiscount;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	/** True when this order was placed without a user account. */
    public boolean isGuestOrder() {
        return customer == null;
    }

    /** Display name for emails/notifications regardless of order type. */
    public String getDisplayName() {
        return isGuestOrder() ? guestName : customer.getFullName();
    }

    /** Contact email for emails/notifications regardless of order type. */
    public String getContactEmail() {
        return isGuestOrder() ? guestEmail : customer.getEmail();
    }


    /** Payment method used for this order, e.g. "STRIPE", "COD". Null if no payment yet. */
    public String getPaymentMethod() {
        return payment != null ? payment.getPaymentMethod() : null;
    }

    /** Payment status for this order, e.g. "PENDING", "PAID". Null if no payment yet. */
    public String getPaymentStatus() {
        return payment != null ? payment.getStatus().name() : null;
    }
}
