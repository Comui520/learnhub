package com.github.comui520.learnhub.user.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.user.entity.AuditLog;
import com.github.comui520.learnhub.user.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

//private Long id;
//
//private String username;
//
//private String action;
//
//private String ip;
//
//private String detail;
//
//private LocalDateTime createdAt;
public interface AuditLogService extends IService<AuditLog> {
    public void record(String username, String action, String ip, String detail);
}
