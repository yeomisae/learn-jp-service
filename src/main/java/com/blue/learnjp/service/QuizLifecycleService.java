package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizAnswerResultRequest;
import com.blue.learnjp.dto.QuizAnswerSubmitRequest;
import com.blue.learnjp.dto.QuizAnswerSubmitResponse;
import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizNextProblemRequest;
import com.blue.learnjp.dto.QuizNextProblemResponse;
import com.blue.learnjp.dto.QuizProblemCreateRequest;
import com.blue.learnjp.dto.QuizProblemDraftRequest;
import com.blue.learnjp.dto.QuizProblemDraftResponse;
import com.blue.learnjp.dto.QuizProblemResponse;
import com.blue.learnjp.dto.QuizProblemTarget;
import com.blue.learnjp.dto.QuizScopeInfo;
import com.blue.learnjp.dto.QuizSessionEndRequest;
import com.blue.learnjp.dto.QuizSessionRequest;
import com.blue.learnjp.dto.QuizSessionResponse;
import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.repository.QuizHistoryRepository;
import com.blue.learnjp.repository.QuizSessionRepository;
import com.blue.learnjp.repository.QuizSessionRepository.AnswerCreateRecord;
import com.blue.learnjp.repository.QuizSessionRepository.AnswerResultCreateRecord;
import com.blue.learnjp.repository.QuizSessionRepository.AnswerSummaryRecord;
import com.blue.learnjp.repository.QuizSessionRepository.ProblemCreateRecord;
import com.blue.learnjp.repository.QuizSessionRepository.ProblemRecord;
import com.blue.learnjp.repository.QuizSessionRepository.ScopeRecord;
import com.blue.learnjp.repository.QuizSessionRepository.SessionRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class QuizLifecycleService {

    private static final List<String> INITIAL_LEVELS = List.of("N5", "N4");

    private final QuizSessionRepository repository;
    private final QuizService quizService;
    private final ObjectMapper objectMapper;

    public QuizLifecycleService(QuizSessionRepository repository, QuizService quizService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.quizService = quizService;
        this.objectMapper = objectMapper;
    }

    public QuizSessionResponse startOrUpdateSession(QuizSessionRequest request) {
        ScopeRecord scope = normalizeScope(request != null ? request.scope() : null);
        quizService.resolveRequiredUserId(request != null ? request.discordSenderId() : null);
        SessionRecord existing = repository.findSession(scope.scopeId()).orElse(null);
        List<String> levels = request != null && request.levels() != null && !request.levels().isEmpty()
            ? quizService.normalizeQuizLevels(request.levels())
            : existing != null ? levelsFromString(existing.levels()) : INITIAL_LEVELS;
        SessionRecord session = repository.upsertSession(scope, levelsToString(levels), "ACTIVE");
        return new QuizSessionResponse(
            session.scopeId(),
            levelsFromString(session.levels()),
            session.status(),
            repository.findOpenProblem(session.scopeId()).map(this::toProblemResponse).orElse(null),
            "ok"
        );
    }

    public QuizSessionResponse endSession(QuizSessionEndRequest request) {
        ScopeRecord scope = normalizeScope(request != null ? request.scope() : null);
        quizService.resolveRequiredUserId(request != null ? request.discordSenderId() : null);
        SessionRecord session = repository.endSession(scope.scopeId());
        return new QuizSessionResponse(
            session.scopeId(),
            levelsFromString(session.levels()),
            session.status(),
            null,
            "ok"
        );
    }

    public QuizProblemDraftResponse createDraft(QuizProblemDraftRequest request) {
        ScopeRecord scope = normalizeScope(request != null ? request.scope() : null);
        String senderId = request != null ? request.discordSenderId() : null;
        quizService.resolveRequiredUserId(senderId);
        SessionRecord session = requireActiveSession(scope.scopeId());
        List<String> levels = levelsFromString(session.levels());
        QuizWordSetResponse wordSet = quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt",
            levels,
            request != null ? request.count() : null,
            List.of(),
            true,
            true,
            true,
            senderId
        ));
        return new QuizProblemDraftResponse(scope.scopeId(), levels, wordSet, "ok");
    }

    public QuizProblemResponse createProblem(QuizProblemCreateRequest request) {
        ScopeRecord scope = normalizeScope(request != null ? request.scope() : null);
        Long userId = quizService.resolveRequiredUserId(request != null ? request.discordSenderId() : null);
        SessionRecord session = requireActiveSession(scope.scopeId());
        String sentence = requiredText(request != null ? request.sentence() : null, "sentence");
        String reading = requiredText(request != null ? request.reading() : null, "reading");
        String translation = requiredText(request != null ? request.translation() : null, "translation");
        List<QuizProblemTarget> targets = normalizeTargets(request != null ? request.targets() : null);
        ProblemRecord problem = repository.createProblem(new ProblemCreateRecord(
            UUID.randomUUID().toString(),
            scope.scopeId(),
            session.levels(),
            sentence,
            reading,
            translation,
            writeTargets(targets),
            userId
        ));
        return toProblemResponse(problem);
    }

    public QuizProblemResponse findOpenProblem(String scopeId) {
        String safeScopeId = requiredText(scopeId, "scopeId");
        return repository.findOpenProblem(safeScopeId)
            .map(this::toProblemResponse)
            .orElseThrow(() -> new IllegalArgumentException("Open quiz problem not found"));
    }

    public QuizAnswerSubmitResponse submitAnswer(String problemId, QuizAnswerSubmitRequest request) {
        ProblemRecord problem = repository.findProblem(requiredText(problemId, "problemId"))
            .orElseThrow(() -> new IllegalArgumentException("Quiz problem not found"));
        if (!"OPEN".equals(problem.status())) {
            throw new IllegalArgumentException("Quiz problem is not open");
        }

        String senderId = request != null ? request.discordSenderId() : null;
        long userId = quizService.resolveRequiredUserId(senderId);
        if (repository.answerExists(problem.id(), userId)) {
            throw new IllegalArgumentException("Answer already submitted for this problem");
        }

        List<QuizProblemTarget> targets = readTargets(problem.targetsJson());
        List<String> targetWordIds = targets.stream().map(QuizProblemTarget::wordId).filter(id -> id != null && !id.isBlank()).toList();
        if (targetWordIds.isEmpty()) {
            throw new IllegalArgumentException("Quiz problem has no target wordIds");
        }
        ResultBuckets buckets = resultBuckets(targetWordIds, request != null ? request.results() : null);
        String answerId = UUID.randomUUID().toString();
        QuizBookmarkUpdateResponse bookmark = quizService.updateBookmarks(
            new QuizBookmarkUpdateRequest(targetWordIds, buckets.wrongWordIds(), buckets.correctWordIds(), senderId),
            new QuizHistoryRepository.HistoryContext(userId, problem.scopeId(), problem.id(), answerId)
        );

        repository.saveAnswer(
            new AnswerCreateRecord(
                answerId,
                problem.id(),
                problem.scopeId(),
                userId,
                requiredText(senderId, "discordSenderId"),
                request != null ? request.displayName() : null,
                requiredText(request != null ? request.answerText() : null, "answerText"),
                normalizeOverallResult(request != null ? request.overallResult() : null),
                request != null ? request.feedback() : null
            ),
            toAnswerResultRecords(bookmark)
        );

        return new QuizAnswerSubmitResponse(
            answerId,
            problem.id(),
            problem.scopeId(),
            userId,
            bookmark,
            buildTargetDisplayLines(bookmark),
            "ok"
        );
    }

    public QuizNextProblemResponse nextProblem(QuizNextProblemRequest request) {
        ScopeRecord scope = normalizeScope(request != null ? request.scope() : null);
        quizService.resolveRequiredUserId(request != null ? request.discordSenderId() : null);
        ProblemRecord closed = repository.closeOpenProblem(scope.scopeId())
            .orElseThrow(() -> new IllegalArgumentException("Open quiz problem not found"));
        List<String> summaryLines = buildSummaryLines(repository.findAnswerSummaries(closed.id()));
        QuizProblemDraftResponse nextDraft = createDraft(new QuizProblemDraftRequest(
            request != null ? request.scope() : null,
            request != null ? request.discordSenderId() : null,
            request != null ? request.count() : null
        ));
        return new QuizNextProblemResponse(toProblemResponse(closed), summaryLines, nextDraft, "ok");
    }

    private SessionRecord requireActiveSession(String scopeId) {
        SessionRecord session = repository.findSession(scopeId)
            .orElseThrow(() -> new IllegalArgumentException("Quiz session not found. Run /quiz first."));
        if (!"ACTIVE".equals(session.status())) {
            throw new IllegalArgumentException("Quiz session is not active. Run /quiz first.");
        }
        return session;
    }

    private ScopeRecord normalizeScope(QuizScopeInfo scope) {
        String accountId = defaulted(scope != null ? scope.accountId() : null, "default");
        String provider = defaulted(scope != null ? scope.provider() : null, "discord");
        String chatId = requiredText(scope != null ? scope.chatId() : null, "chatId");
        String scopeId = accountId + ":" + provider + ":" + chatId;
        return new ScopeRecord(
            scopeId,
            accountId,
            provider,
            chatId,
            scope != null ? scope.chatType() : null,
            scope != null ? scope.guildId() : null,
            scope != null ? scope.channelId() : null,
            scope != null ? scope.label() : null
        );
    }

    private List<String> levelsFromString(String levels) {
        if (levels == null || levels.isBlank()) {
            return INITIAL_LEVELS;
        }
        return quizService.normalizeQuizLevels(List.of(levels.split(",")));
    }

    private String levelsToString(List<String> levels) {
        return String.join(",", quizService.normalizeQuizLevels(levels));
    }

    private List<QuizProblemTarget> normalizeTargets(List<QuizProblemTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("targets is required");
        }
        List<QuizProblemTarget> normalized = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (QuizProblemTarget target : targets) {
            if (target == null) {
                continue;
            }
            String wordId = requiredText(target.wordId(), "target.wordId");
            if (!seen.add(wordId)) {
                continue;
            }
            normalized.add(new QuizProblemTarget(
                wordId,
                requiredText(target.lemma(), "target.lemma"),
                trim(target.reading()),
                trim(target.meaning()),
                trim(target.source()),
                defaulted(target.role(), "target")
            ));
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("targets is required");
        }
        return List.copyOf(normalized);
    }

    private ResultBuckets resultBuckets(List<String> targetWordIds, List<QuizAnswerResultRequest> results) {
        LinkedHashSet<String> targetSet = new LinkedHashSet<>(targetWordIds);
        LinkedHashSet<String> wrong = new LinkedHashSet<>();
        LinkedHashSet<String> correct = new LinkedHashSet<>();
        if (results != null) {
            for (QuizAnswerResultRequest result : results) {
                if (result == null || result.wordId() == null || result.wordId().isBlank()) {
                    continue;
                }
                String wordId = result.wordId().trim();
                if (!targetSet.contains(wordId)) {
                    throw new IllegalArgumentException("answer result wordId must be included in problem targets");
                }
                String outcome = normalizeWordResult(result.result());
                if ("wrong".equals(outcome)) {
                    wrong.add(wordId);
                } else if ("correct".equals(outcome)) {
                    correct.add(wordId);
                }
            }
        }
        LinkedHashSet<String> overlap = new LinkedHashSet<>(wrong);
        overlap.retainAll(correct);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("answer result wordIds cannot be both wrong and correct");
        }
        return new ResultBuckets(List.copyOf(wrong), List.copyOf(correct));
    }

    private List<AnswerResultCreateRecord> toAnswerResultRecords(QuizBookmarkUpdateResponse bookmark) {
        if (bookmark == null || bookmark.targetResults() == null) {
            return List.of();
        }
        List<AnswerResultCreateRecord> records = new ArrayList<>();
        for (QuizBookmarkUpdateResponse.TargetResult result : bookmark.targetResults()) {
            int delta = bookmark.appliedDeltas().getOrDefault(result.wordId(), 0);
            records.add(new AnswerResultCreateRecord(
                result.wordId(),
                result.lemma(),
                result.reading(),
                result.source(),
                result.meaning(),
                normalizeWordResult(result.result()),
                delta,
                result.bookmark()
            ));
        }
        return List.copyOf(records);
    }

    private QuizProblemResponse toProblemResponse(ProblemRecord problem) {
        return new QuizProblemResponse(
            problem.id(),
            problem.scopeId(),
            problem.status(),
            levelsFromString(problem.levels()),
            problem.sentence(),
            problem.reading(),
            problem.translation(),
            readTargets(problem.targetsJson()),
            problem.createdAt(),
            problem.closedAt()
        );
    }

    private String writeTargets(List<QuizProblemTarget> targets) {
        try {
            return objectMapper.writeValueAsString(targets);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize targets", e);
        }
    }

    private List<QuizProblemTarget> readTargets(String targetsJson) {
        if (targetsJson == null || targetsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(targetsJson, new TypeReference<List<QuizProblemTarget>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse quiz problem targets", e);
        }
    }

    private List<String> buildTargetDisplayLines(QuizBookmarkUpdateResponse bookmark) {
        if (bookmark == null || bookmark.targetResults() == null || bookmark.targetResults().isEmpty()) {
            return List.of("• 기록 없음");
        }
        return bookmark.targetResults().stream()
            .map(result -> "• " + result.lemma() + readingSuffix(result.reading(), result.lemma())
                + " " + resultMark(result.result()) + " (" + (result.bookmark() != null ? result.bookmark() : "-") + ")")
            .toList();
    }

    private List<String> buildSummaryLines(List<AnswerSummaryRecord> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return List.of("• 제출된 답안 없음");
        }
        Map<String, List<AnswerSummaryRecord>> byUser = new LinkedHashMap<>();
        for (AnswerSummaryRecord summary : summaries) {
            String name = summary.displayName() != null && !summary.displayName().isBlank()
                ? summary.displayName()
                : "user-" + summary.userId();
            byUser.computeIfAbsent(name, ignored -> new ArrayList<>()).add(summary);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<AnswerSummaryRecord>> entry : byUser.entrySet()) {
            List<String> parts = entry.getValue().stream()
                .map(item -> item.lemma() + readingSuffix(item.reading(), item.lemma())
                    + " " + resultMark(item.result())
                    + " (" + (item.bookmarkAfter() != null ? item.bookmarkAfter() : "-") + ")")
                .toList();
            lines.add("• " + entry.getKey() + ": " + String.join(", ", parts));
        }
        return List.copyOf(lines);
    }

    private String normalizeWordResult(String result) {
        String normalized = result != null ? result.trim().toLowerCase(Locale.ROOT) : "unchanged";
        return switch (normalized) {
            case "correct", "wrong", "unchanged" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported answer result: " + result);
        };
    }

    private String normalizeOverallResult(String result) {
        if (result == null || result.isBlank()) {
            return null;
        }
        String normalized = result.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "correct", "wrong", "partial" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported overallResult: " + result);
        };
    }

    private String resultMark(String result) {
        return switch (result != null ? result : "") {
            case "correct" -> "✅";
            case "wrong" -> "❌";
            default -> "➖";
        };
    }

    private String readingSuffix(String reading, String lemma) {
        return reading != null && !reading.isBlank() && !reading.equals(lemma)
            ? "(" + reading + ")"
            : "";
    }

    private String requiredText(String value, String name) {
        String safe = trim(value);
        if (safe.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return safe;
    }

    private String defaulted(String value, String defaultValue) {
        String safe = trim(value);
        return safe.isBlank() ? defaultValue : safe;
    }

    private String trim(String value) {
        return value != null ? value.trim() : "";
    }

    private record ResultBuckets(List<String> wrongWordIds, List<String> correctWordIds) {}
}
