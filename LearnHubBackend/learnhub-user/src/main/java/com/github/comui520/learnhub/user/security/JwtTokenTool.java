package com.github.comui520.learnhub.user.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Getter
@Component
public class JwtTokenTool {
    private final SecretKey secret;
    private final Long expirationSeconds;

    public JwtTokenTool(JwtProperties jwtProperties) {
        this.secret = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));

        this.expirationSeconds = jwtProperties.getExpiration();
    }

    public String createToken(Long Id, String username) {
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + expirationSeconds);
        return Jwts.builder()
                .subject(Id.toString())
                .claim("username", username)
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(secret)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secret)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long parseId(String token) {
        Claims claims = this.parseClaims(token);
        return Long.valueOf(claims.getSubject());
    }

    public String parseUsername(String token) {
        Claims claims = this.parseClaims(token);

        return claims.get("username", String.class);
    }

    public String parseNickname(String token) {
        Claims claims = this.parseClaims(token);
        return claims.get("nickname", String.class);
    }
}
