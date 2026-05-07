package com.ricsanfre.reviews;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// scanBasePackages covers com.ricsanfre.common (shared exception handler, security utils)
// and com.ricsanfre.reviews (this service's own classes)
@SpringBootApplication(scanBasePackages = "com.ricsanfre")
public class ReviewsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewsServiceApplication.class, args);
    }
}
