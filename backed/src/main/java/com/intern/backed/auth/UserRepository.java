package com.intern.backed.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {
    private static final RowMapper<User> MAPPER = (rs, i) -> new User(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("department"));
    private final JdbcTemplate jdbc;
    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByUsername(String username) {
        return jdbc.query("SELECT * FROM users WHERE username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public void insert(String username, String passwordHash, String role, String department) {
        jdbc.update("INSERT INTO users(username, password_hash, role, department) VALUES (?, ?, ?, ?)",
                username, passwordHash, role, department);
    }
}