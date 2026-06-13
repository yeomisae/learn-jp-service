package com.blue.learnjp.repository;

import com.blue.learnjp.config.QuizHistoryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class UserRepositoryTests {

    @TempDir
    Path tempDir;

    @Test
    void initializeCreatesUsersTable() throws Exception {
        Path sqlitePath = tempDir.resolve("users.sqlite");
        UserRepository repository = new UserRepository(new QuizHistoryConfig(sqlitePath.toString()));

        repository.initialize();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var statement = connection.createStatement();
             var columns = statement.executeQuery("PRAGMA table_info(users)")) {
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString("name")).isEqualTo("id");
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString("name")).isEqualTo("name");
            assertThat(columns.next()).isTrue();
            assertThat(columns.getString("name")).isEqualTo("discord_sender_id");
        }
    }
}
