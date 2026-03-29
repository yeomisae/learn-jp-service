package com.blue.learnjp.controller;

import com.blue.learnjp.service.EnrichmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/words")
public class EnrichmentController {

    private final EnrichmentService enrichmentService;

    public EnrichmentController(EnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
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

    /**
     * GET /api/words/enrich/status
     * 품질 보완이 필요한 Word 노드 수를 반환한다.
     */
    @GetMapping("/enrich/status")
    public ResponseEntity<Map<String, Object>> enrichStatus() {
        int remaining = enrichmentService.countWordsNeedingEnrichment();
        return ResponseEntity.ok(Map.of("remaining", remaining));
    }
}
