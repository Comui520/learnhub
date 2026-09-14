package com.github.comui520.learnhub.credit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("`credit_order`")
public class CreditOrder {
    @TableId(type = IdType.AUTO)
    Long id;

    String orderNo;

    Long userId;

    BigDecimal amount = BigDecimal.ZERO;

    String status;

    LocalDateTime paidAt;

    LocalDateTime expireAt;

    LocalDateTime createdAt;
    
}
