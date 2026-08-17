package com.github.comui520.learnhub.user.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public interface LoginAttemptService {

    public Boolean isLocked(String username);
    public void recordFailure(String username);
    public void reset(String username);

}
