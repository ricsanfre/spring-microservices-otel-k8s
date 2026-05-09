package com.ricsanfre.reviews.service;

import com.ricsanfre.common.exception.BusinessRuleException;
import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.reviews.api.model.CreateReviewRequest;
import com.ricsanfre.reviews.client.OrderServiceClient;
import com.ricsanfre.reviews.client.ProductServiceClient;
import com.ricsanfre.reviews.domain.Review;
import com.ricsanfre.reviews.repository.ReviewRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private UserIdResolverService userIdResolverService;

    @Mock
    private ProductServiceClient productServiceClient;

    @Mock
    private OrderServiceClient orderServiceClient;

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock
    private Tracer tracer;

    @InjectMocks
    private ReviewService reviewService;

    private static final String IDP_SUBJECT = "keycloak-sub-123";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String PRODUCT_ID = "mongo-product-id-abc";

    // ── getByProduct ──────────────────────────────────────────────────────────

    @Test
    void getByProduct_returnsReviewsForProduct() {
        Review review = Review.builder()
                .id("review-1")
                .productId(PRODUCT_ID)
                .orderId(ORDER_ID)
                .userId(USER_ID)
                .rating(5)
                .comment("Great product!")
                .build();
        when(reviewRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(review));

        var result = reviewService.getByProduct(PRODUCT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(result.get(0).getRating()).isEqualTo(5);
    }

    // ── createReview ──────────────────────────────────────────────────────────

    @Test
    void createReview_validRequest_savesAndReturnsReview() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID)
                .orderId(ORDER_ID)
                .rating(4)
                .comment("Good quality")
                .build();

        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);
        when(productServiceClient.getProduct(PRODUCT_ID))
                .thenReturn(new ProductServiceClient.ProductResponse(PRODUCT_ID, "Test Product"));
        when(orderServiceClient.getOrder(ORDER_ID)).thenReturn(deliveredOrderWith(PRODUCT_ID));
        when(reviewRepository.existsByUserIdAndProductIdAndOrderId(USER_ID, PRODUCT_ID, ORDER_ID))
                .thenReturn(false);

        Review saved = Review.builder()
                .id("new-review-id")
                .productId(PRODUCT_ID)
                .orderId(ORDER_ID)
                .userId(USER_ID)
                .rating(4)
                .comment("Good quality")
                .build();
        when(reviewRepository.save(any(Review.class))).thenReturn(saved);

        var result = reviewService.createReview(IDP_SUBJECT, request);

        assertThat(result.getId()).isEqualTo("new-review-id");
        assertThat(result.getRating()).isEqualTo(4);

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).save(captor.capture());
        Review persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(USER_ID);
        assertThat(persisted.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(persisted.getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    void createReview_productNotFound_throwsResourceNotFoundException() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();

        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);
        when(productServiceClient.getProduct(PRODUCT_ID))
                .thenThrow(HttpClientErrorException.NotFound.class);

        assertThatThrownBy(() -> reviewService.createReview(IDP_SUBJECT, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createReview_orderNotFound_throwsResourceNotFoundException() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();

        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);
        when(productServiceClient.getProduct(PRODUCT_ID))
                .thenReturn(new ProductServiceClient.ProductResponse(PRODUCT_ID, "Test Product"));
        when(orderServiceClient.getOrder(ORDER_ID))
                .thenThrow(HttpClientErrorException.NotFound.class);

        assertThatThrownBy(() -> reviewService.createReview(IDP_SUBJECT, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createReview_orderBelongsToDifferentUser_throwsBusinessRuleException() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();

        UUID otherUserId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);
        when(productServiceClient.getProduct(PRODUCT_ID))
                .thenReturn(new ProductServiceClient.ProductResponse(PRODUCT_ID, "Test Product"));
        when(orderServiceClient.getOrder(ORDER_ID)).thenReturn(
                new OrderServiceClient.OrderResponse(ORDER_ID, otherUserId, "DELIVERED",
                        List.of(new OrderServiceClient.OrderItemResponse(UUID.randomUUID(), PRODUCT_ID, 1, 10.0)),
                        10.0, OffsetDateTime.now(), OffsetDateTime.now()));

        assertThatThrownBy(() -> reviewService.createReview(IDP_SUBJECT, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void createReview_orderNotDelivered_throwsBusinessRuleException() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();

        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);
        when(productServiceClient.getProduct(PRODUCT_ID))
                .thenReturn(new ProductServiceClient.ProductResponse(PRODUCT_ID, "Test Product"));
        when(orderServiceClient.getOrder(ORDER_ID)).thenReturn(
                new OrderServiceClient.OrderResponse(ORDER_ID, USER_ID, "CONFIRMED",
                        List.of(new OrderServiceClient.OrderItemResponse(UUID.randomUUID(), PRODUCT_ID, 1, 10.0)),
                        10.0, OffsetDateTime.now(), OffsetDateTime.now()));

        assertThatThrownBy(() -> reviewService.createReview(IDP_SUBJECT, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("DELIVERED");
    }

    @Test
    void createReview_orderDoesNotContainProduct_throwsBusinessRuleException() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();

        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);
        when(productServiceClient.getProduct(PRODUCT_ID))
                .thenReturn(new ProductServiceClient.ProductResponse(PRODUCT_ID, "Test Product"));
        when(orderServiceClient.getOrder(ORDER_ID)).thenReturn(
                new OrderServiceClient.OrderResponse(ORDER_ID, USER_ID, "DELIVERED",
                        List.of(new OrderServiceClient.OrderItemResponse(UUID.randomUUID(), "different-product", 1, 10.0)),
                        10.0, OffsetDateTime.now(), OffsetDateTime.now()));

        assertThatThrownBy(() -> reviewService.createReview(IDP_SUBJECT, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not contain");
    }

    @Test
    void createReview_duplicateReview_throwsBusinessRuleException() {
        CreateReviewRequest request = CreateReviewRequest.builder()
                .productId(PRODUCT_ID).orderId(ORDER_ID).rating(3).build();

        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);
        when(productServiceClient.getProduct(PRODUCT_ID))
                .thenReturn(new ProductServiceClient.ProductResponse(PRODUCT_ID, "Test Product"));
        when(orderServiceClient.getOrder(ORDER_ID)).thenReturn(deliveredOrderWith(PRODUCT_ID));
        when(reviewRepository.existsByUserIdAndProductIdAndOrderId(USER_ID, PRODUCT_ID, ORDER_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> reviewService.createReview(IDP_SUBJECT, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already reviewed");
    }

    // ── deleteReview ──────────────────────────────────────────────────────────

    @Test
    void deleteReview_owner_deletesReview() {
        Review review = Review.builder()
                .id("review-1").productId(PRODUCT_ID).orderId(ORDER_ID).userId(USER_ID).rating(5).build();

        when(reviewRepository.findById("review-1")).thenReturn(Optional.of(review));
        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);

        reviewService.deleteReview("review-1", IDP_SUBJECT);

        verify(reviewRepository).deleteById("review-1");
    }

    @Test
    void deleteReview_notOwner_throwsBusinessRuleException() {
        UUID otherUserId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        Review review = Review.builder()
                .id("review-1").productId(PRODUCT_ID).orderId(ORDER_ID).userId(otherUserId).rating(5).build();

        when(reviewRepository.findById("review-1")).thenReturn(Optional.of(review));
        when(userIdResolverService.resolveInternalId(IDP_SUBJECT)).thenReturn(USER_ID);

        assertThatThrownBy(() -> reviewService.deleteReview("review-1", IDP_SUBJECT))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("not the owner");
    }

    @Test
    void deleteReview_notFound_throwsResourceNotFoundException() {
        when(reviewRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.deleteReview("missing", IDP_SUBJECT))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OrderServiceClient.OrderResponse deliveredOrderWith(String productId) {
        return new OrderServiceClient.OrderResponse(
                ORDER_ID,
                USER_ID,
                "DELIVERED",
                List.of(new OrderServiceClient.OrderItemResponse(UUID.randomUUID(), productId, 1, 49.99)),
                49.99,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
