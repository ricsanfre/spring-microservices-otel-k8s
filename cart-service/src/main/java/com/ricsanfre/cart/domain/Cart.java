package com.ricsanfre.cart.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Cart stored in Valkey as JSON under key {@code cart:{userId}}.
 * TTL is refreshed on every write (7 days).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cart implements Serializable {

    private String userId;

    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    private Instant expiresAt;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public int getTotalItems() {
        return items.size();
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public double getGrandTotal() {
        return items.stream().mapToDouble(CartItem::getLineTotal).sum();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItem implements Serializable {

        private String productId;
        private String productName;
        private double price;
        private int quantity;

        @com.fasterxml.jackson.annotation.JsonIgnore
        public double getLineTotal() {
            return price * quantity;
        }
    }
}
