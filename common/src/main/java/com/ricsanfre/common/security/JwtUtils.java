package com.ricsanfre.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

public final class JwtUtils {

    private JwtUtils() {}

    public static String getSubject(Authentication authentication) {
        return getJwt(authentication).getSubject();
    }

    public static String getEmail(Authentication authentication) {
        return getJwt(authentication).getClaimAsString("email");
    }

    public static String getGivenName(Authentication authentication) {
        return getJwt(authentication).getClaimAsString("given_name");
    }

    public static String getFamilyName(Authentication authentication) {
        return getJwt(authentication).getClaimAsString("family_name");
    }

    public static String getPreferredUsername(Authentication authentication) {
        return getJwt(authentication).getClaimAsString("preferred_username");
    }

    private static Jwt getJwt(Authentication authentication) {
        return (Jwt) authentication.getPrincipal();
    }
}
