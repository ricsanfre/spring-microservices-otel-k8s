package com.ricsanfre.user.controller;

import com.ricsanfre.user.api.UsersApi;
import com.ricsanfre.user.api.model.UpdateUserRequest;
import com.ricsanfre.user.api.model.UserResponse;
import com.ricsanfre.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController implements UsersApi {

    private final UserService userService;

    @Override
    public ResponseEntity<UserResponse> getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(userService.getOrCreateCurrentUser(auth));
    }

    @Override
    public ResponseEntity<UserResponse> getUserById(UUID id) {
        return ResponseEntity.ok(userService.findById(id));
    }

    @Override
    @PreAuthorize("hasAuthority('SCOPE_users:resolve')")
    public ResponseEntity<UserResponse> resolveUser(String idpSubject) {
        return ResponseEntity.ok(userService.findByIdpSubject(idpSubject));
    }

    @Override
    public ResponseEntity<UserResponse> updateUser(UUID id, UpdateUserRequest updateUserRequest) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return ResponseEntity.ok(userService.update(id, updateUserRequest, auth));
    }
}
