package com.github.comui520.learnhub.credit.controller;

import com.github.comui520.learnhub.common.api.ApiResponse;
import com.github.comui520.learnhub.credit.CreditErrorCode;
import com.github.comui520.learnhub.credit.dto.CreateCreditOrderRequest;
import com.github.comui520.learnhub.credit.dto.CreditOrderResponse;
import com.github.comui520.learnhub.credit.dto.PaymentNotifyRequest;
import com.github.comui520.learnhub.credit.service.CreditService;
import com.github.comui520.learnhub.user.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@Tag(name = "Credits", description = "额度与订单")
@RestController
@RequestMapping("/api/v1/credit")
public class CreditController {

    private final CreditService creditService;
    private final CurrentUser currentUser;

    public CreditController(
            CreditService creditService,
            CurrentUser currentUser
    ) {
        this.creditService = creditService;
        this.currentUser = currentUser;
    }

    @GetMapping("/balance")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "查询额度余额", description = "查询当前登录用户的额度余额")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "查询成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）")
    })
    public ApiResponse<BigDecimal> getBalance() {
        return ApiResponse.success(creditService.getBalance(currentUser.currentUserId()));
    }

    @PostMapping("/orders")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "创建额度订单", description = "创建一个模拟支付订单，订单初始状态为 CREATED")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "订单创建成功"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "金额参数错误（COMMON_0400）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "未登录（COMMON_0401）")
    })
    public ApiResponse<CreditOrderResponse> createOrder(
            @Valid @RequestBody CreateCreditOrderRequest request
    ) {
        return ApiResponse.success(
                creditService.createOrder(currentUser.currentUserId(), request.amount())
        );
    }

    @PostMapping("/payments/notify")
    @Operation(summary = "模拟支付回调", description = "模拟支付平台通知；无需 JWT，同一订单重复通知不会重复入账")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "回调处理成功或已幂等处理"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "金额不匹配或参数错误（AI_0004/COMMON_0400）"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "订单不存在（AI_0003）")
    })
    public ApiResponse<Void> paymentNotify(
            @Valid @RequestBody PaymentNotifyRequest request
    ) {
        creditService.handlePaymentNotify(
                request.orderNo(), request.amount(), request.tradeNo()
        );
        return ApiResponse.success();
    }
}