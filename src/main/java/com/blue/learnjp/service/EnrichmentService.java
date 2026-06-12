package com.blue.learnjp.service;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.http.CircuitBreakerOpenException;
import com.blue.learnjp.http.RetryableExternalServiceException;
import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int MIN_EXAMPLE_LENGTH = 5;

    private final GraphRepository graphRepository;
    private final NaverJakoDictionaryService jakoService;
    private final OpenClawService openClawService;
    private final ExampleQueueService exampleQueueService;

    public EnrichmentService(GraphRepository graphRepository,
                             NaverJakoDictionaryService jakoService,
                             OpenClawService openClawService,
                             ExampleQueueService exampleQueueService) {
        this.graphRepository = graphRepository;
        this.jakoService = jakoService;
        this.openClawService = openClawService;
        this.exampleQueueService = exampleQueueService;
    }

    public record EnrichResult(int updated, int batchesRequested, String status, String error) {}
    public record ReadingEnrichResult(int updated, List<String> attemptedLemmas, String status, String error) {}
    public record JlptExampleBackfillResult(
        int lookedUpWords,
        int examplesEnqueued,
        int skippedWords,
        List<String> attemptedLemmas,
        String status,
        String error
    ) {}

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
                        String resolvedReading = resolveReading(lemma, jako.reading());
                        graphRepository.upsertJakoWord(
                            lemma, jako.resolvedLemma(), jako.resolvedLemma(),
                            resolvedReading, jako.meaning(), jako.pos(), jako.posDetail(), jako.posDesc(),
                            "", jako.antonyms(), "",
                            "", jako.starGrade(), jako.conjugations(), jako.dictEntryId()
                        );
                        if (isBadReading(lemma, resolvedReading)) {
                            graphRepository.markReadingBackfillUnresolved(jako.resolvedLemma());
                        }
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
                } catch (RateLimitException | RetryableExternalServiceException | CircuitBreakerOpenException e) {
                    log.warn("Transient enrichment failure on '{}', stopping batch early: {}", lemma, e.getMessage());
                    return new EnrichResult(totalUpdated, batchCount, "paused", e.getMessage());
                } catch (Exception e) {
                    log.warn("Failed to enrich '{}': {}", lemma, e.getMessage());
                }
            }

            log.info("Batch {}/{} completed. Total updated so far: {}", i + 1, batchCount, totalUpdated);
        }

        log.info("Enrichment finished. Total updated: {}", totalUpdated);
        return new EnrichResult(totalUpdated, batchCount, "ok", null);
    }

    public ReadingEnrichResult enrichReadings(String lemmasCsv) {
        List<String> lemmas = normalizeCsv(lemmasCsv);
        if (lemmas.isEmpty()) {
            return new ReadingEnrichResult(0, List.of(), "ok", null);
        }

        Map<String, Map<String, Object>> words = graphRepository.findWordsByLemmas(lemmas);
        int updated = 0;
        List<String> attempted = new ArrayList<>();
        for (String lemma : lemmas) {
            Map<String, Object> word = words.get(lemma);
            if (word == null) {
                continue;
            }
            String reading = stringValue(word.get("reading"));
            try {
                JakoLookupResult jako = jakoService.lookup(lemma, reading);
                if (!jako.found()) {
                    continue;
                }
                String resolvedReading = resolveReading(lemma, jako.reading());
                graphRepository.upsertJakoWord(
                    lemma, jako.resolvedLemma(), jako.resolvedLemma(),
                    resolvedReading, jako.meaning(), jako.pos(), jako.posDetail(), jako.posDesc(),
                    "", jako.antonyms(), "",
                    "", jako.starGrade(), jako.conjugations(), jako.dictEntryId()
                );
                if (isBadReading(lemma, resolvedReading)) {
                    graphRepository.markReadingBackfillUnresolved(jako.resolvedLemma());
                }
                attempted.add(lemma);
                updated++;
            } catch (RateLimitException | RetryableExternalServiceException | CircuitBreakerOpenException e) {
                return new ReadingEnrichResult(updated, List.copyOf(attempted), "paused", e.getMessage());
            }
        }
        return new ReadingEnrichResult(updated, List.copyOf(attempted), "ok", null);
    }

    private String resolveReading(String lemma, String reading) {
        if (!isBadReading(lemma, reading)) {
            return reading != null ? reading : "";
        }

        String inferred = openClawService.inferReading(lemma);
        if (!isBadReading(lemma, inferred)) {
            log.info("Enrichment: reading fallback '{}' → '{}'", lemma, inferred);
            return inferred;
        }
        log.warn("Enrichment: reading unresolved for '{}' (jako='{}', inferred='{}')", lemma, reading, inferred);
        return reading != null ? reading : "";
    }

    private List<String> normalizeCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
    }

    private boolean isBadReading(String lemma, String reading) {
        String normalizedReading = reading != null ? reading.trim() : "";
        return lemma != null && !lemma.isBlank()
            && (normalizedReading.isBlank()
                || (normalizedReading.equals(lemma) && containsKanji(lemma))
                || containsKanji(normalizedReading));
    }

    private boolean containsKanji(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 미보완 노드 수를 반환한다.
     */
    public int countWordsNeedingEnrichment() {
        return graphRepository.findWordsNeedingEnrichment(Integer.MAX_VALUE).size();
    }

    /**
     * JLPT 단어의 jako 예문을 ExampleQueue에 적재한다.
     * 실제 edge 생성은 기존 ExampleQueue consumer가 sentence pipeline을 통해 비동기로 수행한다.
     */
    public JlptExampleBackfillResult backfillJlptExampleEdges(String levelsCsv, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<String> sources = normalizeJlptSources(levelsCsv);
        List<Map<String, Object>> words = graphRepository.findJlptWordsNeedingExampleBackfill(sources, safeLimit);

        int lookedUpWords = 0;
        int examplesEnqueued = 0;
        int skippedWords = 0;
        List<String> attemptedLemmas = new ArrayList<>();

        for (Map<String, Object> word : words) {
            String lemma = stringValue(word.get("lemma"));
            if (lemma.isBlank()) {
                skippedWords++;
                continue;
            }

            String reading = stringValue(word.get("reading"));
            attemptedLemmas.add(lemma);
            graphRepository.markJlptExampleBackfillAttempt(lemma, 0, "IN_PROGRESS");

            try {
                JakoLookupResult jako = jakoService.lookup(lemma, reading);
                lookedUpWords++;

                if (!jako.found()) {
                    graphRepository.markJlptExampleBackfillAttempt(lemma, 0, "NOT_FOUND");
                    skippedWords++;
                    continue;
                }

                int enqueuedForWord = 0;
                for (JakoLookupResult.Example example : jako.examples()) {
                    if (!isUsefulEdgeExample(example.textJa())) {
                        continue;
                    }
                    exampleQueueService.enqueue(example.textJa(), "EXAMPLE:JLPT_EDGE_BACKFILL");
                    enqueuedForWord++;
                }

                examplesEnqueued += enqueuedForWord;
                graphRepository.markJlptExampleBackfillAttempt(
                    lemma,
                    enqueuedForWord,
                    enqueuedForWord > 0 ? "ENQUEUED" : "NO_USEFUL_EXAMPLES"
                );
            } catch (RateLimitException | RetryableExternalServiceException | CircuitBreakerOpenException e) {
                log.warn("JLPT example edge backfill paused on '{}': {}", lemma, e.getMessage());
                graphRepository.markJlptExampleBackfillAttempt(lemma, 0, "PAUSED");
                return new JlptExampleBackfillResult(
                    lookedUpWords,
                    examplesEnqueued,
                    skippedWords,
                    List.copyOf(attemptedLemmas),
                    "paused",
                    e.getMessage()
                );
            } catch (Exception e) {
                log.warn("JLPT example edge backfill failed for '{}': {}", lemma, e.getMessage());
                graphRepository.markJlptExampleBackfillAttempt(lemma, 0, "FAILED");
                skippedWords++;
            }
        }

        return new JlptExampleBackfillResult(
            lookedUpWords,
            examplesEnqueued,
            skippedWords,
            List.copyOf(attemptedLemmas),
            "ok",
            null
        );
    }

    private List<String> normalizeJlptSources(String levelsCsv) {
        List<String> rawLevels = levelsCsv == null || levelsCsv.isBlank()
            ? List.of("N5", "N4", "N3", "N2", "N1")
            : Arrays.stream(levelsCsv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();

        List<String> sources = new ArrayList<>();
        for (String rawLevel : rawLevels) {
            String level = rawLevel.toUpperCase();
            if (!List.of("N5", "N4", "N3", "N2", "N1").contains(level)) {
                throw new IllegalArgumentException("Unsupported JLPT level: " + rawLevel);
            }
            sources.add("JLPT:" + level);
        }
        return List.copyOf(sources);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isUsefulEdgeExample(String textJa) {
        if (textJa == null) {
            return false;
        }
        String normalized = textJa.replaceAll("\\s+", "");
        if (normalized.length() < MIN_EXAMPLE_LENGTH) {
            return false;
        }
        return normalized.matches(".*[ぁ-んァ-ン].*");
    }
}
