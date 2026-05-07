package com.ricsanfre.reviews.controller;

import com.ricsanfre.common.security.JwtUtils;
import com.ricsanfre.reviews.api.ReviewsApi;
import com.ricsanfre.reviews.api.model.CreateReviewRequest;
import com.ricsanfre.reviews.api.model.ReviewResponse;
import com.ricsanfre.reviews.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController implements ReviewsApi {

    private final ReviewService reviewService;

    @Override
    public ResponseEntity<List<ReviewResponse>> getReviewsByProduct(String productId) {
        return ResponseEntity.ok(reviewService.getByProduct(productId));
    }

    @Override
    public ResponseEntity<ReviewResponse> createReview(CreateReviewRequest createReviewRequest) {
        String sub = JwtUtils.getSubject(SecurityContextHolder.getContext().getAuthentication());
        ReviewResponse created = reviewService.createReview(sub, createReviewRequest);
        return ResponseEntity.created(URI.create("/api/v1/reviews/" + created.getId()))
                .body(created);
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_reviews:write')")
    public ResponseEntity<Void> deleteReview(String id) {
        String sub = JwtUtils.getSubject(SecurityContextHolder.getContext().getAuthentication());
        reviewService.deleteReview(id, sub);
        return ResponseEntity.noContent().build();
    }
}
