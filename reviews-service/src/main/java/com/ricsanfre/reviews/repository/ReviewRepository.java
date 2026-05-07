package com.ricsanfre.reviews.repository;

import com.ricsanfre.reviews.domain.Review;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewRepository extends MongoRepository<Review, String> {

    List<Review> findByProductId(String productId);

    boolean existsByUserIdAndProductIdAndOrderId(UUID userId, String productId, UUID orderId);
}
