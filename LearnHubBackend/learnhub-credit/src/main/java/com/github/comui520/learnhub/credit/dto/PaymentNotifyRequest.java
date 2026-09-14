package com.github.comui520.learnhub.credit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@Schema(description = "支付回调请求（模拟）")
public record PaymentNotifyRequest(
        @Schema(description = "订单号", example = "202609081234567890")
        @NotBlank(message = "Order number must not be blank")
        String orderNo,

        @Schema(description = "支付金额", example = "10.00")
        @NotNull(message = "Amount must not be null")
        @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
        BigDecimal amount,

        @Schema(description = "第三方交易号", example = "TRADE-001")
        @NotBlank(message = "Trade number must not be blank")
        String tradeNo
) {
}