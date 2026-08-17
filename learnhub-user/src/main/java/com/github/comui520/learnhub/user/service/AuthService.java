package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.LoginRequest;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.TokenResponse;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.AuditLog;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.RoleMapper;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import com.github.comui520.learnhub.user.mapper.UserRoleMapper;
import com.github.comui520.learnhub.user.security.JwtTokenTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

public interface AuthService {
    public UserResponse register(RegisterRequest request);
    public TokenResponse login(LoginRequest request, String ip);
}
