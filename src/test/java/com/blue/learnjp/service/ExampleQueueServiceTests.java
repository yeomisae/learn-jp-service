package com.blue.learnjp.service;

import com.blue.learnjp.config.ExampleQueueConfig;
import com.blue.learnjp.config.RetryConfig;
import com.blue.learnjp.http.CircuitBreakerOpenException;
import com.blue.learnjp.http.RetryableExternalServiceException;
import com.blue.learnjp.repository.GraphRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

class ExampleQueueServiceTests {

    @Test
    void consumeSchedulesRetryWithBackoffForTransientFailures() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        SentenceService sentenceService = mock(SentenceService.class);
        ExampleQueueConfig config = new ExampleQueueConfig(2_000, 1, RetryConfig.defaults(4, 10_000, 60_000, 2.0));

        when(graphRepository.fetchPendingExamples(1)).thenReturn(List.of(
            Map.of("textJa", "寿司が好きです", "source", "NEWS", "retryCount", 1)
        ));
        when(graphRepository.countPendingExamples()).thenReturn(1L);
        doThrow(new RetryableExternalServiceException("temporary downstream failure", false))
            .when(sentenceService).process("寿司が好きです", "NEWS", 1);

        ExampleQueueService service = new ExampleQueueService(graphRepository, sentenceService, config);

        service.consume();

        verify(graphRepository).scheduleExampleRetry(eq("寿司が好きです"), eq(2), eq(20_000L), contains("temporary downstream failure"));
        verify(graphRepository, never()).updateExampleQueueStatus("寿司が好きです", "DONE");
    }

    @Test
    void consumePausesQueueItemForRateLimitFailures() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        SentenceService sentenceService = mock(SentenceService.class);
        ExampleQueueConfig config = new ExampleQueueConfig(2_000, 1, RetryConfig.defaults(4, 10_000, 60_000, 2.0));

        when(graphRepository.fetchPendingExamples(1)).thenReturn(List.of(
            Map.of("textJa", "考えることもできない大事件", "source", "EXAMPLE:JLPT_EDGE_BACKFILL", "retryCount", 0)
        ));
        when(graphRepository.countPendingExamples()).thenReturn(1L);
        doThrow(new RateLimitException("OpenClaw rate limit reached"))
            .when(sentenceService).process("考えることもできない大事件", "EXAMPLE:JLPT_EDGE_BACKFILL", 1);

        ExampleQueueService service = new ExampleQueueService(graphRepository, sentenceService, config);

        service.consume();

        verify(graphRepository).updateExampleQueueStatus(
            eq("考えることもできない大事件"),
            eq("PAUSED"),
            contains("OpenClaw rate limit reached")
        );
        verify(graphRepository, never()).scheduleExampleRetry(anyString(), anyInt(), anyLong(), anyString());
    }

    @Test
    void consumePausesQueueItemForCircuitBreakerFailures() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        SentenceService sentenceService = mock(SentenceService.class);
        ExampleQueueConfig config = new ExampleQueueConfig(2_000, 1, RetryConfig.defaults(4, 10_000, 60_000, 2.0));

        when(graphRepository.fetchPendingExamples(1)).thenReturn(List.of(
            Map.of("textJa", "ご承知の通り", "source", "EXAMPLE:JLPT_EDGE_BACKFILL", "retryCount", 0)
        ));
        when(graphRepository.countPendingExamples()).thenReturn(1L);
        doThrow(new CircuitBreakerOpenException("openclaw circuit breaker is OPEN"))
            .when(sentenceService).process("ご承知の通り", "EXAMPLE:JLPT_EDGE_BACKFILL", 1);

        ExampleQueueService service = new ExampleQueueService(graphRepository, sentenceService, config);

        service.consume();

        verify(graphRepository).updateExampleQueueStatus(
            eq("ご承知の通り"),
            eq("PAUSED"),
            contains("openclaw circuit breaker is OPEN")
        );
        verify(graphRepository, never()).scheduleExampleRetry(anyString(), anyInt(), anyLong(), anyString());
    }

    @Test
    void consumeStoresLastErrorForNonTransientFailures() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        SentenceService sentenceService = mock(SentenceService.class);
        ExampleQueueConfig config = new ExampleQueueConfig(2_000, 1, RetryConfig.defaults(4, 10_000, 60_000, 2.0));

        when(graphRepository.fetchPendingExamples(1)).thenReturn(List.of(
            Map.of("textJa", "新しいデザインを考える", "source", "EXAMPLE:JLPT_EDGE_BACKFILL", "retryCount", 0)
        ));
        when(graphRepository.countPendingExamples()).thenReturn(1L);
        doThrow(new RuntimeException(
            "OpenClaw analysis failed",
            new IllegalArgumentException("Unrecognized token 'No'")
        ))
            .when(sentenceService).process("新しいデザインを考える", "EXAMPLE:JLPT_EDGE_BACKFILL", 1);

        ExampleQueueService service = new ExampleQueueService(graphRepository, sentenceService, config);

        service.consume();

        verify(graphRepository).updateExampleQueueStatus(
            eq("新しいデザインを考える"),
            eq("FAILED"),
            contains("Unrecognized token 'No'")
        );
    }

    @Test
    void consumeStoresLastErrorWhenTransientRetriesAreExhausted() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        SentenceService sentenceService = mock(SentenceService.class);
        ExampleQueueConfig config = new ExampleQueueConfig(2_000, 1, RetryConfig.defaults(2, 10_000, 60_000, 2.0));

        when(graphRepository.fetchPendingExamples(1)).thenReturn(List.of(
            Map.of("textJa", "ご承知の通り", "source", "EXAMPLE:JLPT_EDGE_BACKFILL", "retryCount", 1)
        ));
        when(graphRepository.countPendingExamples()).thenReturn(1L);
        doThrow(new RetryableExternalServiceException("openclaw timeout", true))
            .when(sentenceService).process("ご承知の通り", "EXAMPLE:JLPT_EDGE_BACKFILL", 1);

        ExampleQueueService service = new ExampleQueueService(graphRepository, sentenceService, config);

        service.consume();

        verify(graphRepository).updateExampleQueueStatus(
            eq("ご承知の通り"),
            eq("FAILED"),
            contains("openclaw timeout")
        );
    }

    @Test
    void resumePausedClampsLimitAndDelegatesToRepository() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        SentenceService sentenceService = mock(SentenceService.class);
        ExampleQueueConfig config = new ExampleQueueConfig(2_000, 1, RetryConfig.defaults(4, 10_000, 60_000, 2.0));
        when(graphRepository.resumePausedExamples("EXAMPLE:JLPT_EDGE_BACKFILL", 500)).thenReturn(12);

        ExampleQueueService service = new ExampleQueueService(graphRepository, sentenceService, config);

        int resumed = service.resumePaused("EXAMPLE:JLPT_EDGE_BACKFILL", 5_000);

        verify(graphRepository).resumePausedExamples("EXAMPLE:JLPT_EDGE_BACKFILL", 500);
        org.assertj.core.api.Assertions.assertThat(resumed).isEqualTo(12);
    }
}
