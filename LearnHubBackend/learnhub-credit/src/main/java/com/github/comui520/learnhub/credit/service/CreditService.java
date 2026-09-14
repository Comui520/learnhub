package com.github.comui520.learnhub.credit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.github.comui520.learnhub.common.exception.BusinessException;
import com.github.comui520.learnhub.credit.CreditErrorCode;
import com.github.comui520.learnhub.credit.dto.CreditOrderResponse;
import com.github.comui520.learnhub.credit.entity.CreditAccount;
import com.github.comui520.learnhub.credit.entity.CreditOrder;
import com.github.comui520.learnhub.credit.entity.CreditTransaction;
import com.github.comui520.learnhub.credit.mapper.CreditAccountMapper;
import com.github.comui520.learnhub.credit.mapper.CreditTransactionMapper;
import com.github.comui520.learnhub.credit.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class CreditService extends ServiceImpl<CreditAccountMapper, CreditAccount> {

    private final CreditAccountMapper accountMapper;
    private final CreditTransactionMapper transactionMapper;
    private final OrderMapper orderMapper;

    public CreditService(
            CreditAccountMapper accountMapper,
            OrderMapper orderMapper,
            CreditTransactionMapper transactionMapper
    ) {
        this.accountMapper = accountMapper;
        this.orderMapper = orderMapper;
        this.transactionMapper = transactionMapper;
    }

    // 获取余额
    public BigDecimal getBalance(Long userId) {
        CreditAccount account = this.getOne(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId)
        );
        return account == null ? BigDecimal.ZERO : account.getBalance();
    }

    @Transactional
    public void grant(Long userId, BigDecimal amount, String bizNo) {
        // 幂等：同一个 bizNo 只送一次（uk_biz_no 兜底）
        if (transactionMapper.selectCount(
                new LambdaQueryWrapper<CreditTransaction>()
                        .eq(CreditTransaction::getBizNo, bizNo)) > 0) {
            return;
        }
        CreditAccount account = ensureAccount(userId);
        account.setBalance(account.getBalance().add(amount));
        accountMapper.updateById(account);
        recordTransaction(userId, amount, account.getBalance(), "GRANT", bizNo);
    }

    @Transactional
    public boolean consume(Long userId, BigDecimal amount, String bizNo) {
        // 幂等：同 bizNo 已扣过 → 直接返回成功（回调/重试场景）
        if (transactionMapper.selectCount(
                new LambdaQueryWrapper<CreditTransaction>()
                        .eq(CreditTransaction::getBizNo, bizNo)) > 0) {
            return true;
        }

        // 原子扣减：余额够才减，返回影响行数
        int rows = accountMapper.deductBalance(userId, amount);
        if (rows == 0) {
            return false;   // 余额不足
        }

        CreditAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId));
        recordTransaction(userId, amount.negate(), account.getBalance(), "CONSUME", bizNo);
        return true;
    }
    @Transactional
    public CreditOrderResponse createOrder(Long userId, BigDecimal amount) {
        CreditOrder order = new CreditOrder();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setAmount(amount);
        order.setStatus("CREATED");
        order.setExpireAt(LocalDateTime.now().plusMinutes(15));   // 15 分钟未支付关闭
        order.setCreatedAt(LocalDateTime.now());
        orderMapper.insert(order);
        return toResponse(order);
    }

    @Transactional
    public void handlePaymentNotify(String orderNo, BigDecimal amount, String tradNo) {
        CreditOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<CreditOrder>()
                        .eq(CreditOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(CreditErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getAmount().compareTo(amount) != 0) {
            throw new BusinessException(CreditErrorCode.PAY_AMOUNT_MISMATCH);  // 金额不符拒绝
        }

        // ★ 幂等关键：状态机条件更新，只有 CREATED → PAID 的第一次能成功
        int rows = orderMapper.updateStatusByOrderNo(orderNo, "PAID", "CREATED");
        if (rows == 0) {
            // 已经 PAID 或 CLOSED——重复回调，直接当作成功返回，不入账
            log.info("duplicate notify ignored: orderNo={}", orderNo);
            return;
        }

        orderMapper.markPaid(orderNo, LocalDateTime.now());

        // 只有第一次走到这里：给用户加额度（bizNo = orderNo，流水唯一索引兜底）
        grant(order.getUserId(), order.getAmount(), order.getOrderNo());
    }

    private void recordTransaction(Long userId, BigDecimal amount, BigDecimal balance, String type, String bizNo) {
        transactionMapper.insert(
                new CreditTransaction(null, userId, amount, balance, type, bizNo, LocalDateTime.now())
        );
    }

    private CreditAccount ensureAccount(Long userId) {
        // 依赖 uk_user_id，保证并发首次使用时只创建一条账户记录。
        accountMapper.insertIfAbsent(userId);
        return accountMapper.selectOne(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId));
    }

    private CreditOrderResponse toResponse(CreditOrder order) {
        return new CreditOrderResponse(
                order.getOrderNo(),
                order.getUserId(),
                order.getAmount(),
                order.getStatus(),
                order.getPaidAt(),
                order.getExpireAt(),
                order.getCreatedAt()
        );
    }
}
