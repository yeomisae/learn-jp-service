package com.blue.learnjp.service;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        if (graphRepository.sentenceExists(sentence)) {
            log.info("Sentence already registered, skipping: {}", sentence);
            return new AnalysisResult(java.util.List.of(), java.util.List.of());
        }

        AnalysisResult result = openClawService.analyze(sentence);
        log.info("Analysis result - words: {}, edges: {}", result.words().size(), result.edges().size());
        saveAndSync(sentence, result);
        return result;
    }

    @Transactional
    public AnalysisResult importResult(String sentence, AnalysisResult result) {
        if (sentence != null && !sentence.isBlank() && graphRepository.sentenceExists(sentence)) {
            log.info("Sentence already registered, skipping: {}", sentence);
            return new AnalysisResult(java.util.List.of(), java.util.List.of());
        }

        log.info("Importing pre-analyzed result - words: {}, edges: {}", result.words().size(), result.edges().size());
        saveAndSync(sentence, result);
        return result;
    }

    private void saveAndSync(String sentence, AnalysisResult result) {
        if (sentence != null && !sentence.isBlank()) {
            graphRepository.createSentence(sentence);
        }

        saveWords(result.words());

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

    /**
     * 분석 결과의 단어를 저장한다.
     * - 신규 단어: mergeWord (ON CREATE)
     * - 기존 단어: reconcile로 의미적 중복 제거 후 setWordFields (덮어쓰기)
     * - reconcile 실패 시 기존 mergeWord 방식 fallback
     */
    private void saveWords(List<AnalysisResult.WordInfo> words) {
        if (words.isEmpty()) return;

        List<String> lemmas = words.stream().map(AnalysisResult.WordInfo::lemma).toList();
        Map<String, Map<String, Object>> existingWords = graphRepository.findWordsByLemmas(lemmas);

        // 기존 단어가 없으면 전부 mergeWord로 저장
        if (existingWords.isEmpty()) {
            for (AnalysisResult.WordInfo w : words) {
                graphRepository.mergeWord(w.surface(), w.lemma(), w.reading(),
                    w.meaning(), w.pos(), w.synonyms(), w.antonyms(), w.description(), w.jlptLevel());
            }
            return;
        }

        // reconcile 대상 분류
        List<AnalysisResult.WordInfo> needsReconcile = words.stream()
            .filter(w -> existingWords.containsKey(w.lemma()))
            .toList();

        log.info("Reconciling {} existing words out of {} total", needsReconcile.size(), words.size());

        // reconcile 시도
        Map<String, AnalysisResult.WordInfo> reconciledMap = new java.util.HashMap<>();
        try {
            List<AnalysisResult.WordInfo> reconciled = openClawService.reconcile(needsReconcile, existingWords);
            for (AnalysisResult.WordInfo r : reconciled) {
                reconciledMap.put(r.lemma(), r);
            }
        } catch (Exception e) {
            log.warn("Reconcile failed, falling back to accumulation: {}", e.getMessage());
        }

        for (AnalysisResult.WordInfo w : words) {
            AnalysisResult.WordInfo r = reconciledMap.get(w.lemma());
            if (r != null) {
                // reconcile 성공한 기존 단어: 정리된 값으로 덮어쓰기
                graphRepository.setWordFields(w.lemma(), r.surface(),
                    r.meaning(), r.pos(), r.synonyms(), r.antonyms(), r.description());
            } else {
                // 신규 단어 또는 reconcile 실패: 기존 방식
                graphRepository.mergeWord(w.surface(), w.lemma(), w.reading(),
                    w.meaning(), w.pos(), w.synonyms(), w.antonyms(), w.description(), w.jlptLevel());
            }
        }
    }
}
