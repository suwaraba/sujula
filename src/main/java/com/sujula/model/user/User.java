package com.sujula.model.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.Address;
import com.sujula.model.constant.UserRole;
import com.sujula.model.order.Order;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
@Builder
public class User {

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean emailVerified = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean blocked = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean fraud = false;

    private String profileImageUrl;

    // Locale preferences — auto-detected from IP on first request, user-overridable
    @Column(length = 3)
    @Builder.Default
    private String preferredCurrency = "GMD";   // ISO 4217

    @Column(length = 10)
    @Builder.Default
    private String preferredLanguage = "en";     // BCP 47 (en, fr, ar, …)

    // Country detected from IP on first visit (ISO 3166-1 alpha-2)
    @Column(length = 2)
    private String detectedCountryCode;

    // Email verification
    private String emailVerificationToken;
    private LocalDateTime emailVerificationTokenExpiry;

    // Password reset
    private String passwordResetToken;
    private LocalDateTime passwordResetTokenExpiry;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Address> addresses = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Order> orders = new ArrayList<>();

    // TOTP 2FA (for admin accounts)
    @JsonIgnore
    @Column(length = 64)
    private String totpSecret;

    @Column(nullable = false)
    @Builder.Default
    private boolean totpEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean totpVerified = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    
public User(Long id, String email, String password, String firstName, String lastName, String phone, UserRole role,
			boolean enabled, boolean emailVerified, boolean blocked, boolean fraud, String profileImageUrl, String preferredCurrency,
			String preferredLanguage, String detectedCountryCode, String emailVerificationToken,
			LocalDateTime emailVerificationTokenExpiry, String passwordResetToken,
			LocalDateTime passwordResetTokenExpiry, List<Address> addresses, List<Order> orders,
			String totpSecret, boolean totpEnabled, boolean totpVerified,
			LocalDateTime createdAt, LocalDateTime updatedAt) {
		this.id = id;
		this.email = email;
		this.password = password;
		this.firstName = firstName;
		this.lastName = lastName;
		this.phone = phone;
		this.role = role;
		this.enabled = enabled;
		this.emailVerified = emailVerified;
		this.blocked = blocked;
		this.fraud = fraud;
		this.profileImageUrl = profileImageUrl;
		this.preferredCurrency = preferredCurrency;
		this.preferredLanguage = preferredLanguage;
		this.detectedCountryCode = detectedCountryCode;
		this.emailVerificationToken = emailVerificationToken;
		this.emailVerificationTokenExpiry = emailVerificationTokenExpiry;
		this.passwordResetToken = passwordResetToken;
		this.passwordResetTokenExpiry = passwordResetTokenExpiry;
		this.addresses = addresses;
		this.orders = orders;
		this.totpSecret = totpSecret;
		this.totpEnabled = totpEnabled;
		this.totpVerified = totpVerified;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	

    
    public User() {
}




	public Long getId() {
		return id;
	}



	public void setId(Long id) {
		this.id = id;
	}



	public String getEmail() {
		return email;
	}



	public void setEmail(String email) {
		this.email = email;
	}



	@JsonIgnore
	public String getPassword() {
		return password;
	}



	public void setPassword(String password) {
		this.password = password;
	}



	public String getFirstName() {
		return firstName;
	}



	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}



	public String getLastName() {
		return lastName;
	}



	public void setLastName(String lastName) {
		this.lastName = lastName;
	}



	public String getPhone() {
		return phone;
	}



	public void setPhone(String phone) {
		this.phone = phone;
	}



	public UserRole getRole() {
		return role;
	}



	public void setRole(UserRole role) {
		this.role = role;
	}



	public boolean isEnabled() {
		return enabled;
	}



	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}



	public boolean isEmailVerified() {
		return emailVerified;
	}



	public void setEmailVerified(boolean emailVerified) {
		this.emailVerified = emailVerified;
	}



	public boolean isBlocked() {
		return blocked;
	}



	public void setBlocked(boolean blocked) {
		this.blocked = blocked;
	}



	public boolean isFraud() {
		return fraud;
	}



	public void setFraud(boolean fraud) {
		this.fraud = fraud;
	}



	public String getProfileImageUrl() {
		return profileImageUrl;
	}



	public void setProfileImageUrl(String profileImageUrl) {
		this.profileImageUrl = profileImageUrl;
	}



	public String getPreferredCurrency() {
		return preferredCurrency;
	}



	public void setPreferredCurrency(String preferredCurrency) {
		this.preferredCurrency = preferredCurrency;
	}



	public String getPreferredLanguage() {
		return preferredLanguage;
	}



	public void setPreferredLanguage(String preferredLanguage) {
		this.preferredLanguage = preferredLanguage;
	}



	public String getDetectedCountryCode() {
		return detectedCountryCode;
	}



	public void setDetectedCountryCode(String detectedCountryCode) {
		this.detectedCountryCode = detectedCountryCode;
	}



	@JsonIgnore
	public String getEmailVerificationToken() {
		return emailVerificationToken;
	}



	public void setEmailVerificationToken(String emailVerificationToken) {
		this.emailVerificationToken = emailVerificationToken;
	}



	@JsonIgnore
	public LocalDateTime getEmailVerificationTokenExpiry() {
		return emailVerificationTokenExpiry;
	}



	public void setEmailVerificationTokenExpiry(LocalDateTime emailVerificationTokenExpiry) {
		this.emailVerificationTokenExpiry = emailVerificationTokenExpiry;
	}



	@JsonIgnore
	public String getPasswordResetToken() {
		return passwordResetToken;
	}



	public void setPasswordResetToken(String passwordResetToken) {
		this.passwordResetToken = passwordResetToken;
	}



	@JsonIgnore
	public LocalDateTime getPasswordResetTokenExpiry() {
		return passwordResetTokenExpiry;
	}



	public void setPasswordResetTokenExpiry(LocalDateTime passwordResetTokenExpiry) {
		this.passwordResetTokenExpiry = passwordResetTokenExpiry;
	}



	@JsonIgnore
	public List<Address> getAddresses() {
		return addresses;
	}



	public void setAddresses(List<Address> addresses) {
		this.addresses = addresses;
	}



	@JsonIgnore
	public List<Order> getOrders() {
		return orders;
	}



	public void setOrders(List<Order> orders) {
		this.orders = orders;
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


    @JsonIgnore
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public boolean isTotpVerified() { return totpVerified; }
    public void setTotpVerified(boolean totpVerified) { this.totpVerified = totpVerified; }

	public String getFullName() {
        return firstName + " " + lastName;
    }
}
