package com.blue.learnjp.service;

import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Word 노드 품질 보완 서비스.
 * 미보완 노드를 배치 단위로 조회 → OpenClaw에게 한글 번역 및 필드 채움 요청 → Neo4j 업데이트.
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final int BATCH_SIZE = 10;

    private final GraphRepository graphRepository;
    private final OpenClawService openClawService;

    public EnrichmentService(GraphRepository graphRepository, OpenClawService openClawService) {
        this.graphRepository = graphRepository;
        this.openClawService = openClawService;
    }

    public record EnrichResult(int updated, int batchesRequested, String status, String error) {}

    /**
     * 미보완 노드를 배치 단위로 처리한다.
     * Rate limit 등 비정상 응답 시 진행 상태를 보존하고 안전하게 종료한다.
     */
    public EnrichResult enrichBatch(int batchCount) {
        int totalUpdated = 0;

        for (int i = 0; i < batchCount; i++) {
            long queryStart = System.currentTimeMillis();
            List<Map<String, Object>> words = graphRepository.findWordsNeedingEnrichment(BATCH_SIZE);
            log.info("Neo4j query took {}ms to find {} words", System.currentTimeMillis() - queryStart, words.size());

            if (words.isEmpty()) {
                log.info("No more words to enrich. Total updated: {}", totalUpdated);
                return new EnrichResult(totalUpdated, batchCount, "completed", null);
            }

            log.info("Enrichment batch {}/{} - {} words", i + 1, batchCount, words.size());

            try {
                List<Map<String, String>> enriched = openClawService.enrich(words);

                // OpenClaw가 lemma에서 괄호 부분을 제거하므로, 원본 DB lemma와 매핑한다.
                Map<String, String> normalizedToOriginal = new HashMap<>();
                for (Map<String, Object> w : words) {
                    String original = (String) w.get("lemma");
                    if (original != null) {
                        String normalized = original.replaceAll("\\s*\\(.*\\)$", "").trim();
                        normalizedToOriginal.put(normalized, original);
                    }
                }

                long updateStart = System.currentTimeMillis();
                for (Map<String, String> w : enriched) {
                    String returnedLemma = w.get("lemma");
                    if (returnedLemma == null || returnedLemma.isBlank()) continue;

                    // OpenClaw 반환 lemma → 원본 DB lemma로 복원
                    String dbLemma = normalizedToOriginal.getOrDefault(returnedLemma, returnedLemma);

                    graphRepository.updateWordEnrichment(
                        dbLemma,
                        w.getOrDefault("meaning", ""),
                        w.getOrDefault("pos", ""),
                        w.getOrDefault("synonyms", ""),
                        w.getOrDefault("antonyms", ""),
                        w.getOrDefault("description", "")
                    );
                    totalUpdated++;
                }
                log.info("Neo4j update took {}ms for {} words", System.currentTimeMillis() - updateStart, enriched.size());

                log.info("Batch {}/{} completed. Updated {} words", i + 1, batchCount, enriched.size());
            } catch (RateLimitException e) {
                log.warn("Rate limit reached at batch {}/{}. Total updated so far: {}", i + 1, batchCount, totalUpdated);
                return new EnrichResult(totalUpdated, batchCount, "rate_limited", e.getMessage());
            } catch (Exception e) {
                log.error("Batch {}/{} failed: {}. Total updated so far: {}", i + 1, batchCount, e.getMessage(), totalUpdated);
                return new EnrichResult(totalUpdated, batchCount, "error", e.getMessage());
            }
        }

        log.info("Enrichment finished. Total updated: {}", totalUpdated);
        return new EnrichResult(totalUpdated, batchCount, "ok", null);
    }

    /**
     * 미보완 노드 수를 반환한다.
     */
    public int countWordsNeedingEnrichment() {
        return graphRepository.findWordsNeedingEnrichment(Integer.MAX_VALUE).size();
    }
}
