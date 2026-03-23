package com.blue.learnjp.service;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문장 입력 파이프라인의 핵심 서비스.
 * 1. OpenClaw로 문장 분석
 * 2. 노드 MERGE
 * 3. 엣지 CREATE
 */
@Service
public class SentenceService {

    private static final Logger log = LoggerFactory.getLogger(SentenceService.class);

    private final OpenClawService openClawService;
    private final GraphRepository graphRepository;

    public SentenceService(OpenClawService openClawService, GraphRepository graphRepository) {
        this.openClawService = openClawService;
        this.graphRepository = graphRepository;
    }

    @Transactional
    public AnalysisResult process(String sentence) {
        // 1. OpenClaw 분석
        AnalysisResult result = openClawService.analyze(sentence);

        log.info("Analysis result - words: {}, edges: {}", result.words().size(), result.edges().size());

        // 2. 노드 MERGE
        for (AnalysisResult.WordInfo word : result.words()) {
            graphRepository.mergeWord(
                word.surface(),
                word.lemma(),
                word.reading(),
                word.meaning(),
                word.pos(),
                word.synonyms(),
                word.antonyms(),
                word.description()
            );
        }

        // 3. 엣지 CREATE
        for (AnalysisResult.EdgeInfo edge : result.edges()) {
            graphRepository.createCoOccursEdge(
                edge.from(),
                edge.to(),
                sentence,
                edge.pattern()
            );
        }

        return result;
    }
}
