package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizHistoryDailyResponse;
import com.blue.learnjp.dto.QuizHistoryEntry;
import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.repository.QuizHistoryRepository;
import com.blue.learnjp.repository.QuizHistoryRepository.HistoryContext;
import com.blue.learnjp.repository.UserRepository;
import com.blue.learnjp.repository.UserRepository.UserRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class QuizHistoryService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final QuizHistoryRepository repository;
    private final GraphRepository graphRepository;
    private final UserRepository userRepository;

    public QuizHistoryService(QuizHistoryRepository repository) {
        this(repository, null, null);
    }

    public QuizHistoryService(QuizHistoryRepository repository, GraphRepository graphRepository) {
        this(repository, graphRepository, null);
    }

    @Autowired
    public QuizHistoryService(QuizHistoryRepository repository, GraphRepository graphRepository,
                              UserRepository userRepository) {
        this.repository = repository;
        this.graphRepository = graphRepository;
        this.userRepository = userRepository;
    }

    public int record(QuizBookmarkUpdateResponse response) {
        return record(response, null);
    }

    public int record(QuizBookmarkUpdateResponse response, HistoryContext context) {
        if (response == null || response.targetResults() == null || response.targetResults().isEmpty()) {
            return 0;
        }
        return repository.saveTargetResults(
            UUID.randomUUID().toString(),
            Instant.now(),
            response.targetResults(),
            context
        );
    }

    public QuizHistoryDailyResponse dailyReport(LocalDate date, ZoneId zone) {
        ZoneId effectiveZone = zone != null ? zone : DEFAULT_ZONE;
        LocalDate effectiveDate = date != null ? date : LocalDate.now(effectiveZone);
        Instant start = effectiveDate.atStartOfDay(effectiveZone).toInstant();
        Instant end = effectiveDate.plusDays(1).atStartOfDay(effectiveZone).toInstant();

        List<QuizHistoryEntry> entries = withUserNames(withCurrentWordReadings(repository.findByPeriod(start, end)));
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
            buildReport(entries)
        );
    }

    private String buildReport(List<QuizHistoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return reportBlock(List.of(), List.of());
        }
        Map<String, List<QuizHistoryEntry>> byUser = new LinkedHashMap<>();
        for (QuizHistoryEntry entry : entries) {
            if (!"correct".equals(entry.result()) && !"wrong".equals(entry.result())) {
                continue;
            }
            byUser.computeIfAbsent(displayUser(entry), ignored -> new ArrayList<>()).add(entry);
        }
        if (byUser.isEmpty()) {
            return reportBlock(List.of(), List.of());
        }
        List<String> sections = new ArrayList<>();
        for (Map.Entry<String, List<QuizHistoryEntry>> entry : byUser.entrySet()) {
            List<String> correctLines = entry.getValue().stream()
                .filter(item -> "correct".equals(item.result()))
                .map(this::formatLine)
                .toList();
            List<String> wrongLines = entry.getValue().stream()
                .filter(item -> "wrong".equals(item.result()))
                .map(this::formatLine)
                .toList();
            sections.add("👤 " + entry.getKey() + "\n" + reportBlock(correctLines, wrongLines));
        }
        return String.join("\n\n", sections);
    }

    private String reportBlock(List<String> correctLines, List<String> wrongLines) {
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

    private List<QuizHistoryEntry> withUserNames(List<QuizHistoryEntry> entries) {
        if (userRepository == null || entries == null || entries.isEmpty()) {
            return entries;
        }
        List<Long> userIds = entries.stream()
            .map(QuizHistoryEntry::userId)
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        if (userIds.isEmpty()) {
            return entries;
        }
        Map<Long, UserRecord> users = userRepository.findByIds(userIds);
        return entries.stream()
            .map(entry -> withUserName(entry, entry.userId() != null ? users.get(entry.userId()) : null))
            .toList();
    }

    private List<QuizHistoryEntry> withCurrentWordReadings(List<QuizHistoryEntry> entries) {
        if (graphRepository == null || entries == null || entries.isEmpty()) {
            return entries;
        }
        List<String> wordIds = entries.stream()
            .map(QuizHistoryEntry::wordId)
            .filter(wordId -> wordId != null && !wordId.isBlank())
            .distinct()
            .toList();
        if (wordIds.isEmpty()) {
            return entries;
        }
        Map<String, Map<String, Object>> currentWords = graphRepository.findWordsByWordIds(wordIds);
        return entries.stream()
            .map(entry -> withCurrentWordReading(entry, currentWords.get(entry.wordId())))
            .toList();
    }

    private QuizHistoryEntry withCurrentWordReading(QuizHistoryEntry entry, Map<String, Object> currentWord) {
        if (currentWord == null) {
            return entry;
        }
        String currentReading = stringValue(currentWord.get("reading"));
        if (currentReading.isBlank() || currentReading.equals(entry.reading())) {
            return entry;
        }
        return new QuizHistoryEntry(
            entry.id(),
            entry.occurredAt(),
            entry.userId(),
            entry.userName(),
            entry.wordId(),
            entry.lemma(),
            currentReading,
            entry.source(),
            entry.meaning(),
            entry.result(),
            entry.bookmark()
        );
    }

    private QuizHistoryEntry withUserName(QuizHistoryEntry entry, UserRecord user) {
        if (user == null || user.name() == null || user.name().isBlank()
            || user.name().equals(entry.userName())) {
            return entry;
        }
        return new QuizHistoryEntry(
            entry.id(),
            entry.occurredAt(),
            entry.userId(),
            user.name(),
            entry.wordId(),
            entry.lemma(),
            entry.reading(),
            entry.source(),
            entry.meaning(),
            entry.result(),
            entry.bookmark()
        );
    }

    private String displayUser(QuizHistoryEntry entry) {
        if (entry.userName() != null && !entry.userName().isBlank()) {
            return entry.userName();
        }
        if (entry.userId() != null) {
            return "user-" + entry.userId();
        }
        return "사용자 미확인";
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

    private String stringValue(Object value) {
        return value != null ? value.toString().trim() : "";
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
