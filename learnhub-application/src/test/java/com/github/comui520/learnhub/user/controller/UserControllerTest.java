package com.github.comui520.learnhub.user.controller;

import com.github.comui520.learnhub.user.config.SecurityConfig;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import com.github.comui520.learnhub.user.mapper.UserRoleMapper;
import com.github.comui520.learnhub.user.security.JwtAuthenticationFilter;
import com.github.comui520.learnhub.user.security.JwtTokenTool;
import com.github.comui520.learnhub.user.security.RestAccessDeniedHandler;
import com.github.comui520.learnhub.user.security.RestAuthenticationEntryPoint;
import com.github.comui520.learnhub.user.service.UserService;
import com.github.comui520.learnhub.web.service.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({
        JwtAuthenticationFilter.class,
        RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class
})
public class UserControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtTokenTool jwtTool;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private UserRoleMapper userRoleMapper;

    @Test
    void shouldReturn401WhenUnauthenticated() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("COMMON_0401"));
    }

    @Test
    void shouldReturnUserResponseWhenAuthenticated() throws Exception {
        given(jwtTool.parseId("agoodtoken"))
                .willReturn(1L);
        given(jwtTool.parseUsername("agoodtoken")).willReturn("learnhub");
        given(userService.findById(1L)).willReturn(
                new UserResponse(1L, "learnhub", "learnhub")
        );

        mvc.perform(
                MockMvcRequestBuilders.get("/api/v1/users/me")
                        .header("Authorization", "Bearer agoodtoken")
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.username").value("learnhub"))
                .andExpect(jsonPath("$.data.nickname").value("learnhub"));
        verify(userService).findById(1L);
    }

    @Test
    void shouldReturn403WhenUserRoleIsNotAdmin() throws Exception {
        given(jwtTool.parseId("agoodtoken")).willReturn(1L);
        given(jwtTool.parseUsername("agoodtoken")).willReturn("learnhub");
        given(userRoleMapper.selectPermissionCodesByUserId(1L)).willReturn(List.of());
        mvc.perform(
                MockMvcRequestBuilders.get("/api/v1/users")
                        .header("Authorization", "Bearer agoodtoken")
        )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_0403"));
    }

    @Test
    void shouldReturnUserListWhenUserIsAdmin() throws Exception{
        given(jwtTool.parseId("agoodtoken")).willReturn(1L);
        given(jwtTool.parseUsername("agoodtoken")).willReturn("learnhub");
        given(userRoleMapper.selectPermissionCodesByUserId(1L)).willReturn(List.of("user:list"));
        given(userService.userList()).willReturn(List.of(
                new UserResponse(1L, "learnhub", "learnhub")
        ));
        mvc.perform(
                MockMvcRequestBuilders.get("/api/v1/users")
                        .header("Authorization", "Bearer agoodtoken")
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1L))
                .andExpect(jsonPath("$.data[0].username").value("learnhub"))
                .andExpect(jsonPath("$.data[0].nickname").value("learnhub"));
    }


}
