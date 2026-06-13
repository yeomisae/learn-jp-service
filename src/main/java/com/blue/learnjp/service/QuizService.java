package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizTurnRequest;
import com.blue.learnjp.dto.QuizTurnResponse;
import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.repository.UserRepository;
import com.blue.learnjp.repository.UserWordStateRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class QuizService {

    private static final Logger log = LoggerFactory.getLogger(QuizService.class);

    private static final String RANDOM_JLPT = "random_jlpt";
    private static final List<String> DEFAULT_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_COUNT = 10;
    private static final boolean ALLOW_DROP_CANDIDATES = true;
    private static final int MAX_CANDIDATE_WORDS_TO_USE = 1;
    private static final int MAX_EXTRA_CONTENT_WORDS = 2;
    private static final int TARGET_SAMPLE_LIMIT = 500;
    private static final int MIN_CANDIDATE_SAMPLE_LIMIT = 50;
    private static final int CANDIDATE_SAMPLE_MULTIPLIER = 20;

    private final GraphRepository graphRepository;
    private final NaverJakoDictionaryService jakoService;
    private final QuizHistoryService quizHistoryService;
    private final UserWordStateRepository userWordStateRepository;
    private final UserRepository userRepository;

    @Autowired
    public QuizService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService,
                       QuizHistoryService quizHistoryService, UserWordStateRepository userWordStateRepository,
                       UserRepository userRepository) {
        this.graphRepository = graphRepository;
        this.jakoService = jakoService;
        this.quizHistoryService = quizHistoryService;
        this.userWordStateRepository = userWordStateRepository;
        this.userRepository = userRepository;
    }

    public QuizService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService) {
        this.graphRepository = graphRepository;
        this.jakoService = jakoService;
        this.quizHistoryService = null;
        this.userWordStateRepository = null;
        this.userRepository = null;
    }

    public QuizService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService,
                       UserWordStateRepository userWordStateRepository) {
        this.graphRepository = graphRepository;
        this.jakoService = jakoService;
        this.quizHistoryService = null;
        this.userWordStateRepository = userWordStateRepository;
        this.userRepository = null;
    }

    public QuizWordSetResponse createWordSet(QuizWordSetRequest request) {
        NormalizedRequest normalized = normalize(request);

        List<Map<String, Object>> targetRows = graphRepository.findQuizTargetCandidatesBySources(
            normalized.sources(),
            normalized.excludeLemmas(),
            TARGET_SAMPLE_LIMIT,
            normalized.requireReading(),
            normalized.requireMeaning(),
            normalized.requireDictEntry()
        );
        Map<String, Object> targetRow = selectWeighted(normalized.userId(), targetRows, true);

        if (targetRow == null) {
            return new QuizWordSetResponse(
                normalized.strategy(),
                normalized.count(),
                0,
                null,
                List.of(),
                ALLOW_DROP_CANDIDATES,
                MAX_CANDIDATE_WORDS_TO_USE,
                MAX_EXTRA_CONTENT_WORDS,
                List.of()
            );
        }

        QuizWordSetResponse.QuizWord requiredWord = toQuizWord(targetRow);
        int candidateLimit = Math.max(0, normalized.count() - 1);
        int candidateSampleLimit = candidateSampleLimit(candidateLimit);
        List<String> candidateExcludes = withAdditionalExcludes(
            normalized.excludeLemmas(),
            List.of(requiredWord.lemma())
        );

        List<Map<String, Object>> candidateRows = graphRepository.findQuizCandidateWordsByEdge(
            requiredWord.lemma(),
            normalized.sources(),
            candidateExcludes,
            candidateSampleLimit,
            normalized.requireReading(),
            normalized.requireMeaning(),
            normalized.requireDictEntry()
        );

        List<QuizWordSetResponse.QuizWord> candidateWords = selectWeightedMany(normalized.userId(), candidateRows, candidateLimit, true).stream()
            .map(this::toQuizWord)
            .toList();

        if (candidateWords.size() < candidateLimit) {
            List<String> fallbackExcludes = withAdditionalExcludes(
                candidateExcludes,
                candidateWords.stream().map(QuizWordSetResponse.QuizWord::lemma).toList()
            );
            List<Map<String, Object>> fallbackRows = graphRepository.findQuizWordsBySources(
                normalized.sources(),
                fallbackExcludes,
                candidateSampleLimit(candidateLimit - candidateWords.size()),
                normalized.requireReading(),
                normalized.requireMeaning(),
                normalized.requireDictEntry()
            );
            List<QuizWordSetResponse.QuizWord> mergedCandidates = new ArrayList<>(candidateWords);
            selectWeightedMany(normalized.userId(), fallbackRows, candidateLimit - candidateWords.size(), false).stream()
                .map(this::toQuizWord)
                .filter(word -> !word.lemma().equals(requiredWord.lemma()))
                .forEach(mergedCandidates::add);
            candidateWords = List.copyOf(mergedCandidates);
        }

        List<QuizWordSetResponse.QuizWord> words = new ArrayList<>();
        words.add(requiredWord);
        words.addAll(candidateWords);

        return new QuizWordSetResponse(
            normalized.strategy(),
            normalized.count(),
            words.size(),
            requiredWord,
            candidateWords,
            ALLOW_DROP_CANDIDATES,
            MAX_CANDIDATE_WORDS_TO_USE,
            MAX_EXTRA_CONTENT_WORDS,
            words
        );
    }

    public QuizTurnResponse processTurn(QuizTurnRequest request) {
        QuizWordSetRequest preliminaryWordSetRequest = toWordSetRequest(request, null);
        NormalizedRequest normalized = normalize(preliminaryWordSetRequest);
        log.info(
            "quiz.turn request: levels={}, count={}, targetWordIds={}, wrongWordIds={}, correctWordIds={}, targetIds={}, wrongIds={}, correctIds={}",
            displayLevels(normalized.sources()),
            normalized.count(),
            sizeOf(request != null ? request.targetWordIds() : null),
            sizeOf(request != null ? request.wrongWordIds() : null),
            sizeOf(request != null ? request.correctWordIds() : null),
            displayIds(request != null ? request.targetWordIds() : null),
            displayIds(request != null ? request.wrongWordIds() : null),
            displayIds(request != null ? request.correctWordIds() : null)
        );

        QuizBookmarkUpdateResponse bookmark = updateBookmarks(toBookmarkUpdateRequest(request));
        QuizWordSetRequest wordSetRequest = toWordSetRequest(request, bookmark);

        QuizWordSetResponse wordSet = createWordSet(wordSetRequest);
        List<String> targetDisplayLines = buildTargetDisplayLines(bookmark);
        log.info(
            "quiz.turn completed: updatedCount={}, appliedDeltas={}, targetLemmas={}, nextWords={}, nextWordIds={}",
            bookmark.updatedCount(),
            bookmark.appliedDeltas().size(),
            targetLemmasFrom(bookmark),
            wordSet.returnedCount(),
            displayIds(wordSet.words().stream().map(QuizWordSetResponse.QuizWord::wordId).toList())
        );
        return new QuizTurnResponse(
            bookmark,
            targetDisplayLines,
            "출제단어:\n\n" + String.join("\n", targetDisplayLines),
            "———",
            wordSet,
            "ok"
        );
    }

    private int candidateSampleLimit(int requested) {
        if (requested <= 0) {
            return 0;
        }
        return Math.max(MIN_CANDIDATE_SAMPLE_LIMIT, requested * CANDIDATE_SAMPLE_MULTIPLIER);
    }

    private Map<String, Object> selectWeighted(long userId, List<Map<String, Object>> rows, boolean includeGraphWeight) {
        List<Map<String, Object>> selected = selectWeightedMany(userId, rows, 1, includeGraphWeight);
        return selected.isEmpty() ? null : selected.getFirst();
    }

    private List<Map<String, Object>> selectWeightedMany(long userId, List<Map<String, Object>> rows, int limit,
                                                         boolean includeGraphWeight) {
        if (rows == null || rows.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> remaining = new ArrayList<>(rows);
        List<Map<String, Object>> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < limit) {
            Map<String, Integer> bookmarks = findBookmarks(userId, wordIdsFrom(remaining));
            Map<String, Object> row = drawWeighted(remaining, bookmarks, includeGraphWeight);
            if (row == null) {
                break;
            }
            selected.add(row);
            remaining.remove(row);
        }
        return List.copyOf(selected);
    }

    private Map<String, Object> drawWeighted(List<Map<String, Object>> rows, Map<String, Integer> bookmarks,
                                             boolean includeGraphWeight) {
        double total = 0.0;
        for (Map<String, Object> row : rows) {
            total += rowWeight(row, bookmarks, includeGraphWeight);
        }
        if (total <= 0.0) {
            return rows.get(ThreadLocalRandom.current().nextInt(rows.size()));
        }
        double point = ThreadLocalRandom.current().nextDouble(total);
        double seen = 0.0;
        for (Map<String, Object> row : rows) {
            seen += rowWeight(row, bookmarks, includeGraphWeight);
            if (point < seen) {
                return row;
            }
        }
        return rows.getLast();
    }

    private double rowWeight(Map<String, Object> row, Map<String, Integer> bookmarks, boolean includeGraphWeight) {
        int bookmark = bookmarks.getOrDefault(stringValue(row.get("wordId")), UserWordStateRepository.INITIAL_BOOKMARK);
        double graphWeight = includeGraphWeight ? doubleValue(row.get("graphWeight"), 1.0) : 1.0;
        return Math.max(0.000001, graphWeight * bookmarkWeight(bookmark));
    }

    private double bookmarkWeight(int bookmark) {
        if (bookmark <= -3) return 6.0;
        if (bookmark == -2) return 5.0;
        if (bookmark == -1) return 4.0;
        if (bookmark == 0) return 3.0;
        if (bookmark == 1) return 2.0;
        return 1.0;
    }

    private List<String> wordIdsFrom(List<Map<String, Object>> rows) {
        return rows.stream()
            .map(row -> stringValue(row.get("wordId")))
            .filter(wordId -> !wordId.isBlank())
            .distinct()
            .toList();
    }

    private Map<String, Integer> findBookmarks(List<String> wordIds) {
        return findBookmarks(UserWordStateRepository.DEFAULT_USER_ID, wordIds);
    }

    private Map<String, Integer> findBookmarks(long userId, List<String> wordIds) {
        if (wordIds == null || wordIds.isEmpty() || userWordStateRepository == null) {
            return Map.of();
        }
        return userWordStateRepository.findBookmarks(userId, wordIds);
    }

    private int adjustUserWordBookmarks(Map<String, Integer> updatableDeltas) {
        return adjustUserWordBookmarks(UserWordStateRepository.DEFAULT_USER_ID, updatableDeltas);
    }

    private int adjustUserWordBookmarks(long userId, Map<String, Integer> updatableDeltas) {
        if (userWordStateRepository == null) {
            return updatableDeltas != null ? updatableDeltas.size() : 0;
        }
        return userWordStateRepository.adjustBookmarks(userId, updatableDeltas);
    }

    private List<String> withAdditionalExcludes(List<String> base, List<String> additional) {
        LinkedHashSet<String> excludes = new LinkedHashSet<>();
        if (base != null) {
            excludes.addAll(base);
        }
        if (additional != null) {
            additional.stream()
                .filter(lemma -> lemma != null && !lemma.isBlank())
                .map(String::trim)
                .forEach(excludes::add);
        }
        return List.copyOf(excludes);
    }

    private List<QuizBookmarkUpdateResponse.TargetResult> buildTargetResults(
        List<String> targetWordIds,
        Map<String, Integer> appliedDeltas,
        Map<String, Map<String, Object>> currentWords,
        Map<String, Integer> bookmarks,
        List<String> missingWordIds
    ) {
        if (targetWordIds == null || targetWordIds.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> missing = new LinkedHashSet<>(missingWordIds != null ? missingWordIds : List.of());
        List<QuizBookmarkUpdateResponse.TargetResult> results = new ArrayList<>();
        for (String targetWordId : targetWordIds) {
            Integer delta = appliedDeltas.get(targetWordId);
            String result = delta == null ? "unchanged" : delta < 0 ? "wrong" : "correct";
            Map<String, Object> current = currentWords.get(targetWordId);
            Integer bookmark = current != null ? bookmarks.getOrDefault(targetWordId, UserWordStateRepository.INITIAL_BOOKMARK) : null;
            String wordId = current != null ? stringValue(current.get("wordId")) : "";
            String lemma = current != null ? stringValue(current.get("lemma")) : "";
            String reading = current != null ? stringValue(current.get("reading")) : "";
            String source = current != null ? stringValue(current.get("source")) : "";
            String meaning = current != null ? stringValue(current.get("meaning")) : "";
            if (missing.contains(targetWordId)) {
                result = "missing";
            }
            results.add(new QuizBookmarkUpdateResponse.TargetResult(
                wordId,
                lemma,
                reading,
                source,
                meaning,
                result,
                bookmark
            ));
        }
        return List.copyOf(results);
    }

    private List<String> buildTargetDisplayLines(QuizBookmarkUpdateResponse bookmark) {
        if (bookmark == null || bookmark.targetResults() == null || bookmark.targetResults().isEmpty()) {
            return List.of("• 기록 없음");
        }

        List<String> lines = new ArrayList<>();
        for (QuizBookmarkUpdateResponse.TargetResult result : bookmark.targetResults()) {
            if (result.lemma() == null || result.lemma().isBlank()) {
                continue;
            }
            lines.add("• " + formatTargetWord(result) + " " + resultMark(result.result()) + " ("
                + (result.bookmark() != null ? result.bookmark() : "-") + ")");
        }
        return lines.isEmpty() ? List.of("• 기록 없음") : List.copyOf(lines);
    }

    private String formatTargetWord(QuizBookmarkUpdateResponse.TargetResult result) {
        StringBuilder text = new StringBuilder(result.lemma());
        if (result.reading() != null && !result.reading().isBlank()
            && !result.reading().equals(result.lemma())) {
            text.append("(").append(result.reading()).append(")");
        }
        text.append(": ").append(displaySource(result.source()));
        String meaning = compactMeaning(result.meaning());
        if (!meaning.isBlank()) {
            text.append(", ").append(meaning);
        }
        return text.toString();
    }

    private String displaySource(String source) {
        if (source == null || source.isBlank()) {
            return "-";
        }
        for (String part : source.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("JLPT:")) {
                return trimmed.substring("JLPT:".length());
            }
        }
        return source.split(",")[0].trim();
    }

    private String compactMeaning(String meaning) {
        if (meaning == null || meaning.isBlank()) {
            return "";
        }
        String compact = meaning.trim();
        int comma = compact.indexOf(',');
        int period = compact.indexOf('.');
        int cut = -1;
        if (comma >= 0 && period >= 0) {
            cut = Math.min(comma, period);
        } else if (comma >= 0) {
            cut = comma;
        } else if (period >= 0) {
            cut = period;
        }
        if (cut > 0) {
            compact = compact.substring(0, cut).trim();
        }
        return compact;
    }

    private String resultMark(String result) {
        return switch (result != null ? result : "") {
            case "correct" -> "✅";
            case "wrong" -> "❌";
            case "missing" -> "?";
            default -> "➖";
        };
    }

    public QuizBookmarkUpdateResponse updateBookmarks(QuizBookmarkUpdateRequest request) {
        BookmarkResolution resolution = normalizeBookmarkDeltas(request);
        long userId = resolveUserId(request != null ? request.discordSenderId() : null);
        Map<String, Map<String, Object>> existingWords = resolution.targetWordIds().isEmpty()
            ? Map.of()
            : graphRepository.findWordsByWordIds(resolution.targetWordIds());

        LinkedHashMap<String, Integer> updatableDeltas = new LinkedHashMap<>();
        List<String> missingWordIds = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : resolution.appliedDeltas().entrySet()) {
            if (existingWords.containsKey(entry.getKey())) {
                updatableDeltas.put(entry.getKey(), entry.getValue());
            } else {
                missingWordIds.add(entry.getKey());
            }
        }
        for (String wordId : resolution.targetWordIds()) {
            if (!existingWords.containsKey(wordId)) {
                missingWordIds.add(wordId);
            }
        }
        if (!missingWordIds.isEmpty()) {
            log.warn(
                "quiz.bookmark rejected: unknown targetWordIds={}",
                displayIds(new ArrayList<>(new LinkedHashSet<>(missingWordIds)))
            );
            throw new IllegalArgumentException("Unknown targetWordIds: " + String.join(",", new LinkedHashSet<>(missingWordIds)));
        }

        int updatedCount = adjustUserWordBookmarks(userId, updatableDeltas);
        Map<String, Map<String, Object>> currentWords = resolution.targetWordIds().isEmpty()
            ? Map.of()
            : graphRepository.findWordsByWordIds(resolution.targetWordIds());
        Map<String, Integer> currentBookmarks = findBookmarks(userId, resolution.targetWordIds());
        QuizBookmarkUpdateResponse response = new QuizBookmarkUpdateResponse(
            updatedCount,
            Map.copyOf(updatableDeltas),
            resolution.resolvedMappings(),
            resolution.ignoredLemmas(),
            List.of(),
            buildTargetResults(resolution.targetWordIds(), updatableDeltas, currentWords, currentBookmarks, List.of()),
            "ok"
        );
        log.info(
            "quiz.bookmark updated: targetWordIds={}, wrong={}, correct={}, updatedCount={}, targetLemmas={}",
            resolution.targetWordIds().size(),
            countDeltas(updatableDeltas, -1),
            countDeltas(updatableDeltas, 1),
            response.updatedCount(),
            targetLemmasFrom(response)
        );
        recordHistory(response);
        return response;
    }

    private void recordHistory(QuizBookmarkUpdateResponse response) {
        if (quizHistoryService == null) {
            return;
        }
        int savedCount = quizHistoryService.record(response);
        log.info("quiz.history recorded: savedCount={}", savedCount);
    }

    private QuizWordSetResponse.QuizWord toQuizWord(Map<String, Object> row) {
        return new QuizWordSetResponse.QuizWord(
            stringValue(row.get("wordId")),
            stringValue(row.get("lemma")),
            stringValue(row.get("reading")),
            stringValue(row.get("meaning")),
            stringValue(row.get("pos")),
            stringValue(row.get("posDetail")),
            stringValue(row.get("posDesc")),
            stringValue(row.get("source")),
            intValue(row.get("starGrade")),
            stringValue(row.get("dictEntryId"))
        );
    }

    private QuizWordSetRequest toWordSetRequest(QuizTurnRequest request, QuizBookmarkUpdateResponse bookmark) {
        if (request == null) {
            return null;
        }
        return new QuizWordSetRequest(
            request.strategy(),
            request.levels(),
            request.count(),
            withAdditionalExcludes(request.excludeLemmas(), targetLemmasFrom(bookmark)),
            request.requireReading(),
            request.requireMeaning(),
            request.requireDictEntry(),
            request.discordSenderId()
        );
    }

    private List<String> targetLemmasFrom(QuizBookmarkUpdateResponse bookmark) {
        if (bookmark == null || bookmark.targetResults() == null || bookmark.targetResults().isEmpty()) {
            return List.of();
        }
        return bookmark.targetResults().stream()
            .map(QuizBookmarkUpdateResponse.TargetResult::lemma)
            .filter(lemma -> lemma != null && !lemma.isBlank())
            .toList();
    }

    private QuizBookmarkUpdateRequest toBookmarkUpdateRequest(QuizTurnRequest request) {
        if (request == null) {
            return null;
        }
        return new QuizBookmarkUpdateRequest(
            request.targetWordIds(),
            request.wrongWordIds(),
            request.correctWordIds(),
            request.discordSenderId()
        );
    }

    private NormalizedRequest normalize(QuizWordSetRequest request) {
        String strategy = request != null && request.strategy() != null
            ? request.strategy().trim().toLowerCase()
            : RANDOM_JLPT;

        if (!RANDOM_JLPT.equals(strategy)) {
            throw new IllegalArgumentException("Unsupported strategy: " + strategy);
        }

        int count = request != null && request.count() != null ? request.count() : DEFAULT_COUNT;
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_COUNT);
        }

        List<String> levels = normalizeLevels(request != null ? request.levels() : null);
        List<String> sources = levels.stream().map(level -> "JLPT:" + level).toList();
        List<String> excludeLemmas = normalizeExcludeLemmas(request != null ? request.excludeLemmas() : null);

        boolean requireReading = request == null || request.requireReading() == null || request.requireReading();
        boolean requireMeaning = request == null || request.requireMeaning() == null || request.requireMeaning();
        boolean requireDictEntry = request == null || request.requireDictEntry() == null || request.requireDictEntry();

        long userId = resolveUserId(request != null ? request.discordSenderId() : null);
        return new NormalizedRequest(strategy, sources, count, excludeLemmas, requireReading, requireMeaning, requireDictEntry, userId);
    }

    private long resolveUserId(String discordSenderId) {
        String safeSenderId = discordSenderId != null ? discordSenderId.trim() : "";
        if (safeSenderId.isBlank()) {
            return UserWordStateRepository.DEFAULT_USER_ID;
        }
        if (userRepository == null) {
            return UserWordStateRepository.DEFAULT_USER_ID;
        }
        return userRepository.findByDiscordSenderId(safeSenderId)
            .map(UserRepository.UserRecord::id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown discordSenderId. Run /join first."));
    }

    private List<String> normalizeLevels(List<String> levels) {
        if (levels == null || levels.isEmpty()) {
            return DEFAULT_LEVELS;
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String level : levels) {
            if (level == null || level.isBlank()) continue;
            String upper = level.trim().toUpperCase();
            if (!DEFAULT_LEVELS.contains(upper)) {
                throw new IllegalArgumentException("Unsupported level: " + upper);
            }
            normalized.add(upper);
        }

        if (normalized.isEmpty()) {
            return DEFAULT_LEVELS;
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeExcludeLemmas(List<String> excludeLemmas) {
        if (excludeLemmas == null || excludeLemmas.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String lemma : excludeLemmas) {
            if (lemma == null || lemma.isBlank()) continue;
            normalized.add(lemma.trim());
        }
        return List.copyOf(normalized);
    }

    private BookmarkResolution normalizeBookmarkDeltas(QuizBookmarkUpdateRequest request) {
        if (request == null) {
            return new BookmarkResolution(List.of(), Map.of(), Map.of(), List.of());
        }

        List<String> targetWordIds = normalizeIds(request.targetWordIds());
        if (targetWordIds.isEmpty()) {
            log.warn("quiz.bookmark rejected: targetWordIds is required");
            throw new IllegalArgumentException("targetWordIds is required");
        }
        LinkedHashSet<String> targetSet = new LinkedHashSet<>(targetWordIds);
        LinkedHashMap<String, Integer> deltas = new LinkedHashMap<>();
        LinkedHashSet<String> wrongWordIds = new LinkedHashSet<>(normalizeIds(request.wrongWordIds()));
        LinkedHashSet<String> correctWordIds = new LinkedHashSet<>(normalizeIds(request.correctWordIds()));

        if (!targetSet.containsAll(wrongWordIds)) {
            log.warn(
                "quiz.bookmark rejected: wrongWordIds outside targetWordIds wrongIds={} targetIds={}",
                displayIds(new ArrayList<>(wrongWordIds)),
                displayIds(targetWordIds)
            );
            throw new IllegalArgumentException("wrongWordIds must be included in targetWordIds");
        }
        if (!targetSet.containsAll(correctWordIds)) {
            log.warn(
                "quiz.bookmark rejected: correctWordIds outside targetWordIds correctIds={} targetIds={}",
                displayIds(new ArrayList<>(correctWordIds)),
                displayIds(targetWordIds)
            );
            throw new IllegalArgumentException("correctWordIds must be included in targetWordIds");
        }
        LinkedHashSet<String> overlap = new LinkedHashSet<>(wrongWordIds);
        overlap.retainAll(correctWordIds);
        if (!overlap.isEmpty()) {
            log.warn("quiz.bookmark rejected: overlapping wordIds={}", displayIds(new ArrayList<>(overlap)));
            throw new IllegalArgumentException("wordIds cannot be both wrong and correct: " + String.join(",", overlap));
        }

        wrongWordIds.forEach(wordId -> deltas.put(wordId, -1));
        correctWordIds.forEach(wordId -> deltas.put(wordId, 1));
        return new BookmarkResolution(
            targetWordIds,
            Collections.unmodifiableMap(new LinkedHashMap<>(deltas)),
            Map.of(),
            List.of()
        );
    }

    private List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            normalized.add(id.trim());
        }
        return List.copyOf(normalized);
    }

    private int sizeOf(List<?> values) {
        return values != null ? values.size() : 0;
    }

    private long countDeltas(Map<String, Integer> deltas, int value) {
        return deltas.values().stream().filter(delta -> delta == value).count();
    }

    private List<String> displayLevels(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
            .map(source -> source != null && source.startsWith("JLPT:") ? source.substring("JLPT:".length()) : source)
            .toList();
    }

    private List<String> displayIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> normalized = normalizeIds(ids);
        if (normalized.size() <= 10) {
            return normalized;
        }
        List<String> displayed = new ArrayList<>(normalized.subList(0, 10));
        displayed.add("...+" + (normalized.size() - 10));
        return List.copyOf(displayed);
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return 0;
    }

    private Integer intValueOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Double.parseDouble(text);
        }
        return defaultValue;
    }

    private record NormalizedRequest(
        String strategy,
        List<String> sources,
        int count,
        List<String> excludeLemmas,
        boolean requireReading,
        boolean requireMeaning,
        boolean requireDictEntry,
        long userId
    ) {}

    private record BookmarkResolution(
        List<String> targetWordIds,
        Map<String, Integer> appliedDeltas,
        Map<String, String> resolvedMappings,
        List<String> ignoredLemmas
    ) {}
}
