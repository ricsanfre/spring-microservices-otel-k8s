package com.ricsanfre.user.service;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.user.api.model.UpdateUserRequest;
import com.ricsanfre.user.api.model.UserResponse;
import com.ricsanfre.user.domain.User;
import com.ricsanfre.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    // ── helpers ─────────────────────────────────────────────────────────────

    private static JwtAuthenticationToken jwtAuth(String sub, String email,
            String username, String firstName, String lastName) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(sub)
                .claim("email", email)
                .claim("preferred_username", username)
                .claim("given_name", firstName)
                .claim("family_name", lastName)
                .claim("scope", "openid profile email users:read")
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private static User buildUser(String idpSubject) {
        return User.builder()
                .id(UUID.randomUUID())
                .idpSubject(idpSubject)
                .email("user@test.com")
                .username("testuser")
                .firstName("Test")
                .lastName("User")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ── getOrCreateCurrentUser ───────────────────────────────────────────────

    @Test
    void getOrCreateCurrentUser_existingUser_returnsExistingProfileWithoutSave() {
        var auth = jwtAuth("sub-1", "user@test.com", "testuser", "Test", "User");
        var existing = buildUser("sub-1");
        when(userRepository.findByIdpSubject("sub-1")).thenReturn(Optional.of(existing));

        UserResponse result = userService.getOrCreateCurrentUser(auth);

        assertThat(result.getUsername()).isEqualTo("testuser");
        assertThat(result.getEmail()).isEqualTo("user@test.com");
        verify(userRepository, never()).save(any());
    }

    @Test
    void getOrCreateCurrentUser_newUser_registersAndReturnsProfile() {
        var auth = jwtAuth("sub-new", "new@test.com", "newuser", "New", "User");
        when(userRepository.findByIdpSubject("sub-new")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@test.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            u.setCreatedAt(Instant.now());
            return u;
        });

        UserResponse result = userService.getOrCreateCurrentUser(auth);

        assertThat(result.getEmail()).isEqualTo("new@test.com");
        assertThat(result.getUsername()).isEqualTo("newuser");
        assertThat(result.getFirstName()).isEqualTo("New");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void getOrCreateCurrentUser_newIdpSubjectSameEmail_relinksIdpSubject() {
        var auth = jwtAuth("sub-new", "user@test.com", "testuser", "Test", "User");
        var existing = buildUser("sub-old");
        when(userRepository.findByIdpSubject("sub-new")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(existing));
        when(userRepository.save(existing)).thenReturn(existing);

        UserResponse result = userService.getOrCreateCurrentUser(auth);

        assertThat(result.getEmail()).isEqualTo("user@test.com");
        assertThat(existing.getIdpSubject()).isEqualTo("sub-new");
        verify(userRepository).save(existing);
    }

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    void findById_existingUser_returnsUser() {
        var id = UUID.randomUUID();
        var user = buildUser("sub-1");
        user.setId(id);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        UserResponse result = userService.findById(id);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    void findById_nonExistentUser_throwsResourceNotFoundException() {
        var id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // ── findByIdpSubject ─────────────────────────────────────────────────────

    @Test
    void findByIdpSubject_existingUser_returnsUser() {
        var user = buildUser("sub-abc");
        when(userRepository.findByIdpSubject("sub-abc")).thenReturn(Optional.of(user));

        UserResponse result = userService.findByIdpSubject("sub-abc");

        assertThat(result.getUsername()).isEqualTo("testuser");
    }

    @Test
    void findByIdpSubject_nonExistentUser_throwsResourceNotFoundException() {
        when(userRepository.findByIdpSubject("sub-xyz")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findByIdpSubject("sub-xyz"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    void update_ownProfile_updatesAndPersists() {
        var id = UUID.randomUUID();
        var user = buildUser("sub-1");
        user.setId(id);
        var auth = jwtAuth("sub-1", "user@test.com", "testuser", "Test", "User");
        var request = UpdateUserRequest.builder().firstName("Updated").lastName("Name").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        UserResponse result = userService.update(id, request, auth);

        assertThat(result).isNotNull();
        verify(userRepository).save(user);
        assertThat(user.getFirstName()).isEqualTo("Updated");
        assertThat(user.getLastName()).isEqualTo("Name");
    }

    @Test
    void update_onlyProvidedFieldsAreChanged() {
        var id = UUID.randomUUID();
        var user = buildUser("sub-1");
        user.setId(id);
        user.setFirstName("Original");
        user.setLastName("Last");
        var auth = jwtAuth("sub-1", "user@test.com", "testuser", "Test", "User");
        // only username changed
        var request = UpdateUserRequest.builder().username("newname").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        userService.update(id, request, auth);

        assertThat(user.getUsername()).isEqualTo("newname");
        assertThat(user.getFirstName()).isEqualTo("Original");  // unchanged
        assertThat(user.getLastName()).isEqualTo("Last");        // unchanged
    }

    @Test
    void update_otherUsersProfile_throwsAccessDeniedException() {
        var id = UUID.randomUUID();
        var user = buildUser("sub-owner");
        user.setId(id);
        var auth = jwtAuth("sub-attacker", "other@test.com", "other", "Other", "Person");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.update(id, new UpdateUserRequest(), auth))
                .isInstanceOf(AccessDeniedException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void update_nonExistentUser_throwsResourceNotFoundException() {
        var id = UUID.randomUUID();
        var auth = jwtAuth("sub-1", "user@test.com", "testuser", "Test", "User");
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.update(id, new UpdateUserRequest(), auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
