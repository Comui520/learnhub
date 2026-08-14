package com.github.comui520.learnhub.user.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;


public class LoginAttemptServiceTest {
    @Test
    public void shouldLockWhenMaxFailuresExceeded() {
        LoginAttemptService service = new LoginAttemptService(3, Duration.ofSeconds(15L));
        service.recordFailure("user1");
        service.recordFailure("user1");
        service.recordFailure("user1");
        service.recordFailure("user1");
        assertThat(service.isLocked("user1")).isTrue();
    }

    @Test
    public void shouldUnlockAfterTimeout() throws InterruptedException {
        LoginAttemptService service = new LoginAttemptService(3, Duration.ofMillis(15L));
        service.recordFailure("user2");
        service.recordFailure("user2");
        service.recordFailure("user2");
        assertThat(service.isLocked("user2")).isTrue();
        Thread.sleep(20);
        assertThat(service.isLocked("user2")).isFalse();
    }

    @Test
    public void shouldUnlockImmediatelyAfterReset(){
        LoginAttemptService service = new LoginAttemptService(3, Duration.ofMillis(15L));
        service.recordFailure("user3");
        service.recordFailure("user3");
        service.recordFailure("user3");
        assertThat(service.isLocked("user3")).isTrue();
        service.reset("user3");
        assertThat(service.isLocked("user3")).isFalse();
    }
}
