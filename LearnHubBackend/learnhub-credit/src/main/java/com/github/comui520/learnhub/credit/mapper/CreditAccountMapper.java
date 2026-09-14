package com.github.comui520.learnhub.credit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.comui520.learnhub.credit.entity.CreditAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface CreditAccountMapper extends BaseMapper<CreditAccount> {
    @Insert("INSERT INTO credit_account (user_id, balance) VALUES (#{userId}, 0) ON DUPLICATE KEY UPDATE id = id")
    int insertIfAbsent(@Param("userId") Long userId);

    int deductBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
