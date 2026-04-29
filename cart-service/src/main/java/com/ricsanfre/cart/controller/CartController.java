package com.ricsanfre.cart.controller;

import com.ricsanfre.cart.api.CartApi;
import com.ricsanfre.cart.api.model.CartItemRequest;
import com.ricsanfre.cart.api.model.CartResponse;
import com.ricsanfre.cart.service.CartService;
import com.ricsanfre.cart.service.UserIdResolverService;
import com.ricsanfre.common.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CartController implements CartApi {

    private final CartService cartService;
    private final UserIdResolverService userIdResolverService;

    @Override
    @PreAuthorize("hasAuthority('SCOPE_cart:read')")
    public ResponseEntity<CartResponse> getCart() {
        UUID userId = resolveUserId();
        return ResponseEntity.ok(cartService.getCart(userId));
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<CartResponse> upsertCartItem(String productId, CartItemRequest cartItemRequest) {
        UUID userId = resolveUserId();
        return ResponseEntity.ok(cartService.upsertItem(userId, productId, cartItemRequest));
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<CartResponse> removeCartItem(String productId) {
        UUID userId = resolveUserId();
        return ResponseEntity.ok(cartService.removeItem(userId, productId));
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<Void> clearCart() {
        UUID userId = resolveUserId();
        cartService.clearCart(userId);
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts the JWT {@code sub} and resolves it to the internal {@code users.id} UUID
     * via {@link UserIdResolverService} (per-service lazy resolution, ADR-004).
     */
    private UUID resolveUserId() {
        String idpSubject = JwtUtils.getSubject(SecurityContextHolder.getContext().getAuthentication());
        return userIdResolverService.resolveInternalId(idpSubject);
    }
}

