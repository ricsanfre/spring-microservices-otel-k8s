package com.ricsanfre.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// scanBasePackages covers com.ricsanfre.common (shared exception handler, security utils)
// and com.ricsanfre.product (this service's own classes)
@SpringBootApplication(scanBasePackages = "com.ricsanfre")
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
