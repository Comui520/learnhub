package com.github.comui520.learnhub.user.controller;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.user.dto.LoginRequest;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.TokenResponse;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.service.AuditLogService;
import com.github.comui520.learnhub.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "注册, 登录")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(
            AuthService authService
    ){
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(summary = "注册", description = "注册新账号, 全局唯一用户名")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "注册成功，返回用户信息"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "用户名已存在（USER_ERROR_0409）")
    })
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request){
        return ApiResponse.success(authService.register(request));
    }

    @Operation(summary = "登录", description = "校验用户名密码，签发 JWT")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "用户登录成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "参数错误（COMMON_0400）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "用户名或密码错误（USER_ERROR_0401）")
    })
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ){
        return ApiResponse.success(authService.login(request, httpRequest.getRemoteAddr()));
    }



}
