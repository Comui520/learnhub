package com.github.comui520.learnhub.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@TableName("`audit_log`")
public class AuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String action;

    private String ip;

    private String detail;

    private LocalDateTime createdAt;
}
