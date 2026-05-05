package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.repository.GraphRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class QuizService {

    private static final String RANDOM_JLPT = "random_jlpt";
    private static final List<String> DEFAULT_LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private static final int DEFAULT_COUNT = 3;
    private static final int MAX_COUNT = 50;

    private final GraphRepository graphRepository;

    public QuizService(GraphRepository graphRepository) {
        this.graphRepository = graphRepository;
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
}
