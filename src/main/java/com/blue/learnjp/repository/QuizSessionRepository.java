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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class QuizSessionRepository {

    private final Path sqlitePath;
    private final String jdbcUrl;

    public QuizSessionRepository(QuizHistoryConfig config) {
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
                    CREATE TABLE IF NOT EXISTS quiz_sessions (
                        scope_id TEXT PRIMARY KEY,
                        account_id TEXT,
                        provider TEXT,
                        chat_id TEXT,
                        chat_type TEXT,
                        guild_id TEXT,
                        channel_id TEXT,
                        label TEXT,
                        levels TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'ENDED')),
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS quiz_problems (
                        id TEXT PRIMARY KEY,
                        scope_id TEXT NOT NULL,
                        status TEXT NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
                        levels TEXT NOT NULL,
                        sentence TEXT NOT NULL,
                        reading TEXT NOT NULL,
                        translation TEXT NOT NULL,
                        targets_json TEXT NOT NULL,
                        created_by_user_id INTEGER,
                        created_at TEXT NOT NULL,
                        closed_at TEXT
                    )
                    """);
                statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_quiz_problems_one_open_per_scope
                    ON quiz_problems (scope_id)
                    WHERE status = 'OPEN'
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_quiz_problems_scope_created
                    ON quiz_problems (scope_id, created_at)
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS quiz_answers (
                        id TEXT PRIMARY KEY,
                        problem_id TEXT NOT NULL,
                        scope_id TEXT NOT NULL,
                        user_id INTEGER NOT NULL,
                        sender_id TEXT NOT NULL,
                        display_name TEXT,
                        answer_text TEXT NOT NULL,
                        overall_result TEXT CHECK (overall_result IN ('correct', 'wrong', 'partial')),
                        feedback TEXT,
                        created_at TEXT NOT NULL
                    )
                    """);
                statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_quiz_answers_one_per_user
                    ON quiz_answers (problem_id, user_id)
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_quiz_answers_scope_problem
                    ON quiz_answers (scope_id, problem_id)
                    """);
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS quiz_answer_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        answer_id TEXT NOT NULL,
                        problem_id TEXT NOT NULL,
                        scope_id TEXT NOT NULL,
                        user_id INTEGER NOT NULL,
                        word_id TEXT NOT NULL,
                        lemma TEXT NOT NULL,
                        reading TEXT,
                        source TEXT,
                        meaning TEXT,
                        result TEXT NOT NULL CHECK (result IN ('correct', 'wrong', 'unchanged')),
                        bookmark_delta INTEGER NOT NULL,
                        bookmark_after INTEGER,
                        created_at TEXT NOT NULL
                    )
                    """);
                statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_quiz_answer_results_answer_word
                    ON quiz_answer_results (answer_id, word_id)
                    """);
                statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_quiz_answer_results_problem
                    ON quiz_answer_results (problem_id, user_id)
                    """);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to initialize quiz session SQLite database: " + sqlitePath, e);
        }
    }

    public SessionRecord upsertSession(ScopeRecord scope, String levels, String status) {
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO quiz_sessions (
                     scope_id, account_id, provider, chat_id, chat_type, guild_id, channel_id, label,
                     levels, status, created_at, updated_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT(scope_id) DO UPDATE SET
                     account_id = excluded.account_id,
                     provider = excluded.provider,
                     chat_id = excluded.chat_id,
                     chat_type = excluded.chat_type,
                     guild_id = excluded.guild_id,
                     channel_id = excluded.channel_id,
                     label = excluded.label,
                     levels = excluded.levels,
                     status = excluded.status,
                     updated_at = excluded.updated_at
                 """)) {
            statement.setString(1, scope.scopeId());
            statement.setString(2, blankToNull(scope.accountId()));
            statement.setString(3, blankToNull(scope.provider()));
            statement.setString(4, blankToNull(scope.chatId()));
            statement.setString(5, blankToNull(scope.chatType()));
            statement.setString(6, blankToNull(scope.guildId()));
            statement.setString(7, blankToNull(scope.channelId()));
            statement.setString(8, blankToNull(scope.label()));
            statement.setString(9, levels);
            statement.setString(10, status);
            statement.setString(11, now);
            statement.setString(12, now);
            statement.executeUpdate();
            return findSession(scope.scopeId()).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert quiz session", e);
        }
    }

    public Optional<SessionRecord> findSession(String scopeId) {
        String sql = """
            SELECT scope_id, account_id, provider, chat_id, chat_type, guild_id, channel_id, label,
                   levels, status, created_at, updated_at
            FROM quiz_sessions
            WHERE scope_id = ?
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toSession(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find quiz session", e);
        }
    }

    public SessionRecord endSession(String scopeId) {
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE quiz_sessions
                 SET status = 'ENDED', updated_at = ?
                 WHERE scope_id = ?
                 """)) {
            statement.setString(1, now);
            statement.setString(2, scopeId);
            statement.executeUpdate();
            closeOpenProblem(scopeId);
            return findSession(scopeId).orElseThrow(() -> new IllegalArgumentException("Quiz session not found"));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to end quiz session", e);
        }
    }

    public ProblemRecord createProblem(ProblemCreateRecord problem) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO quiz_problems (
                     id, scope_id, status, levels, sentence, reading, translation, targets_json,
                     created_by_user_id, created_at, closed_at
                 ) VALUES (?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, NULL)
                 """)) {
            statement.setString(1, problem.id());
            statement.setString(2, problem.scopeId());
            statement.setString(3, problem.levels());
            statement.setString(4, problem.sentence());
            statement.setString(5, problem.reading());
            statement.setString(6, problem.translation());
            statement.setString(7, problem.targetsJson());
            statement.setObject(8, problem.createdByUserId());
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
            return findProblem(problem.id()).orElseThrow();
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("idx_quiz_problems_one_open_per_scope")) {
                throw new IllegalArgumentException("Open quiz problem already exists for scope");
            }
            throw new IllegalStateException("Failed to create quiz problem", e);
        }
    }

    public Optional<ProblemRecord> findProblem(String problemId) {
        String sql = """
            SELECT id, scope_id, status, levels, sentence, reading, translation, targets_json,
                   created_by_user_id, created_at, closed_at
            FROM quiz_problems
            WHERE id = ?
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toProblem(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find quiz problem", e);
        }
    }

    public Optional<ProblemRecord> findOpenProblem(String scopeId) {
        String sql = """
            SELECT id, scope_id, status, levels, sentence, reading, translation, targets_json,
                   created_by_user_id, created_at, closed_at
            FROM quiz_problems
            WHERE scope_id = ? AND status = 'OPEN'
            ORDER BY created_at DESC
            LIMIT 1
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toProblem(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find open quiz problem", e);
        }
    }

    public Optional<ProblemRecord> closeOpenProblem(String scopeId) {
        Optional<ProblemRecord> open = findOpenProblem(scopeId);
        if (open.isEmpty()) {
            return Optional.empty();
        }
        String now = Instant.now().toString();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 UPDATE quiz_problems
                 SET status = 'CLOSED', closed_at = ?
                 WHERE id = ? AND status = 'OPEN'
                 """)) {
            statement.setString(1, now);
            statement.setString(2, open.get().id());
            statement.executeUpdate();
            return findProblem(open.get().id());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close quiz problem", e);
        }
    }

    public boolean answerExists(String problemId, long userId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT 1 FROM quiz_answers WHERE problem_id = ? AND user_id = ? LIMIT 1
                 """)) {
            statement.setString(1, problemId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check quiz answer", e);
        }
    }

    public Optional<AnswerRecord> findAnswer(String answerId) {
        String sql = """
            SELECT id, problem_id, scope_id, user_id, sender_id, display_name, answer_text,
                   overall_result, feedback, created_at
            FROM quiz_answers
            WHERE id = ?
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, answerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toAnswer(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find quiz answer", e);
        }
    }

    public List<PendingAnswerRecord> findPendingAnswers(String problemId) {
        String sql = """
            SELECT a.id, a.user_id, a.display_name, a.answer_text, a.created_at
            FROM quiz_answers a
            WHERE a.problem_id = ?
              AND NOT EXISTS (
                  SELECT 1 FROM quiz_answer_results r WHERE r.answer_id = a.id
              )
            ORDER BY a.created_at ASC
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PendingAnswerRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new PendingAnswerRecord(
                        resultSet.getString("id"),
                        resultSet.getLong("user_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("answer_text"),
                        resultSet.getString("created_at")
                    ));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read pending quiz answers", e);
        }
    }

    public void saveAnswer(AnswerCreateRecord answer, List<AnswerResultCreateRecord> results) {
        try (Connection connection = connect();
             PreparedStatement answerStatement = connection.prepareStatement("""
                 INSERT INTO quiz_answers (
                     id, problem_id, scope_id, user_id, sender_id, display_name,
                     answer_text, overall_result, feedback, created_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """);
             PreparedStatement resultStatement = connection.prepareStatement("""
                 INSERT INTO quiz_answer_results (
                     answer_id, problem_id, scope_id, user_id, word_id, lemma, reading, source, meaning,
                     result, bookmark_delta, bookmark_after, created_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            connection.setAutoCommit(false);
            String now = Instant.now().toString();
            answerStatement.setString(1, answer.id());
            answerStatement.setString(2, answer.problemId());
            answerStatement.setString(3, answer.scopeId());
            answerStatement.setLong(4, answer.userId());
            answerStatement.setString(5, answer.senderId());
            answerStatement.setString(6, blankToNull(answer.displayName()));
            answerStatement.setString(7, answer.answerText());
            answerStatement.setString(8, blankToNull(answer.overallResult()));
            answerStatement.setString(9, blankToNull(answer.feedback()));
            answerStatement.setString(10, now);
            answerStatement.executeUpdate();

            if (results != null) {
                for (AnswerResultCreateRecord result : results) {
                    resultStatement.setString(1, answer.id());
                    resultStatement.setString(2, answer.problemId());
                    resultStatement.setString(3, answer.scopeId());
                    resultStatement.setLong(4, answer.userId());
                    resultStatement.setString(5, result.wordId());
                    resultStatement.setString(6, result.lemma());
                    resultStatement.setString(7, blankToNull(result.reading()));
                    resultStatement.setString(8, blankToNull(result.source()));
                    resultStatement.setString(9, blankToNull(result.meaning()));
                    resultStatement.setString(10, result.result());
                    resultStatement.setInt(11, result.bookmarkDelta());
                    if (result.bookmarkAfter() != null) {
                        resultStatement.setInt(12, result.bookmarkAfter());
                    } else {
                        resultStatement.setObject(12, null);
                    }
                    resultStatement.setString(13, now);
                    resultStatement.addBatch();
                }
                resultStatement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("idx_quiz_answers_one_per_user")) {
                throw new IllegalArgumentException("Answer already submitted for this problem");
            }
            throw new IllegalStateException("Failed to save quiz answer", e);
        }
    }

    public boolean answerHasResults(String answerId) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT 1 FROM quiz_answer_results WHERE answer_id = ? LIMIT 1
                 """)) {
            statement.setString(1, answerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check quiz answer results", e);
        }
    }

    public void saveAnswerGrade(
        AnswerRecord answer,
        String overallResult,
        String feedback,
        List<AnswerResultCreateRecord> results
    ) {
        if (answerHasResults(answer.id())) {
            throw new IllegalArgumentException("Answer already graded");
        }
        try (Connection connection = connect();
             PreparedStatement answerStatement = connection.prepareStatement("""
                 UPDATE quiz_answers
                 SET overall_result = ?, feedback = ?
                 WHERE id = ?
                 """);
             PreparedStatement resultStatement = connection.prepareStatement("""
                 INSERT INTO quiz_answer_results (
                     answer_id, problem_id, scope_id, user_id, word_id, lemma, reading, source, meaning,
                     result, bookmark_delta, bookmark_after, created_at
                 ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                 """)) {
            connection.setAutoCommit(false);
            String now = Instant.now().toString();
            answerStatement.setString(1, blankToNull(overallResult));
            answerStatement.setString(2, blankToNull(feedback));
            answerStatement.setString(3, answer.id());
            answerStatement.executeUpdate();

            if (results != null) {
                for (AnswerResultCreateRecord result : results) {
                    resultStatement.setString(1, answer.id());
                    resultStatement.setString(2, answer.problemId());
                    resultStatement.setString(3, answer.scopeId());
                    resultStatement.setLong(4, answer.userId());
                    resultStatement.setString(5, result.wordId());
                    resultStatement.setString(6, result.lemma());
                    resultStatement.setString(7, blankToNull(result.reading()));
                    resultStatement.setString(8, blankToNull(result.source()));
                    resultStatement.setString(9, blankToNull(result.meaning()));
                    resultStatement.setString(10, result.result());
                    resultStatement.setInt(11, result.bookmarkDelta());
                    if (result.bookmarkAfter() != null) {
                        resultStatement.setInt(12, result.bookmarkAfter());
                    } else {
                        resultStatement.setObject(12, null);
                    }
                    resultStatement.setString(13, now);
                    resultStatement.addBatch();
                }
                resultStatement.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save quiz answer grade", e);
        }
    }

    public List<AnswerSummaryRecord> findAnswerSummaries(String problemId) {
        String sql = """
            SELECT a.user_id, a.display_name, a.feedback, r.word_id, r.lemma, r.reading, r.source, r.meaning,
                   r.result, r.bookmark_delta, r.bookmark_after
            FROM quiz_answer_results r
            JOIN quiz_answers a ON a.id = r.answer_id
            WHERE r.problem_id = ?
            ORDER BY a.created_at ASC, r.id ASC
            """;
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, problemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AnswerSummaryRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(new AnswerSummaryRecord(
                        resultSet.getLong("user_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("feedback"),
                        resultSet.getString("word_id"),
                        resultSet.getString("lemma"),
                        resultSet.getString("reading"),
                        resultSet.getString("source"),
                        resultSet.getString("meaning"),
                        resultSet.getString("result"),
                        resultSet.getInt("bookmark_delta"),
                        intOrNull(resultSet, "bookmark_after")
                    ));
                }
                return List.copyOf(records);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read quiz answer summaries", e);
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

    private SessionRecord toSession(ResultSet resultSet) throws SQLException {
        return new SessionRecord(
            resultSet.getString("scope_id"),
            resultSet.getString("account_id"),
            resultSet.getString("provider"),
            resultSet.getString("chat_id"),
            resultSet.getString("chat_type"),
            resultSet.getString("guild_id"),
            resultSet.getString("channel_id"),
            resultSet.getString("label"),
            resultSet.getString("levels"),
            resultSet.getString("status"),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    private ProblemRecord toProblem(ResultSet resultSet) throws SQLException {
        return new ProblemRecord(
            resultSet.getString("id"),
            resultSet.getString("scope_id"),
            resultSet.getString("status"),
            resultSet.getString("levels"),
            resultSet.getString("sentence"),
            resultSet.getString("reading"),
            resultSet.getString("translation"),
            resultSet.getString("targets_json"),
            longOrNull(resultSet, "created_by_user_id"),
            resultSet.getString("created_at"),
            resultSet.getString("closed_at")
        );
    }

    private AnswerRecord toAnswer(ResultSet resultSet) throws SQLException {
        return new AnswerRecord(
            resultSet.getString("id"),
            resultSet.getString("problem_id"),
            resultSet.getString("scope_id"),
            resultSet.getLong("user_id"),
            resultSet.getString("sender_id"),
            resultSet.getString("display_name"),
            resultSet.getString("answer_text"),
            resultSet.getString("overall_result"),
            resultSet.getString("feedback"),
            resultSet.getString("created_at")
        );
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Long longOrNull(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer intOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    public record ScopeRecord(
        String scopeId,
        String accountId,
        String provider,
        String chatId,
        String chatType,
        String guildId,
        String channelId,
        String label
    ) {}

    public record SessionRecord(
        String scopeId,
        String accountId,
        String provider,
        String chatId,
        String chatType,
        String guildId,
        String channelId,
        String label,
        String levels,
        String status,
        String createdAt,
        String updatedAt
    ) {}

    public record ProblemCreateRecord(
        String id,
        String scopeId,
        String levels,
        String sentence,
        String reading,
        String translation,
        String targetsJson,
        Long createdByUserId
    ) {}

    public record ProblemRecord(
        String id,
        String scopeId,
        String status,
        String levels,
        String sentence,
        String reading,
        String translation,
        String targetsJson,
        Long createdByUserId,
        String createdAt,
        String closedAt
    ) {}

    public record AnswerCreateRecord(
        String id,
        String problemId,
        String scopeId,
        long userId,
        String senderId,
        String displayName,
        String answerText,
        String overallResult,
        String feedback
    ) {}

    public record AnswerRecord(
        String id,
        String problemId,
        String scopeId,
        long userId,
        String senderId,
        String displayName,
        String answerText,
        String overallResult,
        String feedback,
        String createdAt
    ) {}

    public record PendingAnswerRecord(
        String answerId,
        long userId,
        String displayName,
        String answerText,
        String createdAt
    ) {}

    public record AnswerResultCreateRecord(
        String wordId,
        String lemma,
        String reading,
        String source,
        String meaning,
        String result,
        int bookmarkDelta,
        Integer bookmarkAfter
    ) {}

    public record AnswerSummaryRecord(
        long userId,
        String displayName,
        String feedback,
        String wordId,
        String lemma,
        String reading,
        String source,
        String meaning,
        String result,
        int bookmarkDelta,
        Integer bookmarkAfter
    ) {}
}
