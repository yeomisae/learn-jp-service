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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

@Repository
public class UserRepository {

    private final Path sqlitePath;
    private final String jdbcUrl;

    public UserRepository(QuizHistoryConfig config) {
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
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        discord_sender_id TEXT UNIQUE,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_users_discord_sender_id
                    ON users (discord_sender_id)
                    """);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to initialize users SQLite table: " + sqlitePath, e);
        }
    }

    public UserRecord upsertByDiscordSenderId(String name, String discordSenderId) {
        String safeName = name != null && !name.isBlank() ? name.trim() : "user";
        String safeSenderId = discordSenderId != null ? discordSenderId.trim() : "";
        if (safeSenderId.isBlank()) {
            throw new IllegalArgumentException("discordSenderId is required");
        }

        Optional<UserRecord> existing = findByDiscordSenderId(safeSenderId);
        String now = Instant.now().toString();
        if (existing.isPresent()) {
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement("""
                     UPDATE users
                     SET name = ?,
                         updated_at = ?
                     WHERE discord_sender_id = ?
                     """)) {
                statement.setString(1, safeName);
                statement.setString(2, now);
                statement.setString(3, safeSenderId);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to update user", e);
            }
            return findByDiscordSenderId(safeSenderId).orElseThrow();
        }

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO users (name, discord_sender_id, created_at, updated_at)
                 VALUES (?, ?, ?, ?)
                 """)) {
            statement.setString(1, safeName);
            statement.setString(2, safeSenderId);
            statement.setString(3, now);
            statement.setString(4, now);
            statement.executeUpdate();
            return findByDiscordSenderId(safeSenderId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create user", e);
        }
    }

    public Optional<UserRecord> findByDiscordSenderId(String discordSenderId) {
        String safeSenderId = discordSenderId != null ? discordSenderId.trim() : "";
        if (safeSenderId.isBlank()) {
            return Optional.empty();
        }

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT id, name, discord_sender_id
                 FROM users
                 WHERE discord_sender_id = ?
                 """)) {
            statement.setString(1, safeSenderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new UserRecord(
                    resultSet.getLong("id"),
                    resultSet.getString("name"),
                    resultSet.getString("discord_sender_id")
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find user by discord sender id", e);
        }
    }

    public Map<Long, UserRecord> findByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(",");
        List<Long> ids = userIds.stream()
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        for (int i = 0; i < ids.size(); i++) {
            placeholders.add("?");
        }

        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT id, name, discord_sender_id
                 FROM users
                 WHERE id IN (%s)
                 """.formatted(placeholders))) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setLong(i + 1, ids.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<Long, UserRecord> users = new HashMap<>();
                while (resultSet.next()) {
                    UserRecord user = new UserRecord(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("discord_sender_id")
                    );
                    users.put(user.id(), user);
                }
                return Map.copyOf(users);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find users by ids", e);
        }
    }

    public record UserRecord(
        long id,
        String name,
        String discordSenderId
    ) {}

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
