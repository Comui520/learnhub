package com.github.comui520.learnhub.user.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.user.entity.AuditLog;
import com.github.comui520.learnhub.user.mapper.AuditLogMapper;
import com.github.comui520.learnhub.user.service.AuditLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog> implements AuditLogService {
    public void record(String username, String action, String ip, String detail) {
        this.save(new AuditLog(null, username, action, ip, detail, LocalDateTime.now()));
    }
}
