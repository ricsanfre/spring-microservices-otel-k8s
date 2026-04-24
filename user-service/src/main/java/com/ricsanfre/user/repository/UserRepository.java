package com.ricsanfre.user.repository;

import com.ricsanfre.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByIdpSubject(String idpSubject);

    Optional<User> findByEmail(String email);
}
