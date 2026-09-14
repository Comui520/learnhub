package com.github.comui520.learnhub.credit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("`credit_account`")
public class CreditAccount {
    @TableId(type = IdType.AUTO)
    Long id;

    Long userId;

    BigDecimal balance = BigDecimal.ZERO;

    LocalDateTime updatedAt;
}
