package com.ricsanfre.product;

import tools.jackson.databind.ObjectMapper;
import com.ricsanfre.product.api.model.CreateProductRequest;
import com.ricsanfre.product.api.model.UpdateProductRequest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Layer-1 integration tests for {@link com.ricsanfre.product.controller.ProductController}.
 *
 * <p>Spins up the full Spring Boot context against a real MongoDB container.
 * JWT verification is bypassed with {@code @MockitoBean JwtDecoder}; the {@code jwt()}
 * MockMvc post-processor injects pre-built authentication directly into the SecurityContext.
 *
 * <p>Tests are ordered to simulate a realistic product lifecycle: create → read → update → delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Tag("L1")
@ActiveProfiles("l1test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductControllerL1Test {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    JwtDecoder jwtDecoder;

    // Set by order-3 test, consumed by subsequent tests.
    static String createdProductId;

    // ── JWT helpers ──────────────────────────────────────────────────────────

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor adminJwt() {
        return jwt().authorities(
                new SimpleGrantedAuthority("SCOPE_products:read"),
                new SimpleGrantedAuthority("SCOPE_products:write"),
                new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void listProducts_emptyDatabase_returns200WithEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @Order(2)
    void createProduct_noAuth_returns401() throws Exception {
        var request = CreateProductRequest.builder().name("Widget").price(9.99).category("tools").stockQty(10).build();

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void createProduct_withAdminJwt_returns201AndPersists() throws Exception {
        var request = CreateProductRequest.builder()
                .name("Test Widget")
                .description("A fine widget")
                .price(19.99)
                .category("tools")
                .stockQty(50)
                .build();

        var result = mockMvc.perform(post("/api/v1/products")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name").value("Test Widget"))
                .andExpect(jsonPath("$.price").value(19.99))
                .andExpect(jsonPath("$.category").value("tools"))
                .andReturn();

        createdProductId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    @Test
    @Order(4)
    void getProductById_afterCreate_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}", createdProductId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdProductId))
                .andExpect(jsonPath("$.name").value("Test Widget"));
    }

    @Test
    @Order(5)
    void listProducts_afterCreate_returnsOneProduct() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Test Widget"));
    }

    @Test
    @Order(6)
    void listProducts_withMatchingCategory_returnsFiltered() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("category", "tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @Order(7)
    void listProducts_withNonMatchingCategory_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("category", "electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @Order(8)
    void updateProduct_withAdminJwt_returns200() throws Exception {
        var request = UpdateProductRequest.builder().name("Updated Widget").stockQty(100).build();

        mockMvc.perform(put("/api/v1/products/{id}", createdProductId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Widget"))
                .andExpect(jsonPath("$.stockQty").value(100))
                .andExpect(jsonPath("$.description").value("A fine widget"));  // unchanged
    }

    @Test
    @Order(9)
    void deleteProduct_withAdminJwt_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/products/{id}", createdProductId)
                        .with(adminJwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Order(10)
    void getProductById_afterDelete_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}", createdProductId))
                .andExpect(status().isNotFound());
    }
}
