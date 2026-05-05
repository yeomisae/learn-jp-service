package com.blue.learnjp.service;

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

    public ExampleQueueService(GraphRepository graphRepository,
                               @Lazy SentenceService sentenceService) {
        this.graphRepository = graphRepository;
        this.sentenceService = sentenceService;
    }

    /**
     * 예문을 Neo4j ExampleQueue에 적재한다. textJa MERGE로 중복 방지.
     */
    public void enqueue(String sentence, String source) {
        if (sentence == null || sentence.isBlank()) return;
        graphRepository.enqueueExample(sentence, source);
    }

    /**
     * 2초마다 PENDING 예문 1건을 소비하여 sentence 파이프라인에 투입한다.
     */
    @Scheduled(fixedDelay = 2000)
    public void consume() {
        List<Map<String, Object>> pending = graphRepository.fetchPendingExamples(1);
        if (pending.isEmpty()) return;

        Map<String, Object> task = pending.get(0);
        String textJa = (String) task.get("textJa");
        String source = (String) task.get("source");

        try {
            log.info("Processing example from queue: '{}' (pending: {})", textJa, graphRepository.countPendingExamples() - 1);
            sentenceService.process(textJa, source, 1);
            graphRepository.updateExampleQueueStatus(textJa, "DONE");
        } catch (RateLimitException e) {
            log.warn("Rate limit on example '{}', keeping PENDING", textJa);
            // PENDING 유지 — 다음 cycle에 재시도
        } catch (Exception e) {
            log.warn("Failed to process example '{}': {}", textJa, e.getMessage());
            graphRepository.updateExampleQueueStatus(textJa, "FAILED");
        }
    }
}
