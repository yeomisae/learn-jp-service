package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizHistoryDailyResponse;
import com.blue.learnjp.dto.QuizHistoryEntry;
import com.blue.learnjp.repository.QuizHistoryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class QuizHistoryService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final QuizHistoryRepository repository;

    public QuizHistoryService(QuizHistoryRepository repository) {
        this.repository = repository;
    }

    public int record(QuizBookmarkUpdateResponse response) {
        if (response == null || response.targetResults() == null || response.targetResults().isEmpty()) {
            return 0;
        }
        return repository.saveTargetResults(
            UUID.randomUUID().toString(),
            Instant.now(),
            response.targetResults()
        );
    }

    public QuizHistoryDailyResponse dailyReport(LocalDate date, ZoneId zone) {
        ZoneId effectiveZone = zone != null ? zone : DEFAULT_ZONE;
        LocalDate effectiveDate = date != null ? date : LocalDate.now(effectiveZone);
        Instant start = effectiveDate.atStartOfDay(effectiveZone).toInstant();
        Instant end = effectiveDate.plusDays(1).atStartOfDay(effectiveZone).toInstant();

        List<QuizHistoryEntry> entries = repository.findByPeriod(start, end);
        List<QuizHistoryEntry> correctWords = entries.stream()
            .filter(entry -> "correct".equals(entry.result()))
            .toList();
        List<QuizHistoryEntry> wrongWords = entries.stream()
            .filter(entry -> "wrong".equals(entry.result()))
            .toList();
        List<String> correctLines = correctWords.stream().map(this::formatLine).toList();
        List<String> wrongLines = wrongWords.stream().map(this::formatLine).toList();

        return new QuizHistoryDailyResponse(
            effectiveDate.toString(),
            effectiveZone.getId(),
            correctWords.size(),
            wrongWords.size(),
            correctWords,
            wrongWords,
            correctLines,
            wrongLines,
            buildReport(correctLines, wrongLines)
        );
    }

    private String buildReport(List<String> correctLines, List<String> wrongLines) {
        return "✅ 맞은 단어\n"
            + linesOrEmpty(correctLines)
            + "\n\n❌ 틀린 단어\n"
            + linesOrEmpty(wrongLines);
    }

    private String linesOrEmpty(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "• 없음";
        }
        return String.join("\n", lines);
    }

    private String formatLine(QuizHistoryEntry entry) {
        StringBuilder text = new StringBuilder("• ").append(entry.lemma());
        if (entry.reading() != null && !entry.reading().isBlank()
            && !entry.reading().equals(entry.lemma())) {
            text.append("(").append(entry.reading()).append(")");
        }
        text.append(": ").append(displaySource(entry.source()));
        String meaning = compactMeaning(entry.meaning());
        if (!meaning.isBlank()) {
            text.append(", ").append(meaning);
        }
        text.append(" (").append(entry.bookmark() != null ? entry.bookmark() : "-").append(")");
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
        return cut > 0 ? compact.substring(0, cut).trim() : compact;
    }
}
