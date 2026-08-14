package com.github.comui520.learnhub.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleMapper {

    @Select("select * from `role` where code = #{code}")
    Long selectIdByCode(String code);
}
