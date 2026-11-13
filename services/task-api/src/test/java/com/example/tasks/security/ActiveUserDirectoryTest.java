package com.example.tasks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class ActiveUserDirectoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ActiveUserDirectory directory = new ActiveUserDirectory(jdbc);
    private final UUID user = UUID.randomUUID();

    @Test
    void activeOnlyWhenTheDirectorySaysSo() {
        when(jdbc.queryForList(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(List.of(true));
        assertThat(directory.status(user)).isEqualTo(ActiveUserDirectory.Status.ACTIVE);
    }

    @Test
    void inactiveWhenDeactivatedOrUnknown() {
        when(jdbc.queryForList(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(List.of(false));
        assertThat(directory.status(user)).isEqualTo(ActiveUserDirectory.Status.INACTIVE);
        when(jdbc.queryForList(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(List.of());
        assertThat(directory.status(user)).isEqualTo(ActiveUserDirectory.Status.INACTIVE);
    }

    @Test
    void unavailableWhenTheDirectoryCannotBeRead() {
        when(jdbc.queryForList(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(directory.status(user)).isEqualTo(ActiveUserDirectory.Status.UNAVAILABLE);
    }
}
