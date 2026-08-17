package com.github.comui520.learnhub.user.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.user.UserErrorCode;
import com.github.comui520.learnhub.user.dto.UserResponse;
import com.github.comui520.learnhub.user.entity.User;
import com.github.comui520.learnhub.user.mapper.UserMapper;
import com.github.comui520.learnhub.user.service.UserService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserResponse findById(Long id) {
        User user = userMapper.selectById(id);
        if (Objects.isNull(user))
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);

        return new UserResponse(user.getId(), user.getUsername(), user.getNickname());
    }

    public List<UserResponse> userList() {
        return userMapper.selectList(null)
                .stream()
                .map(user -> new UserResponse(user.getId(), user.getUsername(), user.getNickname()))
                .toList();
    }
}
