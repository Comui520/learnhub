package com.github.comui520.learnhub.study.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "learnhub.redis.study")
public class StudyRedisProperties {

    public String stdQstOptKey;

    public Long expire;
}
