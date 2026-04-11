package com.blue.learnjp.service;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Word 노드 품질 보완 서비스.
 * jako API를 사용하여 미보완 노드의 meaning, pos, antonyms, starGrade, conjugations 등을 채운다.
 * 기존 LLM 기반 enrich를 대체한다.
 */
@Service
public class EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final int BATCH_SIZE = 10;

    private final GraphRepository graphRepository;
    private final NaverJakoDictionaryService jakoService;
    private final OpenClawService openClawService;

    public EnrichmentService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService, OpenClawService openClawService) {
        this.graphRepository = graphRepository;
        this.jakoService = jakoService;
        this.openClawService = openClawService;
    }

    public record EnrichResult(int updated, int batchesRequested, String status, String error) {}

    /**
     * 미보완 노드를 배치 단위로 jako API를 통해 보강한다.
     */
    public EnrichResult enrichBatch(int batchCount) {
        int totalUpdated = 0;

        for (int i = 0; i < batchCount; i++) {
            List<Map<String, Object>> words = graphRepository.findWordsNeedingEnrichment(BATCH_SIZE);

            if (words.isEmpty()) {
                log.info("No more words to enrich. Total updated: {}", totalUpdated);
                return new EnrichResult(totalUpdated, batchCount, "completed", null);
            }

            log.info("Enrichment batch {}/{} - {} words", i + 1, batchCount, words.size());

            for (Map<String, Object> w : words) {
                String lemma = (String) w.get("lemma");
                if (lemma == null || lemma.isBlank()) continue;

                try {
                    String reading = (String) w.get("reading");
                    JakoLookupResult jako = jakoService.lookup(lemma, reading);

                    if (jako.found()) {
                        graphRepository.upsertJakoWord(
                            lemma, jako.resolvedLemma(), jako.resolvedLemma(),
                            jako.reading(), jako.meaning(), jako.pos(), jako.posDetail(), jako.posDesc(),
                            "", jako.antonyms(), "",
                            "", jako.starGrade(), jako.conjugations(), jako.dictEntryId()
                        );
                        totalUpdated++;
                        log.debug("Enriched '{}' (resolved={})", lemma, jako.resolvedLemma());
                    } else {
                        // jako NOT_FOUND + 숫자 포함 → n-word 변환 후 단위 부분으로 jako 재검색
                        java.util.List<String> nwords = SentenceService.toNWords(lemma);
                        boolean isConverted = !(nwords.size() == 1 && nwords.get(0).equals(lemma));
                        if (isConverted) {
                            // 첫 번째 n-word로 rename, 나머지는 신규 생성
                            graphRepository.renameLemma(lemma, nwords.get(0));
                            for (String nword : nwords) {
                                String unit = nword.replaceAll("^n-", "");
                                JakoLookupResult unitJako = unit.isEmpty() ? JakoLookupResult.notFound() : jakoService.lookup(unit);
                                if (unitJako.found()) {
                                    graphRepository.upsertJakoWord(
                                        nword, nword, nword,
                                        unitJako.reading(), unitJako.meaning(), unitJako.pos(), unitJako.posDetail(), unitJako.posDesc(),
                                        "", unitJako.antonyms(), "",
                                        "", unitJako.starGrade(), unitJako.conjugations(), unitJako.dictEntryId()
                                    );
                                    totalUpdated++;
                                } else {
                                    graphRepository.mergeWord(nword, nword, "", "", "", "", "", "", "", "", "", 0, "[]", "NOT_FOUND");
                                }
                                log.info("Enrichment: '{}' → n-word '{}' (jako {})", lemma, nword, unitJako.found() ? "found" : "NOT_FOUND");
                            }
                        } else if (lemma.startsWith("n-") && lemma.length() > 2) {
                            // 이미 n-word 형태인 경우: 단위 부분으로 jako 재검색
                            String unit = lemma.substring(2);
                            JakoLookupResult unitJako = jakoService.lookup(unit);
                            if (unitJako.found()) {
                                graphRepository.upsertJakoWord(
                                    lemma, lemma, lemma,
                                    unitJako.reading(), unitJako.meaning(), unitJako.pos(), unitJako.posDetail(), unitJako.posDesc(),
                                    "", unitJako.antonyms(), "",
                                    "", unitJako.starGrade(), unitJako.conjugations(), unitJako.dictEntryId()
                                );
                                totalUpdated++;
                                log.info("Enrichment: n-word '{}' enriched via unit '{}' ", lemma, unit);
                            } else {
                                graphRepository.markDictNotFound(lemma);
                                log.debug("jako: no match for n-word '{}' unit '{}'", lemma, unit);
                            }
                        } else {
                            // jako NOT_FOUND + n-word 아님 → LLM decompose 시도
                            List<AnalysisResult.WordInfo> parts = openClawService.decompose(lemma);
                            if (!parts.isEmpty()) {
                                log.info("Enrichment: decomposed '{}' → {} parts", lemma, parts.size());
                                // 첫 번째 파트로 rename
                                graphRepository.renameLemma(lemma, parts.get(0).lemma());
                                for (AnalysisResult.WordInfo part : parts) {
                                    JakoLookupResult partJako = jakoService.lookup(part.lemma(), part.reading());
                                    if (partJako.found()) {
                                        graphRepository.upsertJakoWord(
                                            part.lemma(), partJako.resolvedLemma(), part.surface(),
                                            partJako.reading(), partJako.meaning(), partJako.pos(), partJako.posDetail(), partJako.posDesc(),
                                            "", partJako.antonyms(), "",
                                            "", partJako.starGrade(), partJako.conjugations(), partJako.dictEntryId()
                                        );
                                        totalUpdated++;
                                        log.info("Enrichment: decomposed part '{}' saved (jako found)", part.lemma());
                                    } else {
                                        // 분해된 파트도 jako 못 찾으면 → n-word 시도
                                        java.util.List<String> partNwords = SentenceService.toNWords(part.lemma());
                                        boolean partConverted = !(partNwords.size() == 1 && partNwords.get(0).equals(part.lemma()));
                                        if (partConverted) {
                                            for (String nw : partNwords) {
                                                String unit = nw.replaceAll("^n-", "");
                                                JakoLookupResult unitJako = unit.isEmpty() ? JakoLookupResult.notFound() : jakoService.lookup(unit);
                                                if (unitJako.found()) {
                                                    graphRepository.upsertJakoWord(
                                                        nw, nw, nw,
                                                        unitJako.reading(), unitJako.meaning(), unitJako.pos(), unitJako.posDetail(), unitJako.posDesc(),
                                                        "", unitJako.antonyms(), "",
                                                        "", unitJako.starGrade(), unitJako.conjugations(), unitJako.dictEntryId()
                                                    );
                                                    totalUpdated++;
                                                } else {
                                                    graphRepository.mergeWord(nw, nw, "", "", "", "", "", "", "", "", "", 0, "[]", "NOT_FOUND");
                                                }
                                            }
                                        } else {
                                            graphRepository.mergeWord(
                                                part.surface(), part.lemma(), part.reading(), "", "",
                                                "", "", "", "", "",
                                                "", 0, "[]", ""
                                            );
                                            log.info("Enrichment: decomposed part '{}' saved (jako NOT_FOUND)", part.lemma());
                                        }
                                    }
                                }
                            } else {
                                graphRepository.markDictNotFound(lemma);
                                log.debug("jako: no match for '{}', decompose failed, marked NOT_FOUND", lemma);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to enrich '{}': {}", lemma, e.getMessage());
                }
            }

            log.info("Batch {}/{} completed. Total updated so far: {}", i + 1, batchCount, totalUpdated);
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
