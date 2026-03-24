package com.blue.learnjp.service;

import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Word 노드 품질 보완 서비스.
 * 미보완 노드를 배치 단위로 조회 → OpenClaw에게 한글 번역 및 필드 채움 요청 → Neo4j 업데이트.
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final int BATCH_SIZE = 20;

    private final GraphRepository graphRepository;
    private final OpenClawService openClawService;

    public EnrichmentService(GraphRepository graphRepository, OpenClawService openClawService) {
        this.graphRepository = graphRepository;
        this.openClawService = openClawService;
    }

    /**
     * 미보완 노드를 배치 단위로 처리한다.
     * @param batchCount 처리할 배치 수 (1배치 = 20개 단어)
     * @return 총 업데이트된 단어 수
     */
    public int enrichBatch(int batchCount) {
        int totalUpdated = 0;

        for (int i = 0; i < batchCount; i++) {
            List<Map<String, Object>> words = graphRepository.findWordsNeedingEnrichment(BATCH_SIZE);
            if (words.isEmpty()) {
                log.info("No more words to enrich. Total updated: {}", totalUpdated);
                break;
            }

            log.info("Enrichment batch {}/{} - {} words", i + 1, batchCount, words.size());

            try {
                List<Map<String, String>> enriched = openClawService.enrich(words);

                for (Map<String, String> w : enriched) {
                    String lemma = w.get("lemma");
                    if (lemma == null || lemma.isBlank()) continue;

                    graphRepository.updateWordEnrichment(
                        lemma,
                        w.getOrDefault("meaning", ""),
                        w.getOrDefault("pos", ""),
                        w.getOrDefault("synonyms", ""),
                        w.getOrDefault("antonyms", ""),
                        w.getOrDefault("description", "")
                    );
                    totalUpdated++;
                }

                log.info("Batch {}/{} completed. Updated {} words", i + 1, batchCount, enriched.size());
            } catch (Exception e) {
                log.error("Batch {}/{} failed: {}", i + 1, batchCount, e.getMessage());
                break;
            }
        }

        return totalUpdated;
    }

    /**
     * 미보완 노드 수를 반환한다.
     */
    public int countWordsNeedingEnrichment() {
        return graphRepository.findWordsNeedingEnrichment(Integer.MAX_VALUE).size();
    }
}
