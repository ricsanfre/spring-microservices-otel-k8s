package com.ricsanfre.product.service;

import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.product.api.model.CreateProductRequest;
import com.ricsanfre.product.api.model.ProductPage;
import com.ricsanfre.product.api.model.ProductResponse;
import com.ricsanfre.product.api.model.StockReserveItem;
import com.ricsanfre.product.api.model.StockReserveRequest;
import com.ricsanfre.product.api.model.UpdateProductRequest;
import com.ricsanfre.product.domain.Product;
import com.ricsanfre.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;

    public ProductPage list(String category, Pageable pageable) {
        Page<Product> page = (category != null && !category.isBlank())
                ? productRepository.findByCategoryIgnoreCase(category, pageable)
                : productRepository.findAll(pageable);

        return ProductPage.builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }

    public ProductResponse getById(String id) {
        return productRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    public ProductResponse create(CreateProductRequest request) {
        log.info("Creating product name={}", request.getName());
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice() != null ? BigDecimal.valueOf(request.getPrice()) : null)
                .category(request.getCategory())
                .imageUrl(request.getImageUrl())
                .stockQty(request.getStockQty() != null ? request.getStockQty() : 0)
                .build();
        return toResponse(productRepository.save(product));
    }

    public ProductResponse update(String id, UpdateProductRequest request) {
        log.info("Updating product id={}", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (request.getName() != null)        product.setName(request.getName());
        if (request.getDescription() != null) product.setDescription(request.getDescription());
        if (request.getPrice() != null)       product.setPrice(BigDecimal.valueOf(request.getPrice()));
        if (request.getCategory() != null)    product.setCategory(request.getCategory());
        if (request.getImageUrl() != null)    product.setImageUrl(request.getImageUrl());
        if (request.getStockQty() != null)    product.setStockQty(request.getStockQty());

        return toResponse(productRepository.save(product));
    }

    public void delete(String id) {
        log.info("Deleting product id={}", id);
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product", id);
        }
        productRepository.deleteById(id);
    }

    /**
     * Atomically reserves stock for all items in a confirmed order.
     *
     * <p>Validates that every product has sufficient stock before persisting any change.
     * If any product has insufficient stock, throws {@link BusinessRuleException} with HTTP 409.
     */
    @Transactional
    public void reserveStock(StockReserveRequest request) {
        List<StockReserveItem> items = request.getItems();
        log.info("Reserving stock for {} items", items.size());

        // Load all products first; fail fast if any is not found
        List<Product> products = items.stream()
                .map(item -> productRepository.findById(item.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product", item.getProductId())))
                .toList();

        // Validate sufficient stock for every item before mutating anything
        for (int i = 0; i < items.size(); i++) {
            StockReserveItem item = items.get(i);
            Product product = products.get(i);
            if (product.getStockQty() < item.getQuantity()) {
                throw new BusinessRuleException(
                        "Insufficient stock for product %s: available=%d requested=%d"
                                .formatted(item.getProductId(), product.getStockQty(), item.getQuantity()));
            }
        }

        // Apply reservation
        for (int i = 0; i < items.size(); i++) {
            Product product = products.get(i);
            product.setStockQty(product.getStockQty() - items.get(i).getQuantity());
            productRepository.save(product);
            log.info("Reserved {} units for product={}, remaining={}",
                    items.get(i).getQuantity(), product.getId(), product.getStockQty());
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private ProductResponse toResponse(Product p) {
        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice() != null ? p.getPrice().doubleValue() : null)
                .category(p.getCategory())
                .imageUrl(p.getImageUrl())
                .stockQty(p.getStockQty())
                .createdAt(p.getCreatedAt() != null
                        ? OffsetDateTime.ofInstant(p.getCreatedAt(), ZoneOffset.UTC) : null)
                .updatedAt(p.getUpdatedAt() != null
                        ? OffsetDateTime.ofInstant(p.getUpdatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
