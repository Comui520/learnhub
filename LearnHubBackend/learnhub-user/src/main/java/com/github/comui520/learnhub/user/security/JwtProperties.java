package com.github.comui520.learnhub.user.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "learnhub.jwt")
public class JwtProperties {
    private String secret;
    private Long expiration = 36000L;
}
