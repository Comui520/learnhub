package com.github.comui520.learnhub.user.controller;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import com.github.comui520.learnhub.user.service.AuthService;
import com.github.comui520.learnhub.web.service.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
public class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @Test
    void shouldReturnUserWhenRegisterSucceeds() throws Exception {
        RegisterRequest request = new RegisterRequest("learnhub", "password123");
        given(authService.register(request))
                .willReturn(new UserResponse(1L, "learnhub", "learnhub"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("learnhub"));
    }

    // 1) shouldReturnBadRequestWhenUsernameIsBlank
    //    请求体 {"username":"","password":"password123"}
    //    断言 400、code=COMMON_0400、data[0].field=username、authService 没被调用
    void shouldReturnBadRequestWhenUsernameIsBlank() throws Exception {
        RegisterRequest request = new RegisterRequest("", "password123");

        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request))
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"))
                .andExpect(jsonPath("$.data[0].field").value("username"));
                verifyNoInteractions(authService);
    }


    // 2) shouldReturnConflictWhenUsernameAlreadyExists
    //    given(authService.register(any())).willThrow(new BusinessException(UserErrorCode.USERNAME_ALREADY_EXISTS))
    //    断言 409、code=USER_ERROR_0409
    void shouldReturnConflictWhenUsernameAlreadyExists() throws Exception{
        RegisterRequest request = new RegisterRequest("learnhub", "password123");
        given(authService.register(request))
                .willThrow(new BusinessException(UserErrorCode.USERNAME_ALREADY_EXISTS));

        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request))
        )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ERROR_0409"));
    }
}
