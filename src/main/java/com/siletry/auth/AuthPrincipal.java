package com.siletry.auth;

/** What we know about the caller once the JWT is verified. */
public record AuthPrincipal(Long userId, Long clinicId, Role role, String name) {}
