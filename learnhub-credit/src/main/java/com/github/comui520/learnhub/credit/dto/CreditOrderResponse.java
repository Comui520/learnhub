package com.github.comui520.learnhub.credit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "订单")
public class CreditOrderResponse {
    String orderNo;

    Long userId;

    BigDecimal amount = BigDecimal.ZERO;

    String status;

    LocalDateTime paidAt;

    LocalDateTime expireAt;

    LocalDateTime createdAt;
}
