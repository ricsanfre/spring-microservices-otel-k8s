package com.ricsanfre.product.repository;

import com.ricsanfre.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ProductRepository extends MongoRepository<Product, String> {

    Page<Product> findByCategoryIgnoreCase(String category, Pageable pageable);
}
