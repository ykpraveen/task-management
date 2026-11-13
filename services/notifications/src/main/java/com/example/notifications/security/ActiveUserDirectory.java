package com.example.notifications.security;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads authoritative activity from the identity-owned directory contract (read-only). */
@Component
public class ActiveUserDirectory {

    public enum Status { ACTIVE, INACTIVE, UNAVAILABLE }

    private final JdbcTemplate jdbc;

    public ActiveUserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Status status(UUID userId) {
        try {
            List<Boolean> rows = jdbc.queryForList("select active from identity.directory where user_id = ?", Boolean.class, userId);
            return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst()) ? Status.ACTIVE : Status.INACTIVE;
        } catch (DataAccessException e) {
            return Status.UNAVAILABLE;
        }
    }
}
