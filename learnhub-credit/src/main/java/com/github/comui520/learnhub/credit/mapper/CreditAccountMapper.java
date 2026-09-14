package com.github.comui520.learnhub.credit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.comui520.learnhub.credit.entity.CreditAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface CreditAccountMapper extends BaseMapper<CreditAccount> {
    int deductBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
