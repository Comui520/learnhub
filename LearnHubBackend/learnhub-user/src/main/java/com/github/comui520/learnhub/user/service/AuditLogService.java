package com.github.comui520.learnhub.user.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.user.entity.AuditLog;
import com.github.comui520.learnhub.user.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditLogService extends ServiceImpl<AuditLogMapper, AuditLog> {

    public void record(String username, String action, String ip, String detail) {
        this.save(new AuditLog(null, username, action, ip, detail, LocalDateTime.now()));
    }
}
