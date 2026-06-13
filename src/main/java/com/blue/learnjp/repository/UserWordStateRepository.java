package com.blue.learnjp.repository;

import com.blue.learnjp.config.QuizHistoryConfig;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class UserWordStateRepository {

    public static final int INITIAL_BOOKMARK = -3;
    public static final long DEFAULT_USER_ID = 1L;

    private final Path sqlitePath;
    private final String jdbcUrl;

    public UserWordStateRepository(QuizHistoryConfig config) {
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
                    CREATE TABLE IF NOT EXISTS user_word_state (
                        user_id INTEGER NOT NULL DEFAULT 1,
                        word_id TEXT NOT NULL,
                        bookmark INTEGER NOT NULL DEFAULT -3,
                        image TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY (user_id, word_id)
                    )
                    """);
                migrateLegacyUserWordStateIfNeeded(connection);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_user_word_state_bookmark
                    ON user_word_state (user_id, bookmark)
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_user_word_state_word_id
                    ON user_word_state (word_id)
                    """);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to initialize user word state SQLite database: " + sqlitePath, e);
        }
    }

    private void migrateLegacyUserWordStateIfNeeded(Connection connection) throws SQLException {
        if (hasColumn(connection, "user_word_state", "user_id")) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("BEGIN IMMEDIATE");
            statement.executeUpdate("""
                CREATE TABLE user_word_state_new (
                    user_id INTEGER NOT NULL DEFAULT 1,
                    word_id TEXT NOT NULL,
                    bookmark INTEGER NOT NULL DEFAULT -3,
                    image TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, word_id)
                )
                """);
            statement.executeUpdate("""
                INSERT OR REPLACE INTO user_word_state_new (
                    user_id, word_id, bookmark, image, created_at, updated_at
                )
                SELECT 1, word_id, bookmark, image, created_at, updated_at
                FROM user_word_state
                """);
            statement.executeUpdate("DROP TABLE user_word_state");
            statement.executeUpdate("ALTER TABLE user_word_state_new RENAME TO user_word_state");
            statement.executeUpdate("COMMIT");
        } catch (SQLException e) {
            try (Statement rollback = connection.createStatement()) {
                rollback.executeUpdate("ROLLBACK");
            } catch (SQLException ignored) {
                // Preserve original migration failure.
            }
            throw e;
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

    public Map<String, Integer> findBookmarks(List<String> wordIds) {
        return findBookmarks(DEFAULT_USER_ID, wordIds);
    }

    public Map<String, Integer> findBookmarks(long userId, List<String> wordIds) {
        if (wordIds == null || wordIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", wordIds.stream().map(id -> "?").toList());
        String sql = "SELECT word_id, bookmark FROM user_word_state WHERE user_id = ? AND word_id IN (" + placeholders + ")";
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            for (int i = 0; i < wordIds.size(); i++) {
                statement.setString(i + 2, wordIds.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, Integer> bookmarks = new LinkedHashMap<>();
                while (resultSet.next()) {
                    bookmarks.put(resultSet.getString("word_id"), resultSet.getInt("bookmark"));
                }
                return Map.copyOf(bookmarks);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read user word bookmarks", e);
        }
    }

    public int adjustBookmarks(Map<String, Integer> bookmarkDeltas) {
        return adjustBookmarks(DEFAULT_USER_ID, bookmarkDeltas);
    }

    public int adjustBookmarks(long userId, Map<String, Integer> bookmarkDeltas) {
        if (bookmarkDeltas == null || bookmarkDeltas.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement insert = connection.prepareStatement("""
                 INSERT OR IGNORE INTO user_word_state (user_id, word_id, bookmark, image, created_at, updated_at)
                 VALUES (?, ?, ?, NULL, ?, ?)
                 """);
             PreparedStatement update = connection.prepareStatement("""
                 UPDATE user_word_state
                 SET bookmark = bookmark + ?,
                     updated_at = ?
                 WHERE user_id = ? AND word_id = ?
                 """)) {
            connection.setAutoCommit(false);
            int updated = 0;
            for (Map.Entry<String, Integer> entry : bookmarkDeltas.entrySet()) {
                insert.setLong(1, userId);
                insert.setString(2, entry.getKey());
                insert.setInt(3, INITIAL_BOOKMARK);
                insert.setString(4, now);
                insert.setString(5, now);
                insert.addBatch();

                update.setInt(1, entry.getValue());
                update.setString(2, now);
                update.setLong(3, userId);
                update.setString(4, entry.getKey());
                update.addBatch();
                updated++;
            }
            insert.executeBatch();
            update.executeBatch();
            connection.commit();
            return updated;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to adjust user word bookmarks", e);
        }
    }

    public int upsertStates(Collection<Map<String, Object>> states) {
        return upsertStates(DEFAULT_USER_ID, states);
    }

    public int upsertStates(long userId, Collection<Map<String, Object>> states) {
        if (states == null || states.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO user_word_state (user_id, word_id, bookmark, image, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(user_id, word_id) DO UPDATE SET
                     bookmark = excluded.bookmark,
                     image = excluded.image,
                     updated_at = excluded.updated_at
                 """)) {
            connection.setAutoCommit(false);
            int saved = 0;
            for (Map<String, Object> state : states) {
                String wordId = stringValue(state.get("wordId"));
                if (wordId.isBlank()) {
                    continue;
                }
                statement.setLong(1, userId);
                statement.setString(2, wordId);
                statement.setInt(3, intValue(state.get("bookmark"), INITIAL_BOOKMARK));
                statement.setString(4, blankToNull(stringValue(state.get("image"))));
                statement.setString(5, now);
                statement.setString(6, now);
                statement.addBatch();
                saved++;
            }
            statement.executeBatch();
            connection.commit();
            return saved;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert user word states", e);
        }
    }

    public int initializeWordStates(long userId, Collection<String> wordIds) {
        if (wordIds == null || wordIds.isEmpty()) {
            return 0;
        }
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT OR IGNORE INTO user_word_state (user_id, word_id, bookmark, image, created_at, updated_at)
                 VALUES (?, ?, ?, NULL, ?, ?)
                 """)) {
            connection.setAutoCommit(false);
            int inserted = 0;
            for (String wordId : wordIds) {
                String safeWordId = wordId != null ? wordId.trim() : "";
                if (safeWordId.isBlank()) {
                    continue;
                }
                statement.setLong(1, userId);
                statement.setString(2, safeWordId);
                statement.setInt(3, INITIAL_BOOKMARK);
                statement.setString(4, now);
                statement.setString(5, now);
                statement.addBatch();
                inserted++;
            }
            int[] results = statement.executeBatch();
            connection.commit();
            int changed = 0;
            for (int result : results) {
                if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                    changed++;
                }
            }
            return changed;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize user word states", e);
        }
    }

    public int bookmarkFor(String wordId, Map<String, Integer> bookmarks) {
        if (wordId == null || wordId.isBlank() || bookmarks == null) {
            return INITIAL_BOOKMARK;
        }
        return bookmarks.getOrDefault(wordId, INITIAL_BOOKMARK);
    }

    private String stringValue(Object value) {
        return value != null ? value.toString().trim() : "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.trim());
        }
        return defaultValue;
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
}
