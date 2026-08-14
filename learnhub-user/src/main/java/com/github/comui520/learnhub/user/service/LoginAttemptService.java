package com.github.comui520.learnhub.user.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final Integer DEFAULT_MAX_FAILURES = 5;
    private static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(15);

    private final Integer maxFailures;
    private final Duration lockDuration;
    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_LOCK_DURATION);
    }

    public LoginAttemptService(Integer maxFailures, Duration lockDuration) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    // Check if the user is locked
    public Boolean isLocked(String username) {
        AttemptState state = attempts.get(username);
        if (state == null) {
            return false;
        }
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(Instant.now())) {
            return true;
        }
        // 锁定过期, 解除锁定
        if (state.lockedUntil() != null && state.lockedUntil().isBefore(Instant.now())){
            attempts.remove(username);
            return false;
        }
        return false;
    }

    //
    public void recordFailure(String username) {
        attempts.compute(username, (key, state) -> {
            Integer count = state == null ? 1 : state.count() + 1;
            Instant lockedUntil = count >= maxFailures ?
                    Instant.now().plus(lockDuration) :
                    null;
            return new AttemptState(count, lockedUntil);
        });

    }

    public void reset(String username) {
        attempts.remove(username);
    }

    record AttemptState(Integer count, Instant lockedUntil) {
    }
}
