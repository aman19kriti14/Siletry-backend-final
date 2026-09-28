package com.siletry.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expiryHours;

    public JwtService(@Value("${siletry.jwt.secret}") String secret,
                      @Value("${siletry.jwt.expiry-hours}") long expiryHours) {
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryHours = expiryHours;
    }

    public String issue(AppUser user) {
        return issue(user, expiryHours);
    }

    /** remember = "Keep me signed in for 30 days". */
    public String issue(AppUser user, boolean remember) {
        return issue(user, remember ? 24L * 30 : expiryHours);
    }

    public String issue(AppUser user, long hours) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("clinicId", user.getClinicId())
                .claim("role", user.getRole().name())
                .claim("name", user.getName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(hours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    public AuthPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new AuthPrincipal(
                Long.valueOf(c.getSubject()),
                ((Number) c.get("clinicId")).longValue(),
                Role.valueOf((String) c.get("role")),
                (String) c.get("name"));
    }
}
