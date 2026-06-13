package com.blue.learnjp.repository;

import com.blue.learnjp.config.QuizHistoryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserWordStateRepositoryTests {

    @TempDir
    Path tempDir;

    @Test
    void initializeCreatesUserScopedWordStateTable() throws Exception {
        Path sqlitePath = tempDir.resolve("state.sqlite");
        UserWordStateRepository repository = new UserWordStateRepository(new QuizHistoryConfig(sqlitePath.toString()));

        repository.initialize();
        repository.adjustBookmarks(Map.of("word-1", 1));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT user_id, word_id, bookmark FROM user_word_state")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("user_id")).isEqualTo(UserWordStateRepository.DEFAULT_USER_ID);
                assertThat(row.getString("word_id")).isEqualTo("word-1");
                assertThat(row.getInt("bookmark")).isEqualTo(-2);
            }
            assertThat(repository.findBookmarks(List.of("word-1"))).containsEntry("word-1", -2);
        }
    }

    @Test
    void initializeMigratesLegacyWordScopedStateTable() throws Exception {
        Path sqlitePath = tempDir.resolve("legacy-state.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE user_word_state (
                    word_id TEXT PRIMARY KEY,
                    bookmark INTEGER NOT NULL DEFAULT -3,
                    image TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                INSERT INTO user_word_state (word_id, bookmark, image, created_at, updated_at)
                VALUES ('word-legacy', -5, NULL, 'now', 'now')
                """);
        }

        UserWordStateRepository repository = new UserWordStateRepository(new QuizHistoryConfig(sqlitePath.toString()));

        repository.initialize();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT user_id, word_id, bookmark FROM user_word_state")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getLong("user_id")).isEqualTo(UserWordStateRepository.DEFAULT_USER_ID);
            assertThat(row.getString("word_id")).isEqualTo("word-legacy");
            assertThat(row.getInt("bookmark")).isEqualTo(-5);
        }
    }
}
