package com.blue.learnjp.service;

import com.blue.learnjp.config.NaverJakoConfig;
import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.http.CircuitBreakerOpenException;
import com.blue.learnjp.http.ResilientCallExecutor;
import com.blue.learnjp.http.RetryableExternalServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 네이버 일본어 사전(jako) 비공식 API를 호출하여 단어 메타데이터를 조회한다.
 * rate limit 제어를 위해 Semaphore + delay를 사용한다.
 */
@Service
public class NaverJakoDictionaryService {

    private static final Logger log = LoggerFactory.getLogger(NaverJakoDictionaryService.class);

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final String REFERER = "https://ja.dict.naver.com/";

    /** HTML ruby 태그에서 한자만 남기고 furigana(rt) 제거 */
    private static final Pattern RUBY_PATTERN = Pattern.compile("<ruby><rb>(.*?)</rb><rt>.*?</rt></ruby>");
    /** HTML 엔티티로 인코딩된 ruby 태그 */
    private static final Pattern RUBY_ENCODED_PATTERN = Pattern.compile("&lt;ruby&gt;&lt;rb&gt;(.*?)&lt;/rb&gt;&lt;rt&gt;.*?&lt;/rt&gt;&lt;/ruby&gt;");
    /** <span class="related_word"> 내부의 반의어 추출 (↔ 앞에 위치) */
    private static final Pattern ANTONYM_PATTERN = Pattern.compile("↔.*?<span class=\"related_word\"[^>]*>(.*?)</span>");
    /** 모든 HTML 태그 제거 */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    /** 활용형의 괄호 안 읽기 제거: 食(た)べます → 食べます */
    private static final Pattern CONJ_READING_PATTERN = Pattern.compile("\\([^)]+\\)");

    /** jako partOfSpeech(한국어) → 학습자 친화적 품사 설명 매핑 */
    private static final Map<String, String> POS_DESCRIPTION_MAP = Map.ofEntries(
        Map.entry("명사", "명사"),
        Map.entry("대명사", "대명사"),
        Map.entry("5단 자동사", "5단 자동사"),
        Map.entry("5단 타동사", "5단 타동사"),
        Map.entry("5단활용 자동사", "5단 자동사"),
        Map.entry("5단활용 타동사", "5단 타동사"),
        Map.entry("하1단 자동사", "하1단 자동사"),
        Map.entry("하1단 타동사", "하1단 타동사"),
        Map.entry("하1단활용 자동사", "하1단 자동사"),
        Map.entry("하1단활용 타동사", "하1단 타동사"),
        Map.entry("상1단 자동사", "상1단 자동사"),
        Map.entry("상1단 타동사", "상1단 타동사"),
        Map.entry("상1단활용 자동사", "상1단 자동사"),
        Map.entry("상1단활용 타동사", "상1단 타동사"),
        Map.entry("ス자동사", "する 자동사"),
        Map.entry("ス타동사", "する 타동사"),
        Map.entry("ダナ", "형용동사 (な형용사)"),
        Map.entry("형용사", "い형용사"),
        Map.entry("부사", "부사"),
        Map.entry("접속사", "접속사"),
        Map.entry("감동사", "감탄사"),
        Map.entry("연체사", "연체사"),
        Map.entry("조사", "조사"),
        Map.entry("조동사", "조동사"),
        Map.entry("접두사", "접두사"),
        Map.entry("접미사", "접미사")
    );

    private final NaverJakoConfig config;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final CloseableHttpClient httpClient;
    private final ResilientCallExecutor callExecutor;
    private volatile long lastRequestTime = 0;

    public NaverJakoDictionaryService(NaverJakoConfig config,
                                      ObjectMapper objectMapper,
                                      @Qualifier("naverJakoHttpClient") CloseableHttpClient httpClient,
                                      @Qualifier("naverJakoCallExecutor") ResilientCallExecutor callExecutor) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.semaphore = new Semaphore(config.maxConcurrent());
        this.httpClient = httpClient;
        this.callExecutor = callExecutor;
    }

    /** 괄호 및 괄호 내용 제거: "こす (みずを～)" → "こす", "こす（水を～）" → "こす" */
    private static final Pattern PAREN_SUFFIX_PATTERN = Pattern.compile("[\\s　]*[（(].*?[）)]\\s*$");
    /** 틸드 접두/접미: "～番", "故～" → "番", "故" */
    private static final Pattern TILDE_PATTERN = Pattern.compile("^～|～$");

    /**
     * 단어를 jako API로 조회한다. reading 없는 버전.
     */
    public JakoLookupResult lookup(String word) {
        return lookup(word, null);
    }

    /**
     * 단어를 jako API로 조회하여 메타데이터를 반환한다.
     * 원본 lemma로 못 찾으면 정제된 변형으로 재시도한다.
     * reading이 제공되면 마지막으로 reading으로 검색하되, 동음이의어 방지를 위해
     * expKanji가 원본 lemma와 일치하는 항목만 선택한다.
     */
    public JakoLookupResult lookup(String word, String reading) {
        try {
            semaphore.acquire();
            try {
                // 1차: 원본으로 시도
                enforceDelay();
                JakoLookupResult result = parseResponse(callApi(word), word, null);
                if (result.found()) return withResolvedLemma(result, word);

                // 2차: 정제된 변형들로 재시도
                for (String variant : generateVariants(word)) {
                    enforceDelay();
                    result = parseResponse(callApi(variant), variant, null);
                    if (result.found()) {
                        log.info("jako: found via variant '{}' (original: '{}')", variant, word);
                        return withResolvedLemma(result, variant);
                    }
                }

                // 3차: reading으로 재시도 (동음이의어 필터링 적용)
                if (reading != null && !reading.isEmpty() && !reading.equals(word)) {
                    Set<String> readingVariants = new LinkedHashSet<>();
                    String cleanReading = PAREN_SUFFIX_PATTERN.matcher(reading).replaceAll("").trim();
                    if (!cleanReading.isEmpty() && !cleanReading.equals(word)) {
                        readingVariants.add(cleanReading);
                    }
                    // する 제거 (サ変動詞: しょうたいする → しょうたい)
                    if (cleanReading.endsWith("する") && cleanReading.length() > 2) {
                        readingVariants.add(cleanReading.substring(0, cleanReading.length() - 2));
                    }
                    for (String rv : readingVariants) {
                        enforceDelay();
                        result = parseResponse(callApi(rv), rv, word);
                        if (result.found()) {
                            log.info("jako: found via reading '{}' (original: '{}')", rv, word);
                            // reading으로 찾은 경우: 원본 lemma(한자) 유지 (hiragana로 rename 방지)
                            return withResolvedLemma(result, word);
                        }
                    }
                }

                return JakoLookupResult.notFound();
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableExternalServiceException("jako lookup interrupted for word: " + word, e, false);
        } catch (RateLimitException | RetryableExternalServiceException | CircuitBreakerOpenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("jako lookup failed for word: {}", word, e);
            throw new RuntimeException("jako lookup failed for word: " + word, e);
        }
    }

    /**
     * 원본 lemma에서 정제된 검색 변형을 생성한다.
     * 원본과 동일한 변형은 제외한다.
     */
    List<String> generateVariants(String word) {
        Set<String> variants = new LinkedHashSet<>();

        // 세미콜론 분리: "そう; そうです" → ["そう", "そうです"]
        if (word.contains(";") || word.contains("；")) {
            for (String part : word.split("[;；]")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !trimmed.equals(word)) {
                    variants.add(trimmed);
                }
            }
        }

        // 틸드 제거: "～番" → "番", "故～" → "故"
        String noTilde = TILDE_PATTERN.matcher(word).replaceAll("").trim();
        if (!noTilde.isEmpty() && !noTilde.equals(word)) {
            variants.add(noTilde);
        }

        // 괄호 제거: "こす (みずを～)" → "こす"
        String noParen = PAREN_SUFFIX_PATTERN.matcher(word).replaceAll("").trim();
        if (!noParen.isEmpty() && !noParen.equals(word)) {
            variants.add(noParen);
        }

        // する 제거: "びっくりする" → "びっくり", "チェックする" → "チェック"
        if (word.endsWith("する") && word.length() > 2) {
            variants.add(word.substring(0, word.length() - 2));
        }

        // な/に 접미사 제거: "おおまかな" → "おおまか", "徐々に" → "徐々"
        if (word.endsWith("な") && word.length() > 1) {
            variants.add(word.substring(0, word.length() - 1));
        }
        if (word.endsWith("に") && word.length() > 1) {
            variants.add(word.substring(0, word.length() - 1));
        }

        // お/ご 접두사 제거: "お金持ち" → "金持ち", "ご主人" → "主人"
        if (word.startsWith("お") && word.length() > 2) {
            variants.add(word.substring(1));
        }
        if (word.startsWith("ご") && word.length() > 2) {
            variants.add(word.substring(1));
        }

        // 々 전개: "時々" → "時時", "色々" → "色色"
        if (word.contains("々") && word.length() >= 2) {
            StringBuilder expanded = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                if (word.charAt(i) == '々' && i > 0) {
                    expanded.append(word.charAt(i - 1));
                } else {
                    expanded.append(word.charAt(i));
                }
            }
            String expandedStr = expanded.toString();
            if (!expandedStr.equals(word)) {
                variants.add(expandedStr);
            }
        }

        return new ArrayList<>(variants);
    }

    private synchronized void enforceDelay() throws InterruptedException {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestTime;
        if (elapsed < config.delayMs()) {
            Thread.sleep(config.delayMs() - elapsed);
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private String callApi(String word) {
        String encoded = URLEncoder.encode(word, StandardCharsets.UTF_8);
        String url = config.baseUrl() + "?query=" + encoded + "&m=pc&range=word";
        return callExecutor.execute("dictionary.lookup", () -> executeGet(url, word));
    }

    private String executeGet(String url, String word) throws IOException {
        HttpGet request = new HttpGet(url);
        request.setHeader("User-Agent", USER_AGENT);
        request.setHeader("Referer", REFERER);

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getCode();
            String payload = readEntity(response);

            if (status == 429) {
                throw new RateLimitException("jako rate limit reached for word '" + word + "'");
            }
            if (status == 408 || status >= 500) {
                throw new RetryableExternalServiceException(
                    "jako returned retryable status " + status + " for word '" + word + "'",
                    false
                );
            }
            if (status != 200) {
                throw new IllegalStateException("jako API returned " + status + " for word: " + word);
            }

            return payload;
        }
    }

    private String readEntity(CloseableHttpResponse response) throws IOException {
        if (response.getEntity() == null) {
            return "";
        }
        return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * @param originalLemma reading 기반 검색 시 동음이의어 필터링용 원본 lemma. null이면 필터링 없음.
     */
    JakoLookupResult parseResponse(String responseBody, String queryWord, String originalLemma) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode items = root.path("searchResultMap")
                .path("searchResultListMap")
                .path("WORD")
                .path("items");

        if (!items.isArray() || items.isEmpty()) {
            log.debug("jako: no items for word '{}'", queryWord);
            return JakoLookupResult.notFound();
        }

        // exact match 수집
        List<JsonNode> exactMatches = new ArrayList<>();
        for (JsonNode candidate : items) {
            if ("exact:entry".equals(candidate.path("matchType").asText(""))) {
                exactMatches.add(candidate);
            }
        }

        if (exactMatches.isEmpty()) {
            log.debug("jako: no exact match for word '{}'", queryWord);
            return JakoLookupResult.notFound();
        }

        // 동음이의어 필터: originalLemma(reading 검색 시)가 있으면 expKanji 매칭 필수
        JsonNode item;
        if (originalLemma != null) {
            item = null;
            String originalKanji = extractKanji(originalLemma);
            for (JsonNode candidate : exactMatches) {
                String expKanjiRaw = candidate.path("expKanji").asText("");
                if (!expKanjiRaw.isEmpty()) {
                    // expKanji 정제: <strong> 태그 제거, () 안 선택적 문자 제거
                    // 예: "落(ち)着く∙落(ち)付く" → ["落着く", "落付く"]
                    String cleaned = expKanjiRaw.replaceAll("</?strong>", "");
                    String[] kanjiVariants = cleaned.split("[·∙]");

                    for (String kanjiForm : kanjiVariants) {
                        // () 제거: "落(ち)着く" → "落着く" (선택적 송리가나 제거)
                        String normalized = kanjiForm.replaceAll("\\([^)]*\\)", "").trim();
                        // () 포함 전개: "落(ち)着く" → "落ち着く"
                        String expanded = kanjiForm.replaceAll("[()]", "").trim();

                        // 1차: 문자열 포함 관계 (정제 후)
                        if (normalized.contains(originalLemma) || originalLemma.contains(normalized)
                                || expanded.contains(originalLemma) || originalLemma.contains(expanded)) {
                            item = candidate;
                            log.debug("jako: selected homonym '{}' matching original '{}'", cleaned, originalLemma);
                            break;
                        }
                        // 2차: 한자 부분만 비교 (송리가나 차이 허용)
                        if (!originalKanji.isEmpty()) {
                            String candidateKanji = extractKanji(expanded);
                            if (!candidateKanji.isEmpty() && candidateKanji.equals(originalKanji)) {
                                item = candidate;
                                log.debug("jako: selected homonym '{}' by kanji match '{}' for '{}'", cleaned, candidateKanji, originalLemma);
                                break;
                            }
                        }
                    }
                    if (item != null) break;
                }
            }
            if (item == null) {
                // expKanji 매칭 실패 → 잘못된 동음이의어 방지, skip
                log.debug("jako: reading '{}' found but no kanji match for '{}', skipping", queryWord, originalLemma);
                return JakoLookupResult.notFound();
            }
        } else {
            item = exactMatches.get(0);
        }

        // 기본 필드
        String reading = HTML_TAG_PATTERN.matcher(item.path("expEntry").asText("")).replaceAll("");
        String dictEntryId = item.path("entryId").asText("");
        int starGrade = item.path("priority").asInt(0);

        // meansCollector 파싱
        JsonNode meansCollectors = item.path("meansCollector");
        List<String> posRawList = new ArrayList<>();
        List<String> posDetailList = new ArrayList<>();
        List<String> posDescList = new ArrayList<>();
        List<String> meanings = new ArrayList<>();
        Set<String> antonyms = new LinkedHashSet<>();
        List<JakoLookupResult.Example> examples = new ArrayList<>();

        if (meansCollectors.isArray()) {
            for (JsonNode mc : meansCollectors) {
                String mcPosRaw = mc.path("partOfSpeech").asText("").strip();
                String mcPosDetail = mc.path("partOfSpeech2").asText("").strip();
                // partOfSpeech는 쉼표 구분 복합 가능 ("명사, ス타동사")
                for (String part : mcPosRaw.split(",")) {
                    String trimmed = part.strip();
                    if (!trimmed.isEmpty()) {
                        if (!posRawList.contains(trimmed)) posRawList.add(trimmed);
                        String desc = POS_DESCRIPTION_MAP.get(trimmed);
                        if (desc != null && !posDescList.contains(desc)) posDescList.add(desc);
                    }
                }
                if (!mcPosDetail.isEmpty() && !posDetailList.contains(mcPosDetail)) posDetailList.add(mcPosDetail);

                JsonNode means = mc.path("means");
                if (means.isArray()) {
                    for (JsonNode m : means) {
                        String order = m.path("order").asText("");
                        String value = m.path("value").asText("");

                        if (!value.isEmpty()) {
                            // 반의어 추출
                            extractAntonyms(value, antonyms);
                            // HTML 제거 후 meaning에 추가
                            String cleanValue = HTML_TAG_PATTERN.matcher(value).replaceAll("").trim();
                            if (!cleanValue.isEmpty()) {
                                meanings.add(cleanValue);
                            }
                        }

                        // 예문 추출
                        String exOri = m.path("exampleOri").asText("");
                        String exTrans = m.path("exampleTrans").asText("");
                        if (!exOri.isEmpty()) {
                            String cleanJa = stripFurigana(exOri);
                            if (!cleanJa.isEmpty()) {
                                examples.add(new JakoLookupResult.Example(cleanJa, exTrans));
                            }
                        }
                    }
                }
            }
        }

        // 활용형
        String conjugations = parseConjugations(item.path("expAliasEntrySearchList"));

        String pos = String.join(", ", posRawList);
        String posDetail = String.join(", ", posDetailList);
        String posDesc = String.join(", ", posDescList);
        String meaning = String.join(", ", meanings);
        String antonymStr = String.join(",", antonyms);

        log.info("jako: found '{}' → reading={}, pos={}, meanings={}, starGrade={}, examples={}",
                queryWord, reading, pos, meanings.size(), starGrade, examples.size());

        return new JakoLookupResult(
                true, "", reading, meaning, pos, posDetail, posDesc, antonymStr,
                starGrade, conjugations, dictEntryId, examples
        );
    }

    /** resolvedLemma를 채운 새 결과를 반환한다. */
    private JakoLookupResult withResolvedLemma(JakoLookupResult r, String lemma) {
        return new JakoLookupResult(
                r.found(), lemma, r.reading(), r.meaning(), r.pos(), r.posDetail(),
                r.posDesc(), r.antonyms(), r.starGrade(), r.conjugations(),
                r.dictEntryId(), r.examples()
        );
    }

    /**
     * 문자열에서 CJK 한자(漢字)만 추출한다.
     * 송리가나 차이 비교용: "落ちる" → "落", "落る" → "落"
     */
    private static String extractKanji(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void extractAntonyms(String value, Set<String> antonyms) {
        Matcher matcher = ANTONYM_PATTERN.matcher(value);
        while (matcher.find()) {
            String ant = matcher.group(1).trim();
            if (!ant.isEmpty()) {
                antonyms.add(ant);
            }
        }
    }

    /**
     * furigana HTML을 제거하고 한자만 남긴다.
     * &lt;ruby&gt; 형태와 <ruby> 형태 모두 처리.
     */
    String stripFurigana(String text) {
        if (text == null || text.isEmpty()) return "";
        // 먼저 HTML 엔티티 인코딩된 ruby 처리
        String result = RUBY_ENCODED_PATTERN.matcher(text).replaceAll("$1");
        // 일반 ruby 태그 처리
        result = RUBY_PATTERN.matcher(result).replaceAll("$1");
        // 남은 HTML 태그 제거
        result = HTML_TAG_PATTERN.matcher(result).replaceAll("");
        // HTML 엔티티 디코딩
        result = result.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        // 혹시 남은 태그 한번 더 제거
        result = HTML_TAG_PATTERN.matcher(result).replaceAll("");
        return result.trim();
    }

    private String parseConjugations(JsonNode conjList) {
        if (!conjList.isArray() || conjList.isEmpty()) return "[]";

        List<String> entries = new ArrayList<>();
        for (JsonNode conj : conjList) {
            String type = conj.path("conjType").asText("");
            String value = conj.path("conjValue").asText("");
            // 괄호 안 읽기 제거: 食(た)べます → 食べます
            value = CONJ_READING_PATTERN.matcher(value).replaceAll("");
            entries.add("{\"type\":\"" + escapeJson(type) + "\",\"value\":\"" + escapeJson(value) + "\"}");
        }

        return "[" + String.join(",", entries) + "]";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
