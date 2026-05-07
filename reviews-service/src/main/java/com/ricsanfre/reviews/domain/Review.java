package com.ricsanfre.reviews.domain;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document(collection = "reviews")
@CompoundIndex(name = "unique_user_product_order",
        def = "{'userId': 1, 'productId': 1, 'orderId': 1}",
        unique = true)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Review {

    @Id
    private String id;  // MongoDB ObjectId as hex string

    @Indexed
    private String productId;

    private UUID orderId;

    @Indexed
    private UUID userId;

    private int rating;

    private String comment;

    @CreatedDate
    private Instant createdAt;
}
