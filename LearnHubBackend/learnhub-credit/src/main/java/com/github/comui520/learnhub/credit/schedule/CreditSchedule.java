package com.github.comui520.learnhub.credit.schedule;

import com.github.comui520.learnhub.credit.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class CreditSchedule {
    private final OrderMapper orderMapper;

    public CreditSchedule(
            OrderMapper orderMapper
    ) {
        this.orderMapper = orderMapper;
    }

    @Scheduled(fixedRate = 30000)
    public void closeOrder() {
        int count = orderMapper.closeOrder();
        log.info("Closed {} orders", count);
    }
}
