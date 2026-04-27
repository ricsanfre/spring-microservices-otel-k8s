package com.ricsanfre.product.controller;

import com.ricsanfre.product.api.ProductsApi;
import com.ricsanfre.product.api.model.CreateProductRequest;
import com.ricsanfre.product.api.model.ProductPage;
import com.ricsanfre.product.api.model.ProductResponse;
import com.ricsanfre.product.api.model.UpdateProductRequest;
import com.ricsanfre.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProductController implements ProductsApi {

    private final ProductService productService;

    @Override
    public ResponseEntity<ProductPage> listProducts(Integer page, Integer size, String category) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        return ResponseEntity.ok(productService.list(category, PageRequest.of(p, s)));
    }

    @Override
    public ResponseEntity<ProductResponse> getProductById(String id) {
        return ResponseEntity.ok(productService.getById(id));
    }

    @Override
    @PreAuthorize("hasRole('ROLE_ADMIN') or hasAuthority('SCOPE_products:write')")
    public ResponseEntity<ProductResponse> createProduct(CreateProductRequest createProductRequest) {
        ProductResponse created = productService.create(createProductRequest);
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.getId()))
                .body(created);
    }

    @Override
    @PreAuthorize("hasRole('ROLE_ADMIN') or hasAuthority('SCOPE_products:write')")
    public ResponseEntity<ProductResponse> updateProduct(String id, UpdateProductRequest updateProductRequest) {
        return ResponseEntity.ok(productService.update(id, updateProductRequest));
    }

    @Override
    @PreAuthorize("hasRole('ROLE_ADMIN') or hasAuthority('SCOPE_products:write')")
    public ResponseEntity<Void> deleteProduct(String id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
