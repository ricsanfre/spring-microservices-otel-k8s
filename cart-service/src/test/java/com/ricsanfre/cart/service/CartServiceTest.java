package com.ricsanfre.cart.service;

import com.ricsanfre.cart.api.model.CartItemRequest;
import com.ricsanfre.cart.client.OrderServiceClient;
import com.ricsanfre.cart.domain.Cart;
import com.ricsanfre.cart.repository.CartRepository;
import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OrderServiceClient orderServiceClient;

    @InjectMocks
    private CartService cartService;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PRODUCT_A = "prod-a";
    private static final String PRODUCT_B = "prod-b";

    // ── helpers ──────────────────────────────────────────────────────────────

    private Cart cartWithItem(String productId, double price, int qty) {
        Cart.CartItem item = Cart.CartItem.builder()
                .productId(productId)
                .productName("Product " + productId)
                .price(price)
                .quantity(qty)
                .build();
        return Cart.builder()
                .userId(USER_ID.toString())
                .items(new ArrayList<>(List.of(item)))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private Cart savedCart(Cart cart) {
        // simulate save setting expiresAt
        cart.setExpiresAt(Instant.now().plusSeconds(3600));
        return cart;
    }

    // ── getCart ──────────────────────────────────────────────────────────────

    @Test
    void getCart_existingCart_returnsCartWithItems() {
        Cart stored = cartWithItem(PRODUCT_A, 9.99, 2);
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));

        var response = cartService.getCart(USER_ID);

        assertThat(response.getUserId()).isEqualTo(USER_ID);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getProductId()).isEqualTo(PRODUCT_A);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(2);
        assertThat(response.getGrandTotal()).isEqualTo(19.98);
        assertThat(response.getTotalItems()).isEqualTo(1);
    }

    @Test
    void getCart_noExistingCart_returnsEmptyCart() {
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.empty());

        var response = cartService.getCart(USER_ID);

        assertThat(response.getUserId()).isEqualTo(USER_ID);
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalItems()).isZero();
        assertThat(response.getGrandTotal()).isZero();
        // empty cart is not persisted
        verify(cartRepository, never()).save(any());
    }

    // ── upsertItem ───────────────────────────────────────────────────────────

    @Test
    void upsertItem_newProduct_addsItemToCart() {
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> savedCart(inv.getArgument(0)));

        CartItemRequest request = CartItemRequest.builder()
                .productName("Widget")
                .price(5.0)
                .quantity(3)
                .build();

        var response = cartService.upsertItem(USER_ID, PRODUCT_A, request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getProductId()).isEqualTo(PRODUCT_A);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(response.getItems().get(0).getPrice()).isEqualTo(5.0);
        assertThat(response.getGrandTotal()).isEqualTo(15.0);
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void upsertItem_existingProduct_updatesQuantityAndPrice() {
        Cart stored = cartWithItem(PRODUCT_A, 9.99, 1);
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> savedCart(inv.getArgument(0)));

        CartItemRequest request = CartItemRequest.builder()
                .quantity(5)
                .price(8.50)
                .build();

        var response = cartService.upsertItem(USER_ID, PRODUCT_A, request);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(response.getItems().get(0).getPrice()).isEqualTo(8.50);
    }

    @Test
    void upsertItem_quantityZero_removesItemFromCart() {
        Cart stored = cartWithItem(PRODUCT_A, 9.99, 2);
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> savedCart(inv.getArgument(0)));

        CartItemRequest request = CartItemRequest.builder()
                .quantity(0)
                .build();

        var response = cartService.upsertItem(USER_ID, PRODUCT_A, request);

        assertThat(response.getItems()).isEmpty();
    }

    @Test
    void upsertItem_multipleProducts_maintainsOtherItems() {
        Cart stored = cartWithItem(PRODUCT_A, 10.0, 1);
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> savedCart(inv.getArgument(0)));

        CartItemRequest request = CartItemRequest.builder()
                .productName("Widget B")
                .price(20.0)
                .quantity(2)
                .build();

        var response = cartService.upsertItem(USER_ID, PRODUCT_B, request);

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getGrandTotal()).isEqualTo(50.0);
    }

    @Test
    void upsertItem_nullQuantity_defaultsToOne() {
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> savedCart(inv.getArgument(0)));

        CartItemRequest request = CartItemRequest.builder()
                .productName("Widget")
                .price(3.0)
                .quantity(null)
                .build();

        var response = cartService.upsertItem(USER_ID, PRODUCT_A, request);

        assertThat(response.getItems().get(0).getQuantity()).isEqualTo(1);
    }

    // ── removeItem ───────────────────────────────────────────────────────────

    @Test
    void removeItem_existingProduct_removesAndReturnsCart() {
        Cart stored = cartWithItem(PRODUCT_A, 9.99, 2);
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> savedCart(inv.getArgument(0)));

        var response = cartService.removeItem(USER_ID, PRODUCT_A);

        assertThat(response.getItems()).isEmpty();
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void removeItem_cartNotFound_throwsResourceNotFoundException() {
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.removeItem(USER_ID, PRODUCT_A))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removeItem_productNotInCart_throwsResourceNotFoundException() {
        Cart stored = cartWithItem(PRODUCT_A, 9.99, 1);
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> cartService.removeItem(USER_ID, PRODUCT_B))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── clearCart ────────────────────────────────────────────────────────────

    @Test
    void clearCart_delegatesToRepository() {
        cartService.clearCart(USER_ID);

        verify(cartRepository).deleteByUserId(USER_ID.toString());
    }

    // ── toResponse mapping ───────────────────────────────────────────────────

    @Test
    void getCart_expiresAtPresent_mappedToOffsetDateTime() {
        Cart stored = cartWithItem(PRODUCT_A, 1.0, 1);
        stored.setExpiresAt(Instant.ofEpochSecond(1_700_000_000));
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));

        var response = cartService.getCart(USER_ID);

        assertThat(response.getExpiresAt()).isNotNull();
        assertThat(response.getExpiresAt().toInstant()).isEqualTo(Instant.ofEpochSecond(1_700_000_000));
    }

    @Test
    void getCart_expiresAtNull_mappedToNull() {
        Cart stored = Cart.builder()
                .userId(USER_ID.toString())
                .items(new ArrayList<>())
                .expiresAt(null)
                .build();
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));

        var response = cartService.getCart(USER_ID);

        assertThat(response.getExpiresAt()).isNull();
    }

    @Test
    void upsertItem_savedCart_capturedWithCorrectUserId() {
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> savedCart(inv.getArgument(0)));

        CartItemRequest request = CartItemRequest.builder()
                .productName("Widget")
                .price(1.0)
                .quantity(1)
                .build();

        cartService.upsertItem(USER_ID, PRODUCT_A, request);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID.toString());
    }

    // ── checkout ─────────────────────────────────────────────────────────────

    @Test
    void checkout_cartWithItems_callsOrderServiceAndReturnsResponse() {
        Cart stored = cartWithItem(PRODUCT_A, 9.99, 2);
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(stored));

        OrderServiceClient.OrderResponse fakeResponse = new OrderServiceClient.OrderResponse(
                java.util.UUID.randomUUID(), USER_ID, "PENDING", List.of(), 19.98, null, null);
        when(orderServiceClient.createOrder(any())).thenReturn(fakeResponse);

        OrderServiceClient.OrderResponse result = cartService.checkout(USER_ID);

        assertThat(result.totalAmount()).isEqualTo(19.98);
        assertThat(result.status()).isEqualTo("PENDING");
        verify(orderServiceClient).createOrder(argThat(req ->
                req.items().size() == 1 &&
                req.items().get(0).productId().equals(PRODUCT_A) &&
                req.items().get(0).quantity() == 2 &&
                req.items().get(0).unitPrice() == 9.99));
    }

    @Test
    void checkout_cartNotFound_throwsBusinessRuleException() {
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.checkout(USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("empty");

        verifyNoInteractions(orderServiceClient);
    }

    @Test
    void checkout_emptyCart_throwsBusinessRuleException() {
        Cart emptyCart = Cart.builder()
                .userId(USER_ID.toString())
                .items(new ArrayList<>())
                .build();
        when(cartRepository.findByUserId(USER_ID.toString())).thenReturn(Optional.of(emptyCart));

        assertThatThrownBy(() -> cartService.checkout(USER_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("empty");

        verifyNoInteractions(orderServiceClient);
    }
}
