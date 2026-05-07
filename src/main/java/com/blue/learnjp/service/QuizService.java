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
    private static final int DEFAULT_COUNT = 3;
    private static final int MAX_COUNT = 50;

    private final GraphRepository graphRepository;
    private final NaverJakoDictionaryService jakoService;

    public QuizService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService) {
        this.graphRepository = graphRepository;
        this.jakoService = jakoService;
    }

    public QuizWordSetResponse createWordSet(QuizWordSetRequest request) {
        NormalizedRequest normalized = normalize(request);

        List<QuizWordSetResponse.QuizWord> words = graphRepository.findQuizWordsBySources(
                normalized.sources(),
                normalized.excludeLemmas(),
                normalized.count(),
                normalized.requireReading(),
                normalized.requireMeaning(),
                normalized.requireDictEntry()
            ).stream()
            .map(this::toQuizWord)
            .toList();

        return new QuizWordSetResponse(
            normalized.strategy(),
            normalized.count(),
            words.size(),
            words
        );
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
        return new QuizBookmarkUpdateResponse(
            updatedCount,
            Map.copyOf(updatableDeltas),
            resolution.resolvedMappings(),
            resolution.ignoredLemmas(),
            List.copyOf(missingLemmas),
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
            return new BookmarkResolution(Map.of(), Map.of(), List.of());
        }

        LinkedHashSet<String> targetSet = new LinkedHashSet<>(normalizeLemmas(request.targetLemmas()));
        LinkedHashMap<String, Integer> deltas = new LinkedHashMap<>();
        LinkedHashMap<String, String> resolvedMappings = new LinkedHashMap<>();
        LinkedHashSet<String> ignoredLemmas = new LinkedHashSet<>();

        applyDelta(deltas, request.wrongLemmas(), -1, targetSet, resolvedMappings, ignoredLemmas);
        applyDelta(deltas, request.correctLemmas(), 1, targetSet, resolvedMappings, ignoredLemmas);
        deltas.entrySet().removeIf(entry -> entry.getValue() == 0);
        return new BookmarkResolution(
            Map.copyOf(deltas),
            Map.copyOf(resolvedMappings),
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
        Map<String, Integer> appliedDeltas,
        Map<String, String> resolvedMappings,
        List<String> ignoredLemmas
    ) {}
}
