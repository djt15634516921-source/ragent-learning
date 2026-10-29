package com.dujiteng.ragentlearning.system.user;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 用户表只保存密码哈希；这个对象不直接作为 HTTP 响应返回。 */
@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> users = jdbcTemplate.query("""
                SELECT id, username, password_hash FROM app_user WHERE username = ?
                """, (rs, rowNum) -> new UserAccount(rs.getLong("id"),
                rs.getString("username"), rs.getString("password_hash")), username);
        return users.stream().findFirst();
    }

    public long create(String username, String passwordHash) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO app_user (username, password_hash) VALUES (?, ?) RETURNING id
                """, Long.class, username, passwordHash);
        if (id == null) throw new IllegalStateException("用户创建后没有返回编号");
        return id;
    }

    public record UserAccount(long id, String username, String passwordHash) { }
}
