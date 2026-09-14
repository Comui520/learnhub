package com.github.comui520.learnhub.user.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

public class JwtTokenToolTest {
    private JwtTokenTool tool;

    @BeforeEach
    public void setUp(){
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-test-secret-test-secret-test-secret");
        jwtProperties.setExpiration(36000L);
        tool = new JwtTokenTool(jwtProperties);
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtProperties expiredProperties = new JwtProperties();
        expiredProperties.setSecret("test-secret-test-secret-test-secret-test-secret");
        expiredProperties.setExpiration(-10L); // 有效期设为负数，签出来就是已过期
        JwtTokenTool expiredProvider = new JwtTokenTool(expiredProperties);

        String token = expiredProvider.createToken(1L, "learnhub");

        assertThatThrownBy(() -> expiredProvider.parseId(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    public void shouldCreateAndParseToken(){
        String token = tool.createToken(1L, "learnhub");
        assertThat(tool.parseId(token))
                .isEqualTo(1L);
        assertThat(tool.parseUsername(token))
                .isEqualTo("learnhub");

    }

    //    在正常 token 末尾加一个字符，parseUserId 应抛 JwtException
    @Test
    public void shouldRejectTamperedToken(){
        String token = tool.createToken(1L, "learnhub") + "q";
        assertThatThrownBy(() -> tool.parseId(token))
                .isInstanceOf(JwtException.class);
    }
}
