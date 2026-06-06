package com.ricsanfre.reviews.service;

import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.reviews.api.model.CreateReviewRequest;
import com.ricsanfre.reviews.api.model.ReviewResponse;
import com.ricsanfre.reviews.client.OrderServiceClient;
import com.ricsanfre.reviews.client.ProductServiceClient;
import com.ricsanfre.reviews.domain.Review;
import com.ricsanfre.reviews.repository.ReviewRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private static final String STATUS_DELIVERED = "DELIVERED";

    private final ReviewRepository reviewRepository;
    private final UserIdResolverService userIdResolverService;
    private final ProductServiceClient productServiceClient;
    private final OrderServiceClient orderServiceClient;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public List<ReviewResponse> getByProduct(String productId) {
        log.debug("getByProduct productId={}", productId);
        return reviewRepository.findByProductId(productId).stream()
                .map(this::toResponse)
                .toList();
    }

    public ReviewResponse createReview(String idpSubject, CreateReviewRequest request) {
        log.debug("operation=review.create outcome=start productId={} orderId={}",
            request.getProductId(), request.getOrderId());
        // 1. Resolve JWT sub → internal userId (ADR-004 lazy resolution)
        UUID userId = userIdResolverService.resolveInternalId(idpSubject);

        io.micrometer.tracing.Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) currentSpan.tag("user.id", userId.toString());
        MDC.put("user.id", userId.toString());
        try {
        // 2. Verify product exists
        try {
            productServiceClient.getProduct(request.getProductId());
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Product", request.getProductId());
        }

        // 3. Fetch order and validate ownership + status + product membership
        UUID orderId = request.getOrderId();
        OrderServiceClient.OrderResponse order;
        try {
            order = orderServiceClient.getOrder(orderId);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Order", orderId.toString());
        }

        if (!order.userId().equals(userId)) {
            throw new BusinessRuleException("Order does not belong to the current user");
        }
        if (!STATUS_DELIVERED.equalsIgnoreCase(order.status())) {
            throw new BusinessRuleException(
                    "Order must be in DELIVERED status to leave a review, current status: " + order.status());
        }
        boolean hasProduct = order.items() != null && order.items().stream()
                .anyMatch(item -> request.getProductId().equals(item.productId()));
        if (!hasProduct) {
            throw new BusinessRuleException("Order does not contain the specified product");
        }

        // 4. Prevent duplicate reviews for the same (user, product, order) combination
        if (reviewRepository.existsByUserIdAndProductIdAndOrderId(userId, request.getProductId(), orderId)) {
            throw new BusinessRuleException("You have already reviewed this product for this order");
        }

        // 5. Persist review
        Review review = Review.builder()
                .productId(request.getProductId())
                .orderId(orderId)
                .userId(userId)
                .rating(request.getRating())
                .comment(request.getComment())
                .build();

        Review saved = reviewRepository.save(review);

        Counter.builder("reviews.submitted").register(meterRegistry).increment();
        DistributionSummary.builder("review.rating").register(meterRegistry).record(request.getRating());

            log.info("operation=review.create outcome=success reviewId={} productId={} orderId={} userId={}",
                    saved.getId(), saved.getProductId(), saved.getOrderId(), saved.getUserId());
            return toResponse(saved);
        } catch (RuntimeException e) {
            log.warn("operation=review.create outcome=failure productId={} orderId={} reason={}",
                    request.getProductId(), request.getOrderId(), e.toString());
            throw e;
        } finally {
            MDC.remove("user.id");
        }
    }

    public void deleteReview(String reviewId, String idpSubject) {
        log.debug("operation=review.delete outcome=start reviewId={}", reviewId);
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", reviewId));

        UUID userId = userIdResolverService.resolveInternalId(idpSubject);
        if (!review.getUserId().equals(userId)) {
            throw new BusinessRuleException("You are not the owner of this review");
        }

        reviewRepository.deleteById(reviewId);
        log.info("operation=review.delete outcome=success reviewId={}", reviewId);
    }

    private ReviewResponse toResponse(Review review) {
        OffsetDateTime createdAt = review.getCreatedAt() != null
                ? review.getCreatedAt().atOffset(ZoneOffset.UTC)
                : null;
        return ReviewResponse.builder()
                .id(review.getId())
                .productId(review.getProductId())
                .orderId(review.getOrderId())
                .userId(review.getUserId())
                .rating(review.getRating())
                .comment(review.getComment())
                .createdAt(createdAt)
                .build();
    }
}
