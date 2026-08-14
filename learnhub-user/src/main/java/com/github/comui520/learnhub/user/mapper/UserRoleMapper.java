package com.github.comui520.learnhub.user.mapper;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserRoleMapper {

    @Insert("insert into `user_role` (user_id, role_id) VALUES (#{userId}, #{roleId})")
    Integer insertUserRole(Long userId, Long roleId);


    // 根据用户ID查询权限代码
    @Select("""
            select distinct code from `permission` p
            where p.id in 
                  (
                    select rp.permission_id from `role_permission` rp
                    where rp.role_id in
                        (
                            select role_id from `user_role`
                           where user_id = #{userId}
                        )
                    )
            """)
    List<String> selectPermissionCodesByUserId(Long userId);
}
