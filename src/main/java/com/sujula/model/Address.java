package com.sujula.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sujula.model.user.User;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "addresses")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String label;               // "Home", "Work", "Other"

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String street;

    private String apartmentSuite;

    @Column(nullable = false)
    private String city;

    private String state;              

    private String postalCode;

    @Column(nullable = false, length = 2)
    private String countryCode;

    private Double latitude;
    private Double longitude;

    @Column(nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
