package com.agentflow.workflow.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT id, email, password_hash AS passwordHash, display_name AS displayName,"
            + " platform_role AS platformRole, status FROM app_user"
            + " WHERE email = #{email} AND deleted_at IS NULL")
    UserRow findByEmail(@Param("email") String email);

    @Select("SELECT id, email, password_hash AS passwordHash, display_name AS displayName,"
            + " platform_role AS platformRole, status FROM app_user"
            + " WHERE id = #{id} AND deleted_at IS NULL")
    UserRow findById(@Param("id") String id);

    @Insert("INSERT INTO app_user (id, email, password_hash, display_name, platform_role)"
            + " VALUES (#{id}, #{email}, #{passwordHash}, #{displayName}, #{platformRole})")
    int insert(UserRow row);

    record UserRow(String id, String email, String passwordHash, String displayName,
                   String platformRole, String status) {
    }
}
