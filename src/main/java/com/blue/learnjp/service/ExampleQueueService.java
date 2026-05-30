package com.blue.learnjp.service;

import com.blue.learnjp.config.ExampleQueueConfig;
import com.blue.learnjp.http.CircuitBreakerOpenException;
import com.blue.learnjp.http.RetryableExternalServiceException;
import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * jako API 예문을 Neo4j ExampleQueue 노드로 영속화하고,
 * 비동기로 sentence 파이프라인에 재투입하는 큐 서비스.
 * depth=1로 호출하여 재귀 방지.
 */
@Service
public class ExampleQueueService {

    private static final Logger log = LoggerFactory.getLogger(ExampleQueueService.class);

    private final GraphRepository graphRepository;
    private final SentenceService sentenceService;
    private final ExampleQueueConfig config;

    public ExampleQueueService(GraphRepository graphRepository,
                               @Lazy SentenceService sentenceService,
                               ExampleQueueConfig config) {
        this.graphRepository = graphRepository;
        this.sentenceService = sentenceService;
        this.config = config;
    }

    /**
     * 예문을 Neo4j ExampleQueue에 적재한다. textJa MERGE로 중복 방지.
     */
    public void enqueue(String sentence, String source) {
        if (sentence == null || sentence.isBlank()) return;
        graphRepository.enqueueExample(sentence, source);
    }

    public int resumePaused(String source, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return graphRepository.resumePausedExamples(source, safeLimit);
    }

    /**
     * 2초마다 PENDING 예문 1건을 소비하여 sentence 파이프라인에 투입한다.
     */
    @Scheduled(fixedDelayString = "${app.example-queue.consume-delay-ms:2000}")
    public void consume() {
        List<Map<String, Object>> pending = graphRepository.fetchPendingExamples(config.batchSize());
        if (pending.isEmpty()) return;

        Map<String, Object> task = pending.get(0);
        String textJa = (String) task.get("textJa");
        String source = (String) task.get("source");
        int retryCount = ((Number) task.getOrDefault("retryCount", 0)).intValue();

        try {
            log.info("Processing example from queue: '{}' (pending: {})", textJa, graphRepository.countPendingExamples() - 1);
            sentenceService.process(textJa, source, 1);
            graphRepository.updateExampleQueueStatus(textJa, "DONE");
        } catch (RateLimitException | CircuitBreakerOpenException e) {
            log.warn("Pausing example queue item '{}' due to upstream capacity failure: {}", textJa, e.getMessage());
            graphRepository.updateExampleQueueStatus(textJa, "PAUSED", rootCauseMessage(e));
        } catch (RetryableExternalServiceException e) {
            int nextRetryCount = retryCount + 1;
            if (nextRetryCount >= config.retry().maxAttempts()) {
                log.warn("Transient failure exhausted retries for example '{}': {}", textJa, e.getMessage());
                graphRepository.updateExampleQueueStatus(textJa, "FAILED", rootCauseMessage(e));
                return;
            }

            long backoffMs = config.retry().backoffForAttempt(nextRetryCount);
            graphRepository.scheduleExampleRetry(textJa, nextRetryCount, backoffMs, rootCauseMessage(e));
            log.warn("Transient failure on example '{}', retry {}/{} in {} ms: {}",
                textJa, nextRetryCount, config.retry().maxAttempts(), backoffMs, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to process example '{}': {}", textJa, e.getMessage());
            graphRepository.updateExampleQueueStatus(textJa, "FAILED", rootCauseMessage(e));
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = root.getClass().getSimpleName();
        }
        return truncate(message);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
