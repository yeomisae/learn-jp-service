package com.blue.learnjp.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Neo4jClient를 사용한 Cypher 직접 실행 Repository.
 * 노드 MERGE + 엣지 CREATE 를 하나의 트랜잭션으로 처리한다.
 *
 * <h3>Word 노드 스키마</h3>
 * <ul>
 *   <li>lemma       — 사전형(기본형). 활용형이 아닌 원형 (예: 食べる, 高い). PK 역할</li>
 *   <li>surface     — 문장에서 실제 등장한 표기 (예: 食べました, 高かった)</li>
 *   <li>reading     — 히라가나 읽기 (예: たべる)</li>
 *   <li>meaning     — 한국어 뜻 (예: 먹다)</li>
 *   <li>pos         — 품사 한국어 원본 (jako partOfSpeech: 5단활용 타동사, ダナ 등)</li>
 *   <li>posDetail   — 품사 일본어 원본 (jako partOfSpeech2: 五段活用他動詞, ダナ 등)</li>
 *   <li>posDesc     — 품사 매핑 설명 (학습자 친화적: 5단 타동사, 형용동사 (な형용사) 등)</li>
 *   <li>synonyms    — 일본어 유의어 (쉼표 구분)</li>
 *   <li>antonyms    — 일본어 반의어 (쉼표 구분)</li>
 *   <li>description — 한국어 설명 (한 줄)</li>
 *   <li>bookmark    — 학습 상태 점수 (기본 -3, 음수: 헷갈림, 0: 미표시, 양수: 익숙함)</li>
 *   <li>image       — 이미지 URL</li>
 *   <li>source      — 등록 출처 (쉼표 구분 누적: JLPT, NEWS, MANUAL, ANIME 등)</li>
 *   <li>starGrade   — 빈도 기반 난이도 (0~2+, 네이버 사전 priority)</li>
 *   <li>conjugations — 활용형 JSON 배열 (예: [{"type":"ます형","value":"食べます"}])</li>
 *   <li>dictEntryId — 네이버 사전 엔트리 ID</li>
 *   <li>createdAt   — 최초 생성 시각</li>
 *   <li>updatedAt   — 마지막 수정 시각</li>
 * </ul>
 */
@Repository
public class GraphRepository {

    static final int INITIAL_BOOKMARK = -3;

    private final Neo4jClient neo4jClient;

    public GraphRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 단어 노드를 MERGE한다. 없으면 생성, 있으면 누적 필드는 쉼표 구분으로 합침(중복 제거).
     * 누적 필드: surface, meaning, synonyms, antonyms, description
     * APOC 없이 Java에서 병합 처리.
     */
    public void mergeWord(String surface, String lemma, String reading, String meaning, String pos,
                          String posDetail, String posDesc, String synonyms, String antonyms,
                          String description, String source, int starGrade, String conjugations,
                          String dictEntryId) {
        neo4jClient.query("""
            MERGE (w:Word {lemma: $lemma})
            ON CREATE SET w.surface = $surface,
                          w.reading = $reading,
                          w.meaning = $meaning,
                          w.pos = $pos,
                          w.posDetail = $posDetail,
                          w.posDesc = $posDesc,
                          w.synonyms = $synonyms,
                          w.antonyms = $antonyms,
                          w.description = $description,
                          w.source = $source,
                          w.starGrade = $starGrade,
                          w.conjugations = $conjugations,
                          w.dictEntryId = $dictEntryId,
                          w.bookmark = $initialBookmark,
                          w.image = '',
                          w.createdAt = datetime()
            RETURN w.surface AS oldSurface, w.meaning AS oldMeaning,
                   w.synonyms AS oldSynonyms, w.antonyms AS oldAntonyms,
                   w.description AS oldDescription, w.source AS oldSource
            """)
            .bind(surface).to("surface")
            .bind(lemma).to("lemma")
            .bind(reading).to("reading")
            .bind(meaning).to("meaning")
            .bind(pos).to("pos")
            .bind(posDetail != null ? posDetail : "").to("posDetail")
            .bind(posDesc != null ? posDesc : "").to("posDesc")
            .bind(synonyms).to("synonyms")
            .bind(antonyms).to("antonyms")
            .bind(description).to("description")
            .bind(source != null ? source : "").to("source")
            .bind(starGrade).to("starGrade")
            .bind(conjugations != null ? conjugations : "[]").to("conjugations")
            .bind(dictEntryId != null ? dictEntryId : "").to("dictEntryId")
            .bind(INITIAL_BOOKMARK).to("initialBookmark")
            .fetch().first()
            .ifPresent(row -> {
                String mergedSurface = mergeValues((String) row.get("oldSurface"), surface);
                String mergedMeaning = mergeValues((String) row.get("oldMeaning"), meaning);
                String mergedSynonyms = mergeValues((String) row.get("oldSynonyms"), synonyms);
                String mergedAntonyms = mergeValues((String) row.get("oldAntonyms"), antonyms);
                String mergedDescription = mergeValues((String) row.get("oldDescription"), description);
                String mergedSource = mergeValues((String) row.get("oldSource"), source);

                neo4jClient.query("""
                    MATCH (w:Word {lemma: $lemma})
                    SET w.surface = $surface,
                        w.reading = $reading,
                        w.meaning = $meaning,
                        w.pos = $pos,
                        w.posDetail = $posDetail,
                        w.posDesc = $posDesc,
                        w.synonyms = $synonyms,
                        w.antonyms = $antonyms,
                        w.description = $description,
                        w.source = $source,
                        w.starGrade = $starGrade,
                        w.conjugations = $conjugations,
                        w.dictEntryId = $dictEntryId,
                        w.updatedAt = datetime()
                    """)
                    .bind(lemma).to("lemma")
                    .bind(reading).to("reading")
                    .bind(pos).to("pos")
                    .bind(posDetail != null ? posDetail : "").to("posDetail")
                    .bind(posDesc != null ? posDesc : "").to("posDesc")
                    .bind(mergedSurface).to("surface")
                    .bind(mergedMeaning).to("meaning")
                    .bind(mergedSynonyms).to("synonyms")
                    .bind(mergedAntonyms).to("antonyms")
                    .bind(mergedDescription).to("description")
                    .bind(mergedSource).to("source")
                    .bind(starGrade).to("starGrade")
                    .bind(conjugations != null ? conjugations : "[]").to("conjugations")
                    .bind(dictEntryId != null ? dictEntryId : "").to("dictEntryId")
                    .run();
            });
    }

    private String mergeValues(String existing, String incoming) {
        Set<String> values = new LinkedHashSet<>();
        if (existing != null) {
            for (String v : existing.split(",")) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }
        }
        if (incoming != null) {
            for (String v : incoming.split(",")) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) values.add(trimmed);
            }
        }
        return String.join(",", values);
    }

    /**
     * 품질 보완이 필요한 Word 노드를 조회한다.
     */
    public List<Map<String, Object>> findWordsNeedingEnrichment(int limit) {
        return neo4jClient.query("""
            MATCH (w:Word)
            WHERE w.dictEntryId IS NULL OR w.dictEntryId = ''
            RETURN w.lemma AS lemma, w.reading AS reading, w.meaning AS meaning, w.pos AS pos,
                   w.synonyms AS synonyms, w.antonyms AS antonyms,
                   w.description AS description
            LIMIT $limit
            """)
            .bind(limit).to("limit")
            .fetch().all().stream().toList();
    }

    /**
     * jako 예문 기반 edge backfill 대상 JLPT 단어를 조회한다.
     * 이미 시도한 단어는 제외하여 작은 limit로 반복 실행해도 같은 단어를 계속 고르지 않는다.
     */
    public List<Map<String, Object>> findJlptWordsNeedingExampleBackfill(List<String> sources, int limit) {
        return neo4jClient.query("""
            MATCH (w:Word)
            WITH w,
                 [src IN split(coalesce(w.source, ''), ',') | trim(src)] AS wordSources
            WHERE ANY(source IN $sources WHERE source IN wordSources)
              AND w.jlptExampleBackfilledAt IS NULL
              AND coalesce(w.jlptExampleBackfillStatus, '') = ''
              AND coalesce(w.dictEntryId, '') <> 'NOT_FOUND'
            RETURN w.lemma AS lemma,
                   w.reading AS reading,
                   w.source AS source,
                   coalesce(w.dictEntryId, '') AS dictEntryId
            ORDER BY rand()
            LIMIT $limit
            """)
            .bind(sources).to("sources")
            .bind(limit).to("limit")
            .fetch().all().stream().toList();
    }

    /**
     * JLPT 예문 edge backfill 시도 결과를 Word 노드에 기록한다.
     */
    public void markJlptExampleBackfillAttempt(String lemma, int exampleCount, String status) {
        neo4jClient.query("""
            MATCH (w:Word {lemma: $lemma})
            SET w.jlptExampleBackfillStartedAt = CASE
                    WHEN $status = 'IN_PROGRESS' THEN datetime()
                    ELSE coalesce(w.jlptExampleBackfillStartedAt, datetime())
                END,
                w.jlptExampleBackfilledAt = CASE
                    WHEN $status = 'IN_PROGRESS' THEN w.jlptExampleBackfilledAt
                    ELSE datetime()
                END,
                w.jlptExampleBackfillExampleCount = $exampleCount,
                w.jlptExampleBackfillStatus = $status,
                w.updatedAt = datetime()
            """)
            .bind(lemma).to("lemma")
            .bind(exampleCount).to("exampleCount")
            .bind(status != null ? status : "").to("status")
            .run();
    }

    /**
     * 기존 Word 노드의 누적 필드를 reconcile된 최종값으로 덮어쓴다 (누적 아님).
     * reconcile 후 의미적 중복이 제거된 값을 직접 SET한다.
     */
    public void setWordFields(String lemma, String surface, String reading, String meaning, String pos,
                              String posDetail, String posDesc, String synonyms, String antonyms,
                              String description, String source, int starGrade, String conjugations,
                              String dictEntryId) {
        // source가 비어 있으면 기존값 유지, 값이 있으면 기존값과 merge
        String mergedSource = source != null ? source : "";
        var row = neo4jClient.query("MATCH (w:Word {lemma: $lemma}) RETURN w.source AS oldSource")
            .bind(lemma).to("lemma")
            .fetch().first().orElse(null);
        if (row != null) {
            String oldSource = (String) row.get("oldSource");
            if (source == null || source.isEmpty()) {
                mergedSource = oldSource != null ? oldSource : "";
            } else {
                mergedSource = mergeValues(oldSource, source);
            }
        }

        neo4jClient.query("""
            MATCH (w:Word {lemma: $lemma})
            SET w.surface = $surface,
                w.reading = $reading,
                w.meaning = $meaning,
                w.pos = $pos,
                w.posDetail = $posDetail,
                w.posDesc = $posDesc,
                w.synonyms = $synonyms,
                w.antonyms = $antonyms,
                w.description = $description,
                w.source = $source,
                w.starGrade = $starGrade,
                w.conjugations = $conjugations,
                w.dictEntryId = $dictEntryId,
                w.updatedAt = datetime()
            """)
            .bind(lemma).to("lemma")
            .bind(surface).to("surface")
            .bind(reading).to("reading")
            .bind(meaning).to("meaning")
            .bind(pos).to("pos")
            .bind(posDetail != null ? posDetail : "").to("posDetail")
            .bind(posDesc != null ? posDesc : "").to("posDesc")
            .bind(synonyms).to("synonyms")
            .bind(antonyms).to("antonyms")
            .bind(description).to("description")
            .bind(mergedSource != null ? mergedSource : "").to("source")
            .bind(starGrade).to("starGrade")
            .bind(conjugations != null ? conjugations : "[]").to("conjugations")
            .bind(dictEntryId != null ? dictEntryId : "").to("dictEntryId")
            .run();
    }

    /**
     * 주어진 lemma 목록에 해당하는 기존 Word 노드의 누적 필드를 조회한다.
     * reconcile(의미적 중복 제거) 시 기존 데이터 참조용.
     */
    public Map<String, Map<String, Object>> findWordsByLemmas(List<String> lemmas) {
        Collection<Map<String, Object>> rows = neo4jClient.query("""
            MATCH (w:Word) WHERE w.lemma IN $lemmas
            RETURN w.lemma AS lemma, w.meaning AS meaning, w.pos AS pos,
                   w.synonyms AS synonyms, w.antonyms AS antonyms,
                   w.description AS description, w.surface AS surface
            """)
            .bind(lemmas).to("lemmas")
            .fetch().all();

        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String lemma = (String) row.get("lemma");
            result.put(lemma, row);
        }
        return result;
    }

    /**
     * 퀴즈용 단어 세트를 조회한다.
     * source는 "JLPT:N5" 같은 exact token 기준으로 필터링한다.
     */
    public List<Map<String, Object>> findQuizWordsBySources(List<String> sources, List<String> excludeLemmas,
                                                            int limit, boolean requireReading,
                                                            boolean requireMeaning, boolean requireDictEntry) {
        return neo4jClient.query("""
            MATCH (w:Word)
            WITH w,
                 [src IN split(coalesce(w.source, ''), ',') | trim(src)] AS wordSources,
                 coalesce(w.bookmark, $initialBookmark) AS bookmarkValue
            WHERE ANY(source IN $sources WHERE source IN wordSources)
              AND (size($excludeLemmas) = 0 OR NOT w.lemma IN $excludeLemmas)
              AND (NOT $requireReading OR trim(coalesce(w.reading, '')) <> '')
              AND (NOT $requireMeaning OR trim(coalesce(w.meaning, '')) <> '')
              AND (NOT $requireDictEntry OR (
                    trim(coalesce(w.dictEntryId, '')) <> ''
                    AND coalesce(w.dictEntryId, '') <> 'NOT_FOUND'
                  ))
            WITH w, bookmarkValue,
                 CASE
                   WHEN bookmarkValue <= -3 THEN 6.0
                   WHEN bookmarkValue = -2 THEN 5.0
                   WHEN bookmarkValue = -1 THEN 4.0
                   WHEN bookmarkValue = 0 THEN 3.0
                   WHEN bookmarkValue = 1 THEN 2.0
                   ELSE 1.0
                 END AS weight
            WITH w, weight, rand() AS r
            WITH w, -log(CASE WHEN r = 0 THEN 0.000001 ELSE r END) / weight AS sampleKey
            RETURN w.lemma AS lemma,
                   w.reading AS reading,
                   w.meaning AS meaning,
                   w.pos AS pos,
                   w.posDetail AS posDetail,
                   w.posDesc AS posDesc,
                   w.source AS source,
                   w.starGrade AS starGrade,
                   w.dictEntryId AS dictEntryId
            ORDER BY sampleKey
            LIMIT $limit
            """)
            .bind(sources).to("sources")
            .bind(excludeLemmas).to("excludeLemmas")
            .bind(requireReading).to("requireReading")
            .bind(requireMeaning).to("requireMeaning")
            .bind(requireDictEntry).to("requireDictEntry")
            .bind(INITIAL_BOOKMARK).to("initialBookmark")
            .bind(limit).to("limit")
            .fetch().all().stream().toList();
    }

    /**
     * bookmark 점수를 bulk 증감한다.
     * delta는 -1 또는 1을 기대하지만, 서비스에서 합산된 임의 정수도 허용한다.
     */
    public int adjustWordBookmarks(Map<String, Integer> bookmarkDeltas) {
        if (bookmarkDeltas == null || bookmarkDeltas.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> updates = new ArrayList<>();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(bookmarkDeltas.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Integer> entry : entries) {
            updates.add(Map.of(
                "lemma", entry.getKey(),
                "delta", entry.getValue()
            ));
        }

        return neo4jClient.query("""
            UNWIND $updates AS update
            MATCH (w:Word {lemma: update.lemma})
            SET w.bookmark = coalesce(w.bookmark, $initialBookmark) + update.delta,
                w.updatedAt = datetime()
            RETURN count(w) AS updatedCount
            """)
            .bind(updates).to("updates")
            .bind(INITIAL_BOOKMARK).to("initialBookmark")
            .fetch().first()
            .map(row -> ((Number) row.get("updatedCount")).intValue())
            .orElse(0);
    }

    /**
     * 문장이 이미 등록되어 있는지 확인한다.
     */
    public boolean sentenceExists(String text) {
        return neo4jClient.query("MATCH (s:Sentence {text: $text}) RETURN s")
            .bind(text).to("text")
            .fetch().first().isPresent();
    }

    /**
     * 문장 노드를 생성한다.
     */
    public void createSentence(String text) {
        neo4jClient.query("CREATE (s:Sentence {text: $text, createdAt: datetime()})")
            .bind(text).to("text")
            .run();
    }

    // ── ExampleQueue 노드 ──

    /**
     * 예문을 큐에 적재한다. textJa 기준 MERGE로 중복 방지.
     */
    public void enqueueExample(String textJa, String source) {
        neo4jClient.query("""
            MERGE (eq:ExampleQueue {textJa: $textJa})
            ON CREATE SET eq.source = $source,
                          eq.status = 'PENDING',
                          eq.retryCount = 0,
                          eq.lastError = '',
                          eq.nextAttemptAt = datetime(),
                          eq.createdAt = datetime()
            """)
            .bind(textJa).to("textJa")
            .bind(source != null ? source : "").to("source")
            .run();
    }

    /**
     * PENDING 상태의 예문을 limit개 조회한다.
     */
    public List<Map<String, Object>> fetchPendingExamples(int limit) {
        return new ArrayList<>(neo4jClient.query("""
            MATCH (eq:ExampleQueue)
            WHERE eq.status = 'PENDING'
              AND (eq.nextAttemptAt IS NULL OR eq.nextAttemptAt <= datetime())
            RETURN eq.textJa AS textJa,
                   eq.source AS source,
                   coalesce(eq.retryCount, 0) AS retryCount
            ORDER BY coalesce(eq.nextAttemptAt, eq.createdAt), eq.createdAt
            LIMIT $limit
            """)
            .bind(limit).to("limit")
            .fetch().all());
    }

    /**
     * 예문 큐 상태를 변경한다.
     */
    public void updateExampleQueueStatus(String textJa, String status) {
        updateExampleQueueStatus(textJa, status, null);
    }

    /**
     * 예문 큐 상태를 변경하고, 실패 사유가 있으면 lastError에 기록한다.
     */
    public void updateExampleQueueStatus(String textJa, String status, String lastError) {
        neo4jClient.query("""
            MATCH (eq:ExampleQueue {textJa: $textJa})
            SET eq.status = $status,
                eq.updatedAt = datetime(),
                eq.nextAttemptAt = null,
                eq.lastError = CASE
                    WHEN $status = 'DONE' THEN ''
                    WHEN trim($lastError) <> '' THEN $lastError
                    ELSE eq.lastError
                END
            """)
            .bind(textJa).to("textJa")
            .bind(status).to("status")
            .bind(lastError != null ? lastError : "").to("lastError")
            .run();
    }

    /**
     * 예문 큐 재시도를 exponential backoff로 예약한다.
     */
    public void scheduleExampleRetry(String textJa, int retryCount, long delayMs, String lastError) {
        long delaySeconds = Math.max(1L, (long) Math.ceil(delayMs / 1000.0));

        neo4jClient.query("""
            MATCH (eq:ExampleQueue {textJa: $textJa})
            SET eq.status = 'PENDING',
                eq.retryCount = $retryCount,
                eq.lastError = $lastError,
                eq.nextAttemptAt = datetime() + duration({seconds: $delaySeconds}),
                eq.updatedAt = datetime()
            """)
            .bind(textJa).to("textJa")
            .bind(retryCount).to("retryCount")
            .bind(lastError != null ? lastError : "").to("lastError")
            .bind(delaySeconds).to("delaySeconds")
            .run();
    }

    /**
     * PENDING 예문 수를 반환한다.
     */
    public long countPendingExamples() {
        return neo4jClient.query("MATCH (eq:ExampleQueue {status: 'PENDING'}) RETURN count(eq) AS cnt")
            .fetch().first()
            .map(row -> ((Number) row.get("cnt")).longValue())
            .orElse(0L);
    }

    /**
     * 일시 중단된 예문 큐 항목을 다시 PENDING으로 전환한다.
     */
    public int resumePausedExamples(String source, int limit) {
        return neo4jClient.query("""
            MATCH (eq:ExampleQueue {status: 'PAUSED'})
            WHERE $source = '' OR eq.source = $source
            WITH eq
            ORDER BY eq.updatedAt, eq.createdAt
            LIMIT $limit
            SET eq.status = 'PENDING',
                eq.nextAttemptAt = datetime(),
                eq.updatedAt = datetime()
            RETURN count(eq) AS resumedCount
            """)
            .bind(source != null ? source : "").to("source")
            .bind(limit).to("limit")
            .fetch().first()
            .map(row -> ((Number) row.get("resumedCount")).intValue())
            .orElse(0);
    }

    /**
     * 두 단어 사이에 CO_OCCURS 엣지를 CREATE한다.
     * 같은 쌍이라도 문장마다 별도 엣지를 생성한다.
     */
    public void createCoOccursEdge(String fromLemma, String toLemma, String sentence, String pattern) {
        neo4jClient.query("""
            MATCH (a:Word {lemma: $fromLemma}), (b:Word {lemma: $toLemma})
            CREATE (a)-[:CO_OCCURS {
                sentence: $sentence,
                pattern: $pattern,
                createdAt: datetime()
            }]->(b)
            """)
            .bind(fromLemma).to("fromLemma")
            .bind(toLemma).to("toLemma")
            .bind(sentence).to("sentence")
            .bind(pattern).to("pattern")
            .run();
    }

    /**
     * jako API에서 매칭 실패한 단어의 dictEntryId를 NOT_FOUND로 마킹한다.
     * enrich 시 반복 조회 방지용.
     */
    public void markDictNotFound(String lemma) {
        neo4jClient.query("MATCH (w:Word {lemma: $lemma}) SET w.dictEntryId = 'NOT_FOUND'")
            .bind(lemma).to("lemma")
            .run();
    }

    /**
     * Word 노드의 lemma를 변경한다.
     * 대상 lemma가 이미 존재하면 기존 노드의 edge를 대상에 이전하고 원본을 삭제한다.
     */
    /**
     * jako 조회 결과로 Word 노드를 생성/갱신한다.
     * - resolvedLemma != originalLemma 이면 기존 노드를 rename
     * - 기존 노드 존재 시 setWordFields (덮어쓰기)
     * - 신규 노드 시 mergeWord (생성)
     */
    public void upsertJakoWord(String originalLemma, String resolvedLemma, String surface,
                               String reading, String meaning, String pos, String posDetail,
                               String posDesc, String synonyms, String antonyms, String description,
                               String source, int starGrade, String conjugations, String dictEntryId) {
        // lemma 정제 시 기존 노드 rename
        if (!resolvedLemma.equals(originalLemma)) {
            boolean oldExists = !findWordsByLemmas(List.of(originalLemma)).isEmpty();
            if (oldExists) {
                renameLemma(originalLemma, resolvedLemma);
            }
        }

        // 존재 여부에 따라 덮어쓰기 or 생성
        boolean exists = !findWordsByLemmas(List.of(resolvedLemma)).isEmpty();
        if (exists) {
            setWordFields(resolvedLemma, surface, reading, meaning, pos, posDetail, posDesc,
                          synonyms, antonyms, description, source, starGrade, conjugations, dictEntryId);
        } else {
            mergeWord(surface, resolvedLemma, reading, meaning, pos, posDetail, posDesc,
                      synonyms, antonyms, description, source, starGrade, conjugations, dictEntryId);
        }
    }

    public void renameLemma(String oldLemma, String newLemma) {
        // 대상 lemma가 이미 존재하는지 확인
        boolean targetExists = neo4jClient.query("MATCH (w:Word {lemma: $lemma}) RETURN w")
            .bind(newLemma).to("lemma")
            .fetch().first().isPresent();

        if (targetExists) {
            // outgoing edges 이전
            neo4jClient.query("""
                MATCH (src:Word {lemma: $oldLemma})-[r:CO_OCCURS]->(t)
                WITH src, r, t
                MATCH (dst:Word {lemma: $newLemma})
                WHERE t <> dst
                CREATE (dst)-[:CO_OCCURS {sentence: r.sentence, pattern: r.pattern, createdAt: r.createdAt}]->(t)
                """)
                .bind(oldLemma).to("oldLemma")
                .bind(newLemma).to("newLemma")
                .run();
            // incoming edges 이전
            neo4jClient.query("""
                MATCH (s)-[r:CO_OCCURS]->(src:Word {lemma: $oldLemma})
                WITH src, r, s
                MATCH (dst:Word {lemma: $newLemma})
                WHERE s <> dst
                CREATE (s)-[:CO_OCCURS {sentence: r.sentence, pattern: r.pattern, createdAt: r.createdAt}]->(dst)
                """)
                .bind(oldLemma).to("oldLemma")
                .bind(newLemma).to("newLemma")
                .run();
            // 원본 노드와 모든 edge 삭제
            neo4jClient.query("MATCH (w:Word {lemma: $lemma}) DETACH DELETE w")
                .bind(oldLemma).to("lemma")
                .run();
        } else {
            // 단순 rename
            neo4jClient.query("MATCH (w:Word {lemma: $oldLemma}) SET w.lemma = $newLemma")
                .bind(oldLemma).to("oldLemma")
                .bind(newLemma).to("newLemma")
                .run();
        }
    }
}
