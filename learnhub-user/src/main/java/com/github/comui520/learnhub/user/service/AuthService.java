package com.github.comui520.learnhub.user.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.LoginRequest;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.TokenResponse;
import com.github.comui520.learnhub.user.dto.UserResponse;
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

@Service
public class AuthService extends ServiceImpl<UserMapper, User> {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final UserRoleMapper userRoleMapper;

    private final RoleMapper roleMapper;

    private final JwtTokenTool jwtTool;

    private final LoginAttemptService loginAttemptService;

    private final AuditLogService auditLogService;

    public AuthService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenTool jwtTool,
            UserRoleMapper userRoleMapper,
            RoleMapper roleMapper,
            LoginAttemptService loginAttemptService,
            AuditLogService auditLogService
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTool = jwtTool;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.loginAttemptService = loginAttemptService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userMapper.selectByUsername(request.username()) != null) {
            throw new BusinessException(UserErrorCode.USERNAME_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(request.username());
        user.setNickname(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(1);
        userMapper.insert(user);
        userRoleMapper.insertUserRole(user.getId(), roleMapper.selectIdByCode("USER"));

        return new UserResponse(user.getId(), user.getUsername(), user.getNickname());
    }

    public TokenResponse login(LoginRequest request, String ip) {
        String username = request.username();
        String password = request.password();
        if (loginAttemptService.isLocked(username)) {
            auditLogService.record(username, "LOGIN_LOCKED", ip, null);
            log.warn("login locked username={}", username);
            throw new BusinessException(UserErrorCode.ACCOUNT_LOCKED);
        }

        User user = userMapper.selectByUsername(username);

        if (Objects.isNull(user) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptService.recordFailure(username);
            auditLogService.record(username, "LOGIN_FAILED", ip, "bad credentials");
            log.warn("login failed username={}", username);
            throw new BusinessException(UserErrorCode.INVALID_CREDENTIALS);
        }

        loginAttemptService.reset(username);

        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("login disabled username={}", username);
            auditLogService.record(username, "LOGIN_DISABLED", ip, null);
            throw new BusinessException(UserErrorCode.ACCOUNT_DISABLED);
        }

        log.info("login success username={}", username);
        auditLogService.record(username, "LOGIN_SUCCESS", ip, null);
        return new TokenResponse(jwtTool.createToken(user.getId(), user.getUsername()), "Bearer", jwtTool.getExpirationSeconds());
    }
}
