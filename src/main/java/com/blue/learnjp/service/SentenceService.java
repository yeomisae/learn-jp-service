package com.blue.learnjp.service;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 문장 입력 파이프라인의 핵심 서비스.
 * 1. OpenClaw로 문장 분석
 * 2. 노드 MERGE
 * 3. 엣지 CREATE
 * 4. Google Sheets 동기화 (설정 시)
 */
@Service
public class SentenceService {

    private static final Logger log = LoggerFactory.getLogger(SentenceService.class);

    private final OpenClawService openClawService;
    private final GraphRepository graphRepository;
    private final Optional<GoogleSheetsService> googleSheetsService;

    public SentenceService(OpenClawService openClawService, GraphRepository graphRepository,
                           Optional<GoogleSheetsService> googleSheetsService) {
        this.openClawService = openClawService;
        this.graphRepository = graphRepository;
        this.googleSheetsService = googleSheetsService;
    }

    @Transactional
    public AnalysisResult process(String sentence) {
        AnalysisResult result = openClawService.analyze(sentence);
        log.info("Analysis result - words: {}, edges: {}", result.words().size(), result.edges().size());
        saveAndSync(sentence, result);
        return result;
    }

    @Transactional
    public AnalysisResult importResult(String sentence, AnalysisResult result) {
        log.info("Importing pre-analyzed result - words: {}, edges: {}", result.words().size(), result.edges().size());
        saveAndSync(sentence, result);
        return result;
    }

    private void saveAndSync(String sentence, AnalysisResult result) {
        for (AnalysisResult.WordInfo word : result.words()) {
            graphRepository.mergeWord(
                word.surface(),
                word.lemma(),
                word.reading(),
                word.meaning(),
                word.pos(),
                word.synonyms(),
                word.antonyms(),
                word.description(),
                word.jlptLevel()
            );
        }

        for (AnalysisResult.EdgeInfo edge : result.edges()) {
            graphRepository.createCoOccursEdge(
                edge.from(),
                edge.to(),
                sentence,
                edge.pattern()
            );
        }

        googleSheetsService.ifPresent(sheets -> {
            try {
                sheets.exportWords();
            } catch (Exception e) {
                log.warn("Google Sheets sync failed (non-blocking): {}", e.getMessage());
            }
        });
    }
}
