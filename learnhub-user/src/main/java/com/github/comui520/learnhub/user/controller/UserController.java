package com.github.comui520.learnhub.user.controller;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.service.UserService;
import com.github.comui520.learnhub.user.service.impl.UserServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "User", description = "当前用户")
@RestController
@RequestMapping("/api/v1/users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    public UserController(UserServiceImpl userServiceImpl) {
        this.userService = userServiceImpl;
    }

    @Operation(summary = "当前用户", description = "获取当前登录用户信息")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回当前用户"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）")
    })
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long id = (Long) auth.getPrincipal();
        return ApiResponse.success(userService.findById(id));
    }

    @GetMapping
//    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "用户列表", description = "只有管理员角色才能访问, 获取用户列表")
    @PreAuthorize("hasAuthority('user:list')")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返回用户列表"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "无权限（COMMON_0403）")
    })
    public ApiResponse<List<UserResponse>> userList(){
        return ApiResponse.success(userService.userList());
    }
}
