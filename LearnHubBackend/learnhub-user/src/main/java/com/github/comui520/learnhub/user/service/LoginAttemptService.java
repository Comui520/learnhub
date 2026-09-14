package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.user.redis.UserRedisProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {
    private final Integer maxFailures;
    private final Duration lockDuration;
    private final StringRedisTemplate redisTemplate;
    private final UserRedisProperties userRedisProperties;
    //private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    // 计数窗口期：未锁定时，失败记录的保留时长（24小时内累计失败次数）
    private static final Duration COUNT_WINDOW = Duration.ofHours(24);

    public LoginAttemptService(
            @Value("${learnhub.login.attempt.max-failures:5}") Integer maxFailures,
            @Value("${learnhub.login.attempt.lock-duration:15m}") Duration lockDuration,
            UserRedisProperties userRedisProperties,
            StringRedisTemplate redisTemplate
    ) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.userRedisProperties = userRedisProperties;
        this.redisTemplate = redisTemplate;
    }

    // 纯查询：只判断是否锁定，不删记录（否则未达阈值的计数被清，永远封不了）
    public Boolean isLocked(String username) {
        String key = buildFailCountKey(username);
        String countStr = redisTemplate.opsForValue().get(key);
        if (countStr == null) {
            return false;
        }

        int count = Integer.parseInt(countStr);
        return count >= maxFailures;
    }

    private String buildFailCountKey(String username) {
        // 假设 UserRedisProperties 中有 getPrefix() 方法返回业务前缀
        // 如果没有，可直接写死为 "learnhub:login:fail:" 格式
        return userRedisProperties.getUsLgFlKey() + username;
    }

    public void recordFailure(String username) {
        String key = buildFailCountKey(username);
        Long count = redisTemplate.opsForValue().increment(key);

        if (count == null) {
            return;
        }

        if (count == 1) {
            redisTemplate.expire(key, COUNT_WINDOW);
        }

        if (count >= maxFailures) {
            redisTemplate.expire(key, lockDuration);
        }
    }

    public void reset(String username) {
        redisTemplate.delete(buildFailCountKey(username));
    }
}
