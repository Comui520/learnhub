package com.github.comui520.learnhub.user.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public interface UserService extends IService<User> {

    public UserResponse findById(Long id);

    public List<UserResponse> userList();
}
