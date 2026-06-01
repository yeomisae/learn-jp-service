package com.blue.learnjp.service;

import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizTurnRequest;
import com.blue.learnjp.dto.QuizTurnResponse;
import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.repository.GraphRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QuizService {

    private static final String RANDOM_JLPT = "random_jlpt";
    private static final List<String> DEFAULT_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private static final int DEFAULT_COUNT = 5;
    private static final int MAX_COUNT = 10;
    private static final boolean ALLOW_DROP_CANDIDATES = true;
    private static final int MAX_CANDIDATE_WORDS_TO_USE = 1;
    private static final int MAX_EXTRA_CONTENT_WORDS = 2;

    private final GraphRepository graphRepository;
    private final NaverJakoDictionaryService jakoService;

    public QuizService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService) {
        this.graphRepository = graphRepository;
        this.jakoService = jakoService;
    }

    public QuizWordSetResponse createWordSet(QuizWordSetRequest request) {
        NormalizedRequest normalized = normalize(request);

        Map<String, Object> targetRow = graphRepository.findQuizTargetBySources(
            normalized.sources(),
            normalized.excludeLemmas(),
            normalized.requireReading(),
            normalized.requireMeaning(),
            normalized.requireDictEntry()
        ).orElse(null);

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
        List<String> candidateExcludes = withAdditionalExcludes(
            normalized.excludeLemmas(),
            List.of(requiredWord.lemma())
        );

        List<Map<String, Object>> candidateRows = graphRepository.findQuizCandidateWordsByEdge(
            requiredWord.lemma(),
            normalized.sources(),
            candidateExcludes,
            candidateLimit,
            normalized.requireReading(),
            normalized.requireMeaning(),
            normalized.requireDictEntry()
        );

        List<QuizWordSetResponse.QuizWord> candidateWords = candidateRows.stream()
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
                candidateLimit - candidateWords.size(),
                normalized.requireReading(),
                normalized.requireMeaning(),
                normalized.requireDictEntry()
            );
            List<QuizWordSetResponse.QuizWord> mergedCandidates = new ArrayList<>(candidateWords);
            fallbackRows.stream()
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
        QuizWordSetRequest wordSetRequest = toWordSetRequest(request);
        normalize(wordSetRequest);

        QuizBookmarkUpdateResponse bookmark = updateBookmarks(toBookmarkUpdateRequest(request));
        QuizWordSetResponse wordSet = createWordSet(wordSetRequest);
        List<String> targetDisplayLines = buildTargetDisplayLines(bookmark);
        return new QuizTurnResponse(
            bookmark,
            targetDisplayLines,
            "출제단어:\n\n" + String.join("\n", targetDisplayLines),
            "———",
            wordSet,
            "ok"
        );
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
        List<String> targetLemmas,
        Map<String, Integer> appliedDeltas,
        Map<String, Map<String, Object>> currentWords,
        List<String> missingLemmas
    ) {
        if (targetLemmas == null || targetLemmas.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> missing = new LinkedHashSet<>(missingLemmas != null ? missingLemmas : List.of());
        List<QuizBookmarkUpdateResponse.TargetResult> results = new ArrayList<>();
        for (String lemma : targetLemmas) {
            Integer delta = appliedDeltas.get(lemma);
            String result = delta == null ? "unchanged" : delta < 0 ? "wrong" : "correct";
            Map<String, Object> current = currentWords.get(lemma);
            Integer bookmark = current != null ? intValueOrNull(current.get("bookmark")) : null;
            String reading = current != null ? stringValue(current.get("reading")) : "";
            String source = current != null ? stringValue(current.get("source")) : "";
            String meaning = current != null ? stringValue(current.get("meaning")) : "";
            if (missing.contains(lemma)) {
                result = "missing";
            }
            results.add(new QuizBookmarkUpdateResponse.TargetResult(
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
        Map<String, Map<String, Object>> existingWords = resolution.appliedDeltas().isEmpty()
            ? Map.of()
            : graphRepository.findWordsByLemmas(new ArrayList<>(resolution.appliedDeltas().keySet()));

        LinkedHashMap<String, Integer> updatableDeltas = new LinkedHashMap<>();
        List<String> missingLemmas = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : resolution.appliedDeltas().entrySet()) {
            if (existingWords.containsKey(entry.getKey())) {
                updatableDeltas.put(entry.getKey(), entry.getValue());
            } else {
                missingLemmas.add(entry.getKey());
            }
        }

        int updatedCount = graphRepository.adjustWordBookmarks(updatableDeltas);
        Map<String, Map<String, Object>> currentWords = resolution.targetLemmas().isEmpty()
            ? Map.of()
            : graphRepository.findWordsByLemmas(resolution.targetLemmas());
        if (currentWords == null) {
            currentWords = Map.of();
        }
        return new QuizBookmarkUpdateResponse(
            updatedCount,
            Map.copyOf(updatableDeltas),
            resolution.resolvedMappings(),
            resolution.ignoredLemmas(),
            List.copyOf(missingLemmas),
            buildTargetResults(resolution.targetLemmas(), updatableDeltas, currentWords, missingLemmas),
            "ok"
        );
    }

    private QuizWordSetResponse.QuizWord toQuizWord(Map<String, Object> row) {
        return new QuizWordSetResponse.QuizWord(
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

    private QuizWordSetRequest toWordSetRequest(QuizTurnRequest request) {
        if (request == null) {
            return null;
        }
        return new QuizWordSetRequest(
            request.strategy(),
            request.levels(),
            request.count(),
            request.excludeLemmas(),
            request.requireReading(),
            request.requireMeaning(),
            request.requireDictEntry()
        );
    }

    private QuizBookmarkUpdateRequest toBookmarkUpdateRequest(QuizTurnRequest request) {
        if (request == null) {
            return null;
        }
        return new QuizBookmarkUpdateRequest(
            request.targetLemmas(),
            request.wrongLemmas(),
            request.correctLemmas()
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

        return new NormalizedRequest(strategy, sources, count, excludeLemmas, requireReading, requireMeaning, requireDictEntry);
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

        List<String> targetLemmas = normalizeLemmas(request.targetLemmas());
        LinkedHashSet<String> targetSet = new LinkedHashSet<>(targetLemmas);
        LinkedHashMap<String, Integer> deltas = new LinkedHashMap<>();
        LinkedHashMap<String, String> resolvedMappings = new LinkedHashMap<>();
        LinkedHashSet<String> ignoredLemmas = new LinkedHashSet<>();

        applyDelta(deltas, request.wrongLemmas(), -1, targetSet, resolvedMappings, ignoredLemmas);
        applyDelta(deltas, request.correctLemmas(), 1, targetSet, resolvedMappings, ignoredLemmas);
        deltas.entrySet().removeIf(entry -> entry.getValue() == 0);
        return new BookmarkResolution(
            targetLemmas,
            Collections.unmodifiableMap(new LinkedHashMap<>(deltas)),
            Collections.unmodifiableMap(new LinkedHashMap<>(resolvedMappings)),
            List.copyOf(ignoredLemmas)
        );
    }

    private void applyDelta(Map<String, Integer> deltas, List<String> lemmas, int delta,
                            Set<String> targetSet, Map<String, String> resolvedMappings,
                            Set<String> ignoredLemmas) {
        if (lemmas == null || lemmas.isEmpty()) {
            return;
        }

        for (String lemma : lemmas) {
            if (lemma == null || lemma.isBlank()) continue;
            String normalized = lemma.trim();
            String resolved = resolveToTargetLemma(normalized, targetSet);
            if (resolved == null) {
                ignoredLemmas.add(normalized);
                continue;
            }
            if (!resolved.equals(normalized)) {
                resolvedMappings.put(normalized, resolved);
            }
            deltas.merge(resolved, delta, Integer::sum);
        }
    }

    private String resolveToTargetLemma(String lemma, Set<String> targetSet) {
        if (lemma == null || lemma.isBlank()) {
            return null;
        }

        String normalized = lemma.trim();
        if (targetSet.isEmpty()) {
            return normalized;
        }
        if (targetSet.contains(normalized)) {
            return normalized;
        }

        for (String variant : kaoSafeVariants(normalized)) {
            if (targetSet.contains(variant)) {
                return variant;
            }
        }

        JakoLookupResult jako = jakoService.lookup(normalized);
        if (jako.found() && targetSet.contains(jako.resolvedLemma())) {
            return jako.resolvedLemma();
        }

        return null;
    }

    private List<String> kaoSafeVariants(String lemma) {
        try {
            return jakoService.generateVariants(lemma);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<String> normalizeLemmas(List<String> lemmas) {
        if (lemmas == null || lemmas.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String lemma : lemmas) {
            if (lemma == null || lemma.isBlank()) continue;
            normalized.add(lemma.trim());
        }
        return List.copyOf(normalized);
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

    private record NormalizedRequest(
        String strategy,
        List<String> sources,
        int count,
        List<String> excludeLemmas,
        boolean requireReading,
        boolean requireMeaning,
        boolean requireDictEntry
    ) {}

    private record BookmarkResolution(
        List<String> targetLemmas,
        Map<String, Integer> appliedDeltas,
        Map<String, String> resolvedMappings,
        List<String> ignoredLemmas
    ) {}
}
