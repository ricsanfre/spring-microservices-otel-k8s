package com.ricsanfre.user.service;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.common.security.JwtUtils;
import com.ricsanfre.user.api.model.UpdateUserRequest;
import com.ricsanfre.user.api.model.UserResponse;
import com.ricsanfre.user.domain.User;
import com.ricsanfre.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse getOrCreateCurrentUser(Authentication authentication) {
        String idpSubject = JwtUtils.getSubject(authentication);
        User user = userRepository.findByIdpSubject(idpSubject)
                .orElseGet(() -> {
                    String email = JwtUtils.getEmail(authentication);
                    return userRepository.findByEmail(email)
                            .map(existing -> {
                                log.info("Re-linking user profile email={} to new idp_subject={}", email, idpSubject);
                                existing.setIdpSubject(idpSubject);
                                return userRepository.save(existing);
                            })
                            .orElseGet(() -> lazyRegister(authentication, idpSubject));
                });
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse findById(UUID id) {
        return userRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    @Transactional(readOnly = true)
    public UserResponse findByIdpSubject(String idpSubject) {
        return userRepository.findByIdpSubject(idpSubject)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("User with idp_subject", idpSubject));
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request, Authentication authentication) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));

        String currentSubject = JwtUtils.getSubject(authentication);
        if (!user.getIdpSubject().equals(currentSubject)) {
            throw new AccessDeniedException("You can only update your own profile");
        }

        if (request.getUsername() != null)  user.setUsername(request.getUsername());
        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null)  user.setLastName(request.getLastName());

        return toResponse(userRepository.save(user));
    }

    private User lazyRegister(Authentication authentication, String idpSubject) {
        log.info("Lazy-registering new user profile for idp_subject={}", idpSubject);
        User newUser = User.builder()
                .idpSubject(idpSubject)
                .email(JwtUtils.getEmail(authentication))
                .username(JwtUtils.getPreferredUsername(authentication))
                .firstName(JwtUtils.getGivenName(authentication))
                .lastName(JwtUtils.getFamilyName(authentication))
                .build();
        return userRepository.save(newUser);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .createdAt(user.getCreatedAt() != null
                        ? user.getCreatedAt().atOffset(ZoneOffset.UTC) : null)
                .updatedAt(user.getUpdatedAt() != null
                        ? user.getUpdatedAt().atOffset(ZoneOffset.UTC) : null)
                .build();
    }
}
