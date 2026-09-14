package com.github.comui520.learnhub.credit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.comui520.learnhub.credit.entity.CreditOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface OrderMapper extends BaseMapper<CreditOrder> {
    int updateStatusByOrderNo(@Param("orderNo") String orderNo,
                              @Param("to") String to,
                              @Param("from") String from);

    void markPaid(
            @Param("orderNo") String orderNo,
            @Param("paidAt") LocalDateTime paidAt);

    int closeOrder();
}
