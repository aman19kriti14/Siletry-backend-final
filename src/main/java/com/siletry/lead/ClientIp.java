package com.siletry.lead;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The visitor's IP behind Railway's proxy. Anything the client sends in X-Forwarded-For can be
 * forged, but the proxy appends the address it actually saw, so the right-most entry is the one
 * we can trust (one proxy hop). Only used, hashed, for rate limiting and abuse tracing.
 */
final class ClientIp {

    private ClientIp() {}

    static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty() && last.length() <= 64) return last;
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && real.length() <= 64) return real.trim();
        return request.getRemoteAddr();
    }
}
