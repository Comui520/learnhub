package com.github.comui520.learnhub.user.service;

import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.RegisterRequest;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.RoleMapper;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import com.github.comui520.learnhub.user.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;


@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {
    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void shouldEncodePasswordAndInsertUserWhenUsernameIsFree(){
        given(userMapper.selectByUsername("learnhub")).willReturn(null);
        given(passwordEncoder.encode("password123")).willReturn("encodedPassword");
        given(roleMapper.selectIdByCode("USER")).willReturn(1L);

        UserResponse userResponse = authService.register(new RegisterRequest("learnhub", "password123"));

        assertThat(userResponse.username()).isEqualTo("learnhub");

        verify(userMapper).insert(any(User.class));
    }

    @Test
    void shouldThrowWhenUsernameAlreadyExists(){
        given(userMapper.selectByUsername("learnhub")).willReturn(new User());
        assertThatThrownBy(() -> authService.register(new RegisterRequest("learnhub", "password123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(UserErrorCode.USERNAME_ALREADY_EXISTS.message());
    }

}

