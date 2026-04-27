package com.ricsanfre.user.service;

import com.ricsanfre.common.exception.ResourceNotFoundException;
import com.ricsanfre.common.security.JwtUtils;
import com.ricsanfre.user.api.model.BillingAccount;
import com.ricsanfre.user.api.model.ShippingAddress;
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

        var addr = request.getShippingAddress();
        if (addr != null) {
            if (addr.getStreet() != null)     user.setAddressStreet(addr.getStreet());
            if (addr.getCity() != null)       user.setAddressCity(addr.getCity());
            if (addr.getState() != null)      user.setAddressState(addr.getState());
            if (addr.getPostalCode() != null) user.setAddressPostalCode(addr.getPostalCode());
            if (addr.getCountry() != null)    user.setAddressCountry(addr.getCountry());
        }

        var billing = request.getBillingAccount();
        if (billing != null) {
            if (billing.getCardHolder() != null) user.setBillingCardHolder(billing.getCardHolder());
            if (billing.getCardLast4() != null)  user.setBillingCardLast4(billing.getCardLast4());
            if (billing.getCardExpiry() != null) user.setBillingCardExpiry(billing.getCardExpiry());
            if (billing.getSameAsShipping() != null)
                user.setBillingSameAsShipping(billing.getSameAsShipping());
        }

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
        ShippingAddress addr = null;
        if (user.getAddressStreet() != null || user.getAddressCity() != null
                || user.getAddressPostalCode() != null || user.getAddressCountry() != null) {
            addr = ShippingAddress.builder()
                    .street(user.getAddressStreet())
                    .city(user.getAddressCity())
                    .state(user.getAddressState())
                    .postalCode(user.getAddressPostalCode())
                    .country(user.getAddressCountry())
                    .build();
        }

        BillingAccount billing = null;
        if (user.getBillingCardHolder() != null || user.getBillingCardLast4() != null) {
            billing = BillingAccount.builder()
                    .cardHolder(user.getBillingCardHolder())
                    .cardLast4(user.getBillingCardLast4())
                    .cardExpiry(user.getBillingCardExpiry())
                    .sameAsShipping(user.isBillingSameAsShipping())
                    .build();
        }

        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .shippingAddress(addr)
                .billingAccount(billing)
                .createdAt(user.getCreatedAt() != null
                        ? user.getCreatedAt().atOffset(ZoneOffset.UTC) : null)
                .updatedAt(user.getUpdatedAt() != null
                        ? user.getUpdatedAt().atOffset(ZoneOffset.UTC) : null)
                .build();
    }
}
