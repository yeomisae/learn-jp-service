package com.blue.learnjp.controller;

import com.blue.learnjp.service.EnrichmentService;
import com.blue.learnjp.service.ExampleQueueService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/words")
public class EnrichmentController {

    private final EnrichmentService enrichmentService;
    private final ExampleQueueService exampleQueueService;

    public EnrichmentController(EnrichmentService enrichmentService,
                                ExampleQueueService exampleQueueService) {
        this.enrichmentService = enrichmentService;
        this.exampleQueueService = exampleQueueService;
    }

    /**
     * POST /api/words/enrich?batches=5
     * 미보완 Word 노드를 배치 단위로 품질 보완한다.
     * 1배치 = 20개 단어. 기본 1배치.
     */
    @PostMapping("/enrich")
    public ResponseEntity<EnrichmentService.EnrichResult> enrich(
            @RequestParam(defaultValue = "1") int batches) {
        return ResponseEntity.ok(enrichmentService.enrichBatch(batches));
    }

    @PostMapping("/enrich/reading")
    public ResponseEntity<EnrichmentService.ReadingEnrichResult> enrichReadings(
            @RequestParam(defaultValue = "") String lemmas) {
        return ResponseEntity.ok(enrichmentService.enrichReadings(lemmas));
    }

    /**
     * GET /api/words/enrich/status
     * 품질 보완이 필요한 Word 노드 수를 반환한다.
     */
    @GetMapping("/enrich/status")
    public ResponseEntity<Map<String, Object>> enrichStatus() {
        int remaining = enrichmentService.countWordsNeedingEnrichment();
        return ResponseEntity.ok(Map.of("remaining", remaining));
    }

    /**
     * POST /api/words/edges/backfill/jlpt?levels=N5,N4&limit=1
     * JLPT 단어의 jako 예문을 ExampleQueue에 적재하여 CO_OCCURS edge 생성을 비동기로 유도한다.
     */
    @PostMapping("/edges/backfill/jlpt")
    public ResponseEntity<?> backfillJlptExampleEdges(
            @RequestParam(defaultValue = "N5,N4,N3,N2,N1") String levels,
            @RequestParam(defaultValue = "1") int limit) {
        try {
            return ResponseEntity.ok(enrichmentService.backfillJlptExampleEdges(levels, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/words/edges/backfill/resume-paused?limit=50
     * OpenClaw capacity 문제 등으로 PAUSED 처리된 예문 큐 항목을 재시도 대상으로 되돌린다.
     */
    @PostMapping("/edges/backfill/resume-paused")
    public ResponseEntity<Map<String, Object>> resumePausedBackfillExamples(
            @RequestParam(defaultValue = "EXAMPLE:JLPT_EDGE_BACKFILL") String source,
            @RequestParam(defaultValue = "50") int limit) {
        int resumed = exampleQueueService.resumePaused(source, limit);
        return ResponseEntity.ok(Map.of("resumed", resumed, "source", source));
    }
}
