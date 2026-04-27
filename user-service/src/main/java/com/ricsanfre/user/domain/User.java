package com.ricsanfre.user.domain;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "users",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_users_idp_subject", columnNames = "idp_subject"),
        @UniqueConstraint(name = "uq_users_email",       columnNames = "email")
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idp_subject", nullable = false)
    private String idpSubject;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String username;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    // ── Shipping address ────────────────────────────────────────────────────
    @Column(name = "address_street")
    private String addressStreet;

    @Column(name = "address_city")
    private String addressCity;

    @Column(name = "address_state")
    private String addressState;

    @Column(name = "address_postal_code")
    private String addressPostalCode;

    /** ISO 3166-1 alpha-2 country code, e.g. "US", "ES". */
    @Column(name = "address_country", length = 2)
    private String addressCountry;

    // ── Billing account ─────────────────────────────────────────────────────
    /** Cardholder display name — never store the full PAN or CVV. */
    @Column(name = "billing_card_holder")
    private String billingCardHolder;

    /** Last four digits of the payment card, for display only. */
    @Column(name = "billing_card_last4", length = 4)
    private String billingCardLast4;

    /** Card expiry in MM/YY format, for display only. */
    @Column(name = "billing_card_expiry", length = 5)
    private String billingCardExpiry;

    /** When true, billing address is the same as the shipping address above. */
    @Column(name = "billing_same_as_shipping", nullable = false)
    @Builder.Default
    private boolean billingSameAsShipping = true;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
