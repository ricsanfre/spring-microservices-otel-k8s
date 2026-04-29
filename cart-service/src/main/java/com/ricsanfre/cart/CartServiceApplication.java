package com.ricsanfre.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// scanBasePackages covers com.ricsanfre.common (shared exception handler, security utils)
// and com.ricsanfre.cart (this service's own classes)
@SpringBootApplication(scanBasePackages = "com.ricsanfre")
public class CartServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}
