package com.blue.learnjp.repository;

import com.blue.learnjp.config.QuizHistoryConfig;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizHistoryEntry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class QuizHistoryRepository {

    private final Path sqlitePath;
    private final String jdbcUrl;

    public QuizHistoryRepository(QuizHistoryConfig config) {
        this.sqlitePath = Path.of(config.sqlitePath()).toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + sqlitePath;
    }

    @PostConstruct
    public void initialize() {
        try {
            Path parent = sqlitePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS quiz_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        turn_id TEXT NOT NULL,
                        occurred_at TEXT NOT NULL,
                        word_id TEXT NOT NULL,
                        lemma TEXT NOT NULL,
                        reading TEXT,
                        source TEXT,
                        meaning TEXT,
                        result TEXT NOT NULL CHECK (result IN ('correct', 'wrong', 'unchanged')),
                        bookmark INTEGER
                    )
                    """);
                addColumnIfMissing(connection, "quiz_history", "user_id", "INTEGER");
                addColumnIfMissing(connection, "quiz_history", "scope_id", "TEXT");
                addColumnIfMissing(connection, "quiz_history", "problem_id", "TEXT");
                addColumnIfMissing(connection, "quiz_history", "answer_id", "TEXT");
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_quiz_history_occurred_at
                    ON quiz_history (occurred_at)
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_quiz_history_word_id
                    ON quiz_history (word_id)
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_quiz_history_scope_problem
                    ON quiz_history (scope_id, problem_id)
                    """);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to initialize quiz history SQLite database: " + sqlitePath, e);
        }
    }

    public int saveTargetResults(String turnId, Instant occurredAt,
                                 List<QuizBookmarkUpdateResponse.TargetResult> targetResults) {
        return saveTargetResults(turnId, occurredAt, targetResults, null);
    }

    public int saveTargetResults(String turnId, Instant occurredAt,
                                 List<QuizBookmarkUpdateResponse.TargetResult> targetResults,
                                 HistoryContext context) {
        if (targetResults == null || targetResults.isEmpty()) {
            return 0;
        }

        String sql = """
            INSERT INTO quiz_history (
                turn_id, occurred_at, word_id, lemma, reading, source, meaning, result, bookmark,
                user_id, scope_id, problem_id, answer_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        int saved = 0;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (QuizBookmarkUpdateResponse.TargetResult result : targetResults) {
                if (result == null || result.wordId() == null || result.wordId().isBlank()
                    || result.lemma() == null || result.lemma().isBlank()) {
                    continue;
                }
                String outcome = normalizeOutcome(result.result());
                if (outcome == null) {
                    continue;
                }
                statement.setString(1, turnId);
                statement.setString(2, occurredAt.toString());
                statement.setString(3, result.wordId());
                statement.setString(4, result.lemma());
                statement.setString(5, blankToNull(result.reading()));
                statement.setString(6, blankToNull(result.source()));
                statement.setString(7, blankToNull(result.meaning()));
                statement.setString(8, outcome);
                if (result.bookmark() != null) {
                    statement.setInt(9, result.bookmark());
                } else {
                    statement.setObject(9, null);
                }
                if (context != null && context.userId() != null) {
                    statement.setLong(10, context.userId());
                } else {
                    statement.setObject(10, null);
                }
                statement.setString(11, context != null ? blankToNull(context.scopeId()) : null);
                statement.setString(12, context != null ? blankToNull(context.problemId()) : null);
                statement.setString(13, context != null ? blankToNull(context.answerId()) : null);
                statement.addBatch();
                saved++;
            }
            statement.executeBatch();
            connection.commit();
            return saved;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save quiz history", e);
        }
    }

    public List<QuizHistoryEntry> findByPeriod(Instant startInclusive, Instant endExclusive) {
        String sql = """
            SELECT id, occurred_at, word_id, lemma, reading, source, meaning, result, bookmark
            FROM quiz_history
            WHERE occurred_at >= ? AND occurred_at < ?
            ORDER BY occurred_at ASC, id ASC
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, startInclusive.toString());
            statement.setString(2, endExclusive.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<QuizHistoryEntry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(new QuizHistoryEntry(
                        resultSet.getLong("id"),
                        resultSet.getString("occurred_at"),
                        resultSet.getString("word_id"),
                        resultSet.getString("lemma"),
                        resultSet.getString("reading"),
                        resultSet.getString("source"),
                        resultSet.getString("meaning"),
                        resultSet.getString("result"),
                        intOrNull(resultSet, "bookmark")
                    ));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read quiz history", e);
        }
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private void addColumnIfMissing(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        if (hasColumn(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equals(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private String normalizeOutcome(String outcome) {
        if ("correct".equals(outcome) || "wrong".equals(outcome) || "unchanged".equals(outcome)) {
            return outcome;
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Integer intOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    public record HistoryContext(
        Long userId,
        String scopeId,
        String problemId,
        String answerId
    ) {}
}
