package com.github.comui520.learnhub.credit;

import com.github.comui520.learnhub.common.exception.ErrorCode;

public enum CreditErrorCode implements ErrorCode {
    RATE_LIMITED("AI_0001", "请求过于频繁，请稍后再试", 429),
    INSUFFICIENT_CREDIT("AI_0002", "余额不足, 请充值", 402),
    ORDER_NOT_FOUND("AI_0003", "订单不存在", 404),
    PAY_AMOUNT_MISMATCH("AI_0004", "支付金额与订单金额不一致", 400);
    private final String code;
    private final String message;
    private final Integer httpStatus;

    CreditErrorCode(String code, String message, Integer httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public Integer httpStatus() {
        return httpStatus;
    }
}
