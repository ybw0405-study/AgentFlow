package com.agentflow.workflow.auth;

import java.sql.Timestamp;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefreshTokenMapper {

    @Insert("INSERT INTO refresh_token (id, user_id, token_hash, family_id, status, expires_at)"
            + " VALUES (#{id}, #{userId}, #{tokenHash}, #{familyId}, 'ACTIVE', #{expiresAt})")
    int insert(@Param("id") String id, @Param("userId") String userId,
               @Param("tokenHash") String tokenHash, @Param("familyId") String familyId,
               @Param("expiresAt") Timestamp expiresAt);

    @Select("SELECT id, user_id AS userId, family_id AS familyId, status, expires_at AS expiresAt"
            + " FROM refresh_token WHERE token_hash = #{tokenHash}")
    RefreshTokenRow findByHash(@Param("tokenHash") String tokenHash);

    /** 原子消费：仅 ACTIVE 可转 USED；影响行数必须恰为 1，否则视为重用。 */
    @Update("UPDATE refresh_token SET status = 'USED'"
            + " WHERE id = #{id} AND status = 'ACTIVE'")
    int consumeActive(@Param("id") String id);

    @Update("UPDATE refresh_token SET status = 'REVOKED'"
            + " WHERE family_id = #{familyId} AND status <> 'REVOKED'")
    int revokeFamily(@Param("familyId") String familyId);

    @Update("UPDATE refresh_token SET status = 'REVOKED'"
            + " WHERE user_id = #{userId} AND status = 'ACTIVE'")
    int revokeAllForUser(@Param("userId") String userId);

    record RefreshTokenRow(String id, String userId, String familyId, String status,
                           Timestamp expiresAt) {
    }
}
