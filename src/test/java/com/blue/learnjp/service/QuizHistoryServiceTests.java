package com.blue.learnjp.service;

import com.blue.learnjp.config.QuizHistoryConfig;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizHistoryDailyResponse;
import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.repository.QuizHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuizHistoryServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void recordsTargetResultsAndBuildsDailyReport() {
        QuizHistoryRepository repository = new QuizHistoryRepository(
            new QuizHistoryConfig(tempDir.resolve("quiz-history.sqlite").toString())
        );
        repository.initialize();
        QuizHistoryService service = new QuizHistoryService(repository);

        int savedCount = service.record(new QuizBookmarkUpdateResponse(
            2,
            Map.of("word-mirror", -1, "word-water", 1),
            Map.of(),
            List.of(),
            List.of(),
            List.of(
                new QuizBookmarkUpdateResponse.TargetResult(
                    "word-mirror", "鏡", "かがみ", "JLPT:N4,EXAMPLE:JLPT_EDGE_BACKFILL", "거울, 경계.", "wrong", -4
                ),
                new QuizBookmarkUpdateResponse.TargetResult(
                    "word-water", "水", "みず", "JLPT:N5", "물.", "correct", -2
                )
            ),
            "ok"
        ));

        QuizHistoryDailyResponse report = service.dailyReport(LocalDate.now(ZoneId.of("Asia/Seoul")), ZoneId.of("Asia/Seoul"));

        assertThat(savedCount).isEqualTo(2);
        assertThat(report.correctCount()).isEqualTo(1);
        assertThat(report.wrongCount()).isEqualTo(1);
        assertThat(report.correctLines()).containsExactly("• 水(みず): N5, 물 (-2)");
        assertThat(report.wrongLines()).containsExactly("• 鏡(かがみ): N4, 거울 (-4)");
        assertThat(report.mustCopyReport()).contains("✅ 맞은 단어");
        assertThat(report.mustCopyReport()).contains("❌ 틀린 단어");
    }

    @Test
    void dailyReportPrefersCurrentWordReadingOverHistorySnapshot() {
        QuizHistoryRepository repository = new QuizHistoryRepository(
            new QuizHistoryConfig(tempDir.resolve("quiz-history.sqlite").toString())
        );
        repository.initialize();
        GraphRepository graphRepository = mock(GraphRepository.class);
        when(graphRepository.findWordsByWordIds(List.of("word-sea")))
            .thenReturn(Map.of("word-sea", Map.of("reading", "うみ")));
        QuizHistoryService service = new QuizHistoryService(repository, graphRepository);

        service.record(new QuizBookmarkUpdateResponse(
            1,
            Map.of("word-sea", 1),
            Map.of(),
            List.of(),
            List.of(),
            List.of(new QuizBookmarkUpdateResponse.TargetResult(
                "word-sea", "海", "海", "JLPT:N5", "바다", "correct", -2
            )),
            "ok"
        ));

        QuizHistoryDailyResponse report = service.dailyReport(
            LocalDate.now(ZoneId.of("Asia/Seoul")),
            ZoneId.of("Asia/Seoul")
        );

        assertThat(report.correctLines()).containsExactly("• 海(うみ): N5, 바다 (-2)");
    }

}
