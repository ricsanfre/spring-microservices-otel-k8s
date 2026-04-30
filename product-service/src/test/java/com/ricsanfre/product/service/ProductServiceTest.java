package com.ricsanfre.product.service;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.product.api.model.CreateProductRequest;
import com.ricsanfre.product.api.model.ProductPage;
import com.ricsanfre.product.api.model.ProductResponse;
import com.ricsanfre.product.api.model.UpdateProductRequest;
import com.ricsanfre.product.domain.Product;
import com.ricsanfre.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Product buildProduct(String id, String category) {
        return Product.builder()
                .id(id)
                .name("Test Product")
                .description("A test product")
                .price(new BigDecimal("9.99"))
                .category(category)
                .imageUrl("https://example.com/img.png")
                .stockQty(10)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void list_withoutCategory_returnsAllProducts() {
        var pageable = PageRequest.of(0, 20);
        var products = List.of(buildProduct("id1", "electronics"), buildProduct("id2", "books"));
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(products, pageable, 2));

        ProductPage result = productService.list(null, pageable);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2L);
        verify(productRepository).findAll(pageable);
        verify(productRepository, never()).findByCategoryIgnoreCase(any(), any());
    }

    @Test
    void list_withBlankCategory_returnsAllProducts() {
        var pageable = PageRequest.of(0, 20);
        when(productRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        productService.list("  ", pageable);

        verify(productRepository).findAll(pageable);
        verify(productRepository, never()).findByCategoryIgnoreCase(any(), any());
    }

    @Test
    void list_withCategory_filtersByCategory() {
        var pageable = PageRequest.of(0, 20);
        var products = List.of(buildProduct("id1", "electronics"));
        when(productRepository.findByCategoryIgnoreCase("electronics", pageable))
                .thenReturn(new PageImpl<>(products, pageable, 1));

        ProductPage result = productService.list("electronics", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getCategory()).isEqualTo("electronics");
        verify(productRepository).findByCategoryIgnoreCase("electronics", pageable);
        verify(productRepository, never()).findAll((Pageable) any());
    }

    // ── getById ──────────────────────────────────────────────────────────────

    @Test
    void getById_existingProduct_returnsResponse() {
        var product = buildProduct("abc123", "electronics");
        when(productRepository.findById("abc123")).thenReturn(Optional.of(product));

        ProductResponse result = productService.getById("abc123");

        assertThat(result.getId()).isEqualTo("abc123");
        assertThat(result.getName()).isEqualTo("Test Product");
        assertThat(result.getPrice()).isEqualTo(9.99);
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    void getById_nonExistentProduct_throwsResourceNotFoundException() {
        when(productRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById("missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing");
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void create_buildsProductAndPersists() {
        var request = CreateProductRequest.builder()
                .name("New Product")
                .description("Description")
                .price(19.99)
                .category("books")
                .stockQty(5)
                .build();
        var saved = buildProduct("new-id", "books");
        saved.setName("New Product");
        when(productRepository.save(any(Product.class))).thenReturn(saved);

        ProductResponse result = productService.create(request);

        assertThat(result.getId()).isEqualTo("new-id");
        assertThat(result.getName()).isEqualTo("New Product");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void create_withNullStockQty_defaultsToZero() {
        var request = CreateProductRequest.builder().name("Item").price(5.0).build();
        var saved = buildProduct("id1", null);
        saved.setStockQty(0);
        when(productRepository.save(any(Product.class))).thenReturn(saved);

        productService.create(request);

        verify(productRepository).save(argThat(p -> p.getStockQty() == 0));
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    void update_existingProduct_updatesOnlyNonNullFields() {
        var product = buildProduct("abc123", "electronics");
        product.setDescription("Original Desc");
        when(productRepository.findById("abc123")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        var request = UpdateProductRequest.builder().name("Updated Name").build();
        ProductResponse result = productService.update("abc123", request);

        assertThat(product.getName()).isEqualTo("Updated Name");
        assertThat(product.getDescription()).isEqualTo("Original Desc");  // unchanged
        verify(productRepository).save(product);
    }

    @Test
    void update_nonExistentProduct_throwsResourceNotFoundException() {
        when(productRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.update("missing", new UpdateProductRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_existingProduct_deletesIt() {
        when(productRepository.existsById("abc123")).thenReturn(true);

        productService.delete("abc123");

        verify(productRepository).deleteById("abc123");
    }

    @Test
    void delete_nonExistentProduct_throwsResourceNotFoundException() {
        when(productRepository.existsById("missing")).thenReturn(false);

        assertThatThrownBy(() -> productService.delete("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(productRepository, never()).deleteById(any());
    }
}
