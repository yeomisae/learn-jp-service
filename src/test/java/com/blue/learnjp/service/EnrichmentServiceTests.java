package com.blue.learnjp.service;

import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.repository.GraphRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EnrichmentServiceTests {

    @Test
    void enrichBatchUsesOpenClawReadingWhenJakoReadingIsKanji() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        OpenClawService openClawService = mock(OpenClawService.class);
        ExampleQueueService exampleQueueService = mock(ExampleQueueService.class);

        when(graphRepository.findWordsNeedingEnrichment(10))
            .thenReturn(List.of(Map.of("lemma", "海", "reading", "海")))
            .thenReturn(List.of());
        when(jakoService.lookup("海", "海")).thenReturn(new JakoLookupResult(
            true,
            "海",
            "海",
            "바다",
            "명사",
            "名詞",
            "명사",
            "",
            2,
            "[]",
            "123",
            List.of()
        ));
        when(openClawService.inferReading("海")).thenReturn("うみ");

        EnrichmentService service = new EnrichmentService(
            graphRepository,
            jakoService,
            openClawService,
            exampleQueueService
        );

        EnrichmentService.EnrichResult result = service.enrichBatch(2);

        assertThat(result.updated()).isEqualTo(1);
        verify(graphRepository).upsertJakoWord(
            "海",
            "海",
            "海",
            "うみ",
            "바다",
            "명사",
            "名詞",
            "명사",
            "",
            "",
            "",
            "",
            2,
            "[]",
            "123"
        );
        verify(graphRepository, never()).markReadingBackfillUnresolved(anyString());
    }

    @Test
    void backfillJlptExampleEdgesEnqueuesJakoExamplesForOneWord() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        OpenClawService openClawService = mock(OpenClawService.class);
        ExampleQueueService exampleQueueService = mock(ExampleQueueService.class);

        when(graphRepository.findJlptWordsNeedingExampleBackfill(List.of("JLPT:N5"), 1))
            .thenReturn(List.of(Map.of(
                "lemma", "食べる",
                "reading", "たべる",
                "source", "JLPT:N5",
                "dictEntryId", "123"
            )));
        when(jakoService.lookup("食べる", "たべる")).thenReturn(new JakoLookupResult(
            true,
            "食べる",
            "たべる",
            "먹다",
            "동사",
            "下一段他動詞",
            "하1단 타동사",
            "",
            1,
            "[]",
            "123",
            List.of(new JakoLookupResult.Example("ご飯を食べます。", "밥을 먹습니다."))
        ));

        EnrichmentService service = new EnrichmentService(
            graphRepository,
            jakoService,
            openClawService,
            exampleQueueService
        );

        EnrichmentService.JlptExampleBackfillResult result = service.backfillJlptExampleEdges("N5", 1);

        assertThat(result.lookedUpWords()).isEqualTo(1);
        assertThat(result.examplesEnqueued()).isEqualTo(1);
        assertThat(result.skippedWords()).isZero();
        assertThat(result.attemptedLemmas()).containsExactly("食べる");
        assertThat(result.status()).isEqualTo("ok");
        verify(exampleQueueService).enqueue("ご飯を食べます。", "EXAMPLE:JLPT_EDGE_BACKFILL");
        var inOrder = inOrder(graphRepository, jakoService, exampleQueueService);
        inOrder.verify(graphRepository).markJlptExampleBackfillAttempt("食べる", 0, "IN_PROGRESS");
        inOrder.verify(jakoService).lookup("食べる", "たべる");
        inOrder.verify(exampleQueueService).enqueue("ご飯を食べます。", "EXAMPLE:JLPT_EDGE_BACKFILL");
        inOrder.verify(graphRepository).markJlptExampleBackfillAttempt("食べる", 1, "ENQUEUED");
    }

    @Test
    void backfillJlptExampleEdgesSkipsShortCompoundExamples() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        OpenClawService openClawService = mock(OpenClawService.class);
        ExampleQueueService exampleQueueService = mock(ExampleQueueService.class);

        when(graphRepository.findJlptWordsNeedingExampleBackfill(List.of("JLPT:N5"), 1))
            .thenReturn(List.of(Map.of(
                "lemma", "半",
                "reading", "はん",
                "source", "JLPT:N5",
                "dictEntryId", "456"
            )));
        when(jakoService.lookup("半", "はん")).thenReturn(new JakoLookupResult(
            true,
            "半",
            "はん",
            "반",
            "",
            "",
            "",
            "",
            0,
            "[]",
            "456",
            List.of(
                new JakoLookupResult.Example("半年", "반년"),
                new JakoLookupResult.Example("半途", "중도")
            )
        ));

        EnrichmentService service = new EnrichmentService(
            graphRepository,
            jakoService,
            openClawService,
            exampleQueueService
        );

        EnrichmentService.JlptExampleBackfillResult result = service.backfillJlptExampleEdges("N5", 1);

        assertThat(result.lookedUpWords()).isEqualTo(1);
        assertThat(result.examplesEnqueued()).isZero();
        assertThat(result.status()).isEqualTo("ok");
        verify(exampleQueueService, never()).enqueue(anyString(), anyString());
        verify(graphRepository).markJlptExampleBackfillAttempt("半", 0, "IN_PROGRESS");
        verify(graphRepository).markJlptExampleBackfillAttempt("半", 0, "NO_USEFUL_EXAMPLES");
    }

    @Test
    void backfillJlptExampleEdgesMarksPausedWhenLookupIsInterruptedByRateLimit() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        OpenClawService openClawService = mock(OpenClawService.class);
        ExampleQueueService exampleQueueService = mock(ExampleQueueService.class);

        when(graphRepository.findJlptWordsNeedingExampleBackfill(List.of("JLPT:N5"), 1))
            .thenReturn(List.of(Map.of(
                "lemma", "考える",
                "reading", "かんがえる",
                "source", "JLPT:N5",
                "dictEntryId", "789"
            )));
        when(jakoService.lookup("考える", "かんがえる"))
            .thenThrow(new RateLimitException("jako rate limit"));

        EnrichmentService service = new EnrichmentService(
            graphRepository,
            jakoService,
            openClawService,
            exampleQueueService
        );

        EnrichmentService.JlptExampleBackfillResult result = service.backfillJlptExampleEdges("N5", 1);

        assertThat(result.status()).isEqualTo("paused");
        assertThat(result.attemptedLemmas()).containsExactly("考える");
        var inOrder = inOrder(graphRepository, jakoService);
        inOrder.verify(graphRepository).markJlptExampleBackfillAttempt("考える", 0, "IN_PROGRESS");
        inOrder.verify(jakoService).lookup("考える", "かんがえる");
        inOrder.verify(graphRepository).markJlptExampleBackfillAttempt("考える", 0, "PAUSED");
    }
}
