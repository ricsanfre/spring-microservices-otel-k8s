package com.ricsanfre.order.controller;

import com.ricsanfre.order.api.OrdersApi;
import com.ricsanfre.order.api.model.CreateOrderRequest;
import com.ricsanfre.order.api.model.OrderResponse;
import com.ricsanfre.order.api.model.UpdateOrderStatusRequest;
import com.ricsanfre.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OrderController implements OrdersApi {

    private final OrderService orderService;

    @Override
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    public ResponseEntity<OrderResponse> createOrder(CreateOrderRequest createOrderRequest) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(orderService.createOrder(createOrderRequest, auth));
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    public ResponseEntity<OrderResponse> getOrderById(UUID id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(orderService.findById(id, auth));
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_orders:read')")
    public ResponseEntity<List<OrderResponse>> getOrdersByUserId(UUID userId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(orderService.findByUserId(userId, auth));
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_orders:write')")
    public ResponseEntity<OrderResponse> updateOrderStatus(UUID id, UpdateOrderStatusRequest updateOrderStatusRequest) {
        return ResponseEntity.ok(orderService.updateStatus(id, updateOrderStatusRequest));
    }
}
