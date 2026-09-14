package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.user.redis.UserRedisProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    private static final String FAIL_KEY = "us:login:fail:user1";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserRedisProperties redisProperties;

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisProperties.getUsLgFlKey()).willReturn("us:login:fail:");
        service = new LoginAttemptService(
                3,
                Duration.ofMinutes(15),
                redisProperties,
                redisTemplate
        );
    }

    @Test
    void shouldLockWhenMaxFailuresReached() {
        given(valueOperations.increment(FAIL_KEY)).willReturn(1L, 2L, 3L);
        given(valueOperations.get(FAIL_KEY)).willReturn("3");

        service.recordFailure("user1");
        service.recordFailure("user1");
        service.recordFailure("user1");

        assertThat(service.isLocked("user1")).isTrue();
        verify(redisTemplate).expire(FAIL_KEY, Duration.ofHours(24));
        verify(redisTemplate).expire(FAIL_KEY, Duration.ofMinutes(15));
    }

    @Test
    void shouldRemainUnlockedBeforeMaxFailures() {
        given(valueOperations.get(FAIL_KEY)).willReturn("2");

        assertThat(service.isLocked("user1")).isFalse();
    }

    @Test
    void shouldUnlockImmediatelyAfterReset() {
        given(valueOperations.get(FAIL_KEY)).willReturn("3", null);

        assertThat(service.isLocked("user1")).isTrue();
        service.reset("user1");

        assertThat(service.isLocked("user1")).isFalse();
        verify(redisTemplate).delete(FAIL_KEY);
    }
}
