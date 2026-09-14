package com.github.comui520.learnhub.user.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@TableName("`user`")
@Getter
@Setter
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String nickname;

    private String passwordHash;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}