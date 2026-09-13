package com.sujula.model.shipment;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What lets the recipient change something about her own parcel.
 *
 * <p><strong>Not a {@link com.sujula.model.delivery.HandoverCode}, and the
 * distinction is the whole of this class.</strong> A handover code is proof that
 * a parcel changed hands: it is presented in person, burned on use, and a driver
 * who could read one could mark a parcel delivered without meeting anybody. This
 * is an access credential — it says the person typing is the person the parcel
 * is for, and it authorises instructions rather than custody. Reusing the
 * delivery code here would have put it on a web page, and a code on a web page
 * is a code somebody can be talked into reading out.
 *
 * <p>It is <em>not</em> burned on first use, deliberately. The recipient who
 * chooses a pickup point and then wants a different day would otherwise have to
 * go back to the person who paid for her and ask them to send another one, from
 * another continent, in another time zone. It lives fifteen minutes and dies on
 * the fifth wrong guess.
 *
 * <p>Where it is sent is never in the request. The destination is the contact on
 * the order, chosen by the server — an endpoint that accepted an address would
 * be an endpoint for having somebody else's code delivered to you.
 */
@Entity
@Table(name = "parcel_access_codes",
       indexes = {
           @Index(name = "idx_pac_shipment", columnList = "shipment_id"),
           @Index(name = "idx_pac_expires",  columnList = "expiresAt")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParcelAccessCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Two people acting on one parcel at once: the recipient and her brother. */
    @Version
    private Long version;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    /** Six digits, stored as text so a leading zero survives. */
    @Column(nullable = false, length = 6)
    private String code;

    /**
     * How many wrong guesses have been made against this code.
     *
     * <p>Six digits is a million tries for a script and one for somebody who
     * misheard a digit over a bad line. The count is what tells those apart, and
     * what kills a code being worked through.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer failedAttempts = 0;

    /** Set when a newer code replaces this one. Reissuing replaces, never edits. */
    private LocalDateTime invalidatedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /** When it was last accepted, for the page to say "you are signed in until". */
    private LocalDateTime lastUsedAt;

    /**
     * How many instructions have been given on this code.
     *
     * <p>Not a limit — a record. A code that authorised nine changes in fifteen
     * minutes is worth being able to see afterwards.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer instructionsGiven = 0;

    /**
     * Where it went, already masked.
     *
     * <p>Stored masked rather than in full because this row is read by support:
     * knowing the code went to {@code d***@gmail.com} answers "did he get it",
     * and the full address is on the order for anyone who genuinely needs it.
     */
    @Column(length = 120)
    private String sentTo;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /** Whether this code would be accepted right now. */
    public boolean isLive(LocalDateTime now) {
        return invalidatedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
