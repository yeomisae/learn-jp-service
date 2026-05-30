package com.blue.learnjp.service;

import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
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
            if (missing.contains(lemma)) {
                result = "missing";
            }
            results.add(new QuizBookmarkUpdateResponse.TargetResult(lemma, result, bookmark));
        }
        return List.copyOf(results);
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

    private QuizWordSetResponse.QuizWord selectRequiredWord(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        Map<String, Object> best = rows.getFirst();
        int bestScore = anchorScore(best);
        for (int i = 1; i < rows.size(); i++) {
            Map<String, Object> candidate = rows.get(i);
            int candidateScore = anchorScore(candidate);
            if (candidateScore > bestScore) {
                best = candidate;
                bestScore = candidateScore;
            }
        }
        return toQuizWord(best);
    }

    private int anchorScore(Map<String, Object> row) {
        String lemma = stringValue(row.get("lemma"));
        String combined = (stringValue(row.get("pos")) + " " + stringValue(row.get("posDesc")) + " "
            + stringValue(row.get("posDetail"))).toLowerCase();

        if (lemma.contains("～") || lemma.matches(".*\\d.*")) {
            return 0;
        }
        if (combined.contains("명사") || combined.contains("동사") || combined.contains("형용사")
            || combined.contains("형용동사") || combined.contains("な형용사")) {
            return 5;
        }
        if (combined.contains("부사")) {
            return 3;
        }
        if (combined.contains("대명사")) {
            return 2;
        }
        if (combined.contains("접속사")) {
            return 1;
        }
        return 0;
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
