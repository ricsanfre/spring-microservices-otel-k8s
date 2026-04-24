package com.ricsanfre.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// scanBasePackages covers com.ricsanfre.common (shared exception handler, security utils)
// and com.ricsanfre.user (this service's own classes)
@SpringBootApplication(scanBasePackages = "com.ricsanfre")
@EnableJpaAuditing
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
