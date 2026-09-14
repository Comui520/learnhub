package com.github.comui520.learnhub.credit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("`credit_transaction`")
public class CreditTransaction {
    @TableId(type = IdType.AUTO)
    Long id;

    Long userId;

    BigDecimal changeAmount = BigDecimal.ZERO;

    BigDecimal balanceAfter = BigDecimal.ZERO;

    String type;

    String bizNo;

    LocalDateTime createdAt;
}
