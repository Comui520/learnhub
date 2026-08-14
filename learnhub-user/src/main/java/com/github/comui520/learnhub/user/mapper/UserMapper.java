package com.github.comui520.learnhub.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.comui520.learnhub.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 按用户名查询。这是核心查询，手写 SQL，面试能解释索引命中（uk_username）。 */
    @Select("SELECT * FROM `user` WHERE username = #{username}")
    User selectByUsername(String username);
}
