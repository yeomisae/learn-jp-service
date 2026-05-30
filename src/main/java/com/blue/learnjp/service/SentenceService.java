package com.blue.learnjp.service;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.repository.GraphRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 문장 입력 파이프라인의 핵심 서비스.
 * 1. OpenClaw로 문장 분석 (단어 분리 + co-occurrence)
 * 2. 각 단어에 대해 jako API 조회 (meaning, pos, reading 등)
 * 3. 노드 MERGE
 * 4. 엣지 CREATE
 * 5. jako 예문 → 비동기 큐 적재 (depth=0일 때만)
 * 6. Google Sheets 동기화 (설정 시)
 */
@Service
public class SentenceService {

    private static final Logger log = LoggerFactory.getLogger(SentenceService.class);
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("[0-9０-９]+\\.?[0-9０-９]*");
    private static final Pattern ASCII_ONLY = Pattern.compile("^[A-Za-z0-9]+$");

    private final OpenClawService openClawService;
    private final NaverJakoDictionaryService jakoService;
    private final GraphRepository graphRepository;
    private final Optional<GoogleSheetsService> googleSheetsService;
    private final ExampleQueueService exampleQueueService;

    public SentenceService(OpenClawService openClawService,
                           NaverJakoDictionaryService jakoService,
                           GraphRepository graphRepository,
                           Optional<GoogleSheetsService> googleSheetsService,
                           ExampleQueueService exampleQueueService) {
        this.openClawService = openClawService;
        this.jakoService = jakoService;
        this.graphRepository = graphRepository;
        this.googleSheetsService = googleSheetsService;
        this.exampleQueueService = exampleQueueService;
    }

    /**
     * 문장을 분석하고 저장한다 (외부 호출용, depth=0).
     */
    @Transactional
    public AnalysisResult process(String sentence, String source) {
        return process(sentence, source, 0);
    }

    /**
     * 문장을 분석하고 저장한다.
     * @param depth 0=원본 문장 (예문 큐 적재), 1=예문 (큐 적재 안 함)
     */
    @Transactional
    public AnalysisResult process(String sentence, String source, int depth) {
        if (graphRepository.sentenceExists(sentence)) {
            log.info("Sentence already registered, skipping: {}", sentence);
            return new AnalysisResult(List.of(), List.of());
        }

        AnalysisResult result = openClawService.analyze(sentence);
        log.info("Analysis result - words: {}, edges: {} (depth={})", result.words().size(), result.edges().size(), depth);
        saveAndSync(sentence, result, source, depth);
        return result;
    }

    @Transactional
    public AnalysisResult importResult(String sentence, AnalysisResult result, String source) {
        if (sentence != null && !sentence.isBlank() && graphRepository.sentenceExists(sentence)) {
            log.info("Sentence already registered, skipping: {}", sentence);
            return new AnalysisResult(List.of(), List.of());
        }

        log.info("Importing pre-analyzed result - words: {}, edges: {}", result.words().size(), result.edges().size());
        saveAndSync(sentence, result, source, 0);
        return result;
    }

    private void saveAndSync(String sentence, AnalysisResult result, String source, int depth) {
        if (sentence != null && !sentence.isBlank()) {
            graphRepository.createSentence(sentence);
        }

        Map<String, String> lemmaMap = saveWords(result.words(), source, depth);

        for (AnalysisResult.EdgeInfo edge : result.edges()) {
            String from = lemmaMap.getOrDefault(edge.from(), edge.from());
            String to = lemmaMap.getOrDefault(edge.to(), edge.to());
            graphRepository.createCoOccursEdge(from, to, sentence, edge.pattern());
        }

        if (depth == 0) {
            googleSheetsService.ifPresent(sheets -> {
                try {
                    sheets.exportWords();
                } catch (Exception e) {
                    log.warn("Google Sheets sync failed (non-blocking): {}", e.getMessage());
                }
            });
        }
    }

    /**
     * 단어를 jako API로 직접 조회하여 등록한다. LLM 불필요.
     * edge 없이 노드만 생성한다.
     * @return jako 조회 결과 (found=false이면 등록 실패)
     */
    public JakoLookupResult registerWord(String word, String source) {
        if (ASCII_ONLY.matcher(word).matches()) {
            log.info("Rejecting non-Japanese word: '{}'", word);
            return JakoLookupResult.notFound();
        }
        JakoLookupResult jako = jakoService.lookup(word);

        if (jako.found()) {
            String lemma = jako.resolvedLemma();
            graphRepository.upsertJakoWord(
                word, lemma, lemma,
                jako.reading(), jako.meaning(), jako.pos(), jako.posDetail(), jako.posDesc(),
                "", jako.antonyms(), "",
                source, jako.starGrade(), jako.conjugations(), jako.dictEntryId()
            );

            // 예문 큐 적재
            if (!jako.examples().isEmpty()) {
                String exampleSource = source != null && !source.isEmpty() ? source : "EXAMPLE";
                for (JakoLookupResult.Example ex : jako.examples()) {
                    exampleQueueService.enqueue(ex.textJa(), exampleSource);
                }
                log.info("Word '{}' registered (resolvedLemma={}), {} examples enqueued", word, lemma, jako.examples().size());
            } else {
                log.info("Word '{}' registered (resolvedLemma={})", word, lemma);
            }

            googleSheetsService.ifPresent(sheets -> {
                try {
                    sheets.exportWords();
                } catch (Exception e) {
                    log.warn("Google Sheets sync failed (non-blocking): {}", e.getMessage());
                }
            });
        } else {
            log.info("Word '{}' not found in jako, not registered", word);
        }

        return jako;
    }

    /**
     * 분석 결과의 단어를 저장한다.
     * jako found → upsertJakoWord (rename + 덮어쓰기/생성 통합)
     * jako miss → mergeWord fallback (LLM 데이터). 숫자 포함 시 n-word 변환.
     * @return lemma 매핑 (원본 → 실제 저장된 lemma). edge 생성 시 참조용.
     */
    private Map<String, String> saveWords(List<AnalysisResult.WordInfo> words, String source, int depth) {
        Map<String, String> lemmaMap = new HashMap<>();
        if (words.isEmpty()) return lemmaMap;

        List<JakoLookupResult.Example> allExamples = new ArrayList<>();

        for (AnalysisResult.WordInfo w : words) {
            if (ASCII_ONLY.matcher(w.lemma()).matches()) {
                log.debug("Skipping non-Japanese word: '{}'", w.lemma());
                continue;
            }
            JakoLookupResult jako = jakoService.lookup(w.lemma(), w.reading());

            if (jako.found()) {
                graphRepository.upsertJakoWord(
                    w.lemma(), jako.resolvedLemma(), w.surface(),
                    jako.reading(), jako.meaning(), jako.pos(), jako.posDetail(), jako.posDesc(),
                    "", jako.antonyms(), "",
                    source, jako.starGrade(), jako.conjugations(), jako.dictEntryId()
                );

                if (!jako.resolvedLemma().equals(w.lemma())) {
                    lemmaMap.put(w.lemma(), jako.resolvedLemma());
                }

                if (depth == 0 && !jako.examples().isEmpty()) {
                    allExamples.addAll(jako.examples());
                }

                log.info("Word '{}' saved (resolvedLemma={}, starGrade={}, examples={})",
                        w.lemma(), jako.resolvedLemma(), jako.starGrade(), jako.examples().size());
            } else {
                // jako NOT_FOUND + 숫자 포함 → n-word 변환 후 단위 부분으로 jako 재검색
                List<String> nwords = toNWords(w.lemma());
                boolean isConverted = !(nwords.size() == 1 && nwords.get(0).equals(w.lemma()));
                if (isConverted) {
                    lemmaMap.put(w.lemma(), nwords.get(0));
                    for (String nword : nwords) {
                        saveNWord(nword, w.lemma(), source);
                    }
                } else {
                    // jako NOT_FOUND + n-word 아님 → LLM decompose 시도
                    List<AnalysisResult.WordInfo> parts = openClawService.decompose(w.lemma());
                    if (!parts.isEmpty()) {
                        log.info("Decomposed '{}' → {} parts, saving each", w.lemma(), parts.size());
                        // 첫 번째 파트로 lemma 매핑 (edge 정합성)
                        lemmaMap.put(w.lemma(), parts.get(0).lemma());
                        for (AnalysisResult.WordInfo part : parts) {
                            JakoLookupResult partJako = jakoService.lookup(part.lemma(), part.reading());
                            if (partJako.found()) {
                                graphRepository.upsertJakoWord(
                                    part.lemma(), partJako.resolvedLemma(), part.surface(),
                                    partJako.reading(), partJako.meaning(), partJako.pos(), partJako.posDetail(), partJako.posDesc(),
                                    "", partJako.antonyms(), "",
                                    source, partJako.starGrade(), partJako.conjugations(), partJako.dictEntryId()
                                );
                                if (depth == 0 && !partJako.examples().isEmpty()) {
                                    allExamples.addAll(partJako.examples());
                                }
                                log.info("Decomposed part '{}' saved (jako found, resolved={})", part.lemma(), partJako.resolvedLemma());
                            } else {
                                // 분해된 파트도 jako 못 찾으면 → n-word 시도, 그래도 안 되면 LLM 데이터로 저장
                                List<String> partNwords = toNWords(part.lemma());
                                boolean partConverted = !(partNwords.size() == 1 && partNwords.get(0).equals(part.lemma()));
                                if (partConverted) {
                                    for (String nw : partNwords) {
                                        saveNWord(nw, part.lemma(), source);
                                    }
                                } else {
                                    graphRepository.mergeWord(
                                        part.surface(), part.lemma(), part.reading(), part.meaning(), part.pos(),
                                        "", "", "", "", "",
                                        source, 0, "[]", ""
                                    );
                                    log.info("Decomposed part '{}' saved with LLM fallback", part.lemma());
                                }
                            }
                        }
                    } else {
                        graphRepository.mergeWord(
                            w.surface(), w.lemma(), w.reading(), w.meaning(), w.pos(),
                            "", "", w.synonyms(), w.antonyms(), w.description(),
                            source, 0, "[]", ""
                        );
                        log.info("Word '{}' saved with LLM fallback (decompose failed)", w.lemma());
                    }
                }
            }
        }

        if (!allExamples.isEmpty()) {
            String exampleSource = source != null && !source.isEmpty() ? source : "EXAMPLE";
            for (JakoLookupResult.Example ex : allExamples) {
                exampleQueueService.enqueue(ex.textJa(), exampleSource);
            }
            log.info("Enqueued {} examples for background processing", allExamples.size());
        }

        return lemmaMap;
    }

    /**
     * 숫자를 포함한 단어를 n-word 리스트로 변환한다.
     * jako NOT_FOUND인 경우에만 호출한다.
     * 예: "365日" → ["n-日"], "48時間" → ["n-時間"], "8時40分" → ["n-時", "n-分"]
     * 숫자가 없으면 원본 그대로 단일 요소 리스트 반환.
     */
    private static final Pattern NWORD_LLM_PATTERN = Pattern.compile("^n[^a-zA-Zぁ-ん]");

    static List<String> toNWords(String lemma) {
        // LLM이 이미 "n時", "n円" 형태로 반환한 경우 → "n-時", "n-円"로 정규화
        // (n + 한자/カタカナ/기호 패턴만 대상, "なんなり" 등 히라가나 단어는 제외)
        if (lemma.startsWith("n") && !lemma.startsWith("n-") && lemma.length() > 1
                && NWORD_LLM_PATTERN.matcher(lemma).find()
                && !NUMERIC_PATTERN.matcher(lemma).find()) {
            return List.of("n-" + lemma.substring(1));
        }
        if (!NUMERIC_PATTERN.matcher(lemma).find()) {
            return List.of(lemma);
        }
        // 숫자+비숫자 쌍 추출: "8時40分" → [("8","時"), ("40","分")]
        // "150人以上" → [("150","人以上")]  → 이후 splitUnit에서 "人"+"以上"으로 분리
        java.util.regex.Matcher m = Pattern.compile(
            "([0-9０-９]+\\.?[0-9０-９]*)([^0-9０-９]+)").matcher(lemma);
        List<String> result = new ArrayList<>();
        while (m.find()) {
            String unitPart = m.group(2).trim();
            if (!unitPart.isEmpty()) {
                result.add("n-" + unitPart);
            }
        }
        if (result.isEmpty()) {
            String unit = NUMERIC_PATTERN.matcher(lemma).replaceAll("").trim();
            result.add(unit.isEmpty() ? lemma : "n-" + unit);
        }
        return result;
    }

    /**
     * n-word를 저장한다. 단위 부분으로 jako lookup하여 메타데이터를 채운다.
     * 단위가 jako에 없으면 NOT_FOUND로 저장한다.
     * (단위 분리는 LLM이 담당해야 할 영역이므로 여기서는 시도하지 않는다)
     */
    private void saveNWord(String nword, String originalLemma, String source) {
        String unit = nword.replaceAll("^n-", "");
        if (unit.isEmpty()) {
            graphRepository.mergeWord(nword, nword, "", "", "", "", "", "", "", "", source, 0, "[]", "NOT_FOUND");
            return;
        }

        JakoLookupResult unitJako = jakoService.lookup(unit);
        if (unitJako.found()) {
            graphRepository.upsertJakoWord(
                nword, nword, nword,
                unitJako.reading(), unitJako.meaning(), unitJako.pos(), unitJako.posDetail(), unitJako.posDesc(),
                "", unitJako.antonyms(), "",
                source, unitJako.starGrade(), unitJako.conjugations(), unitJako.dictEntryId()
            );
            log.info("Word '{}' → n-word '{}' (jako unit '{}' found)", originalLemma, nword, unit);
        } else {
            graphRepository.mergeWord(nword, nword, "", "", "", "", "", "", "", "", source, 0, "[]", "NOT_FOUND");
            log.info("Word '{}' → n-word '{}' (jako NOT_FOUND)", originalLemma, nword);
        }
    }
}
