package com.siletry.auth;

import com.siletry.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Static access to the logged-in user. Every service call scopes its queries with clinicId(). */
public final class CurrentUser {

    private CurrentUser() {}

    public static AuthPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal p)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        return p;
    }

    public static Long clinicId() {
        return get().clinicId();
    }

    public static Long userId() {
        return get().userId();
    }

    public static void requireOwner() {
        if (get().role() != Role.OWNER) {
            throw ApiException.forbidden("Only the clinic owner can do this");
        }
    }
}
