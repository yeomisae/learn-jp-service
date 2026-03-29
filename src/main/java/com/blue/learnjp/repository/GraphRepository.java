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
 *   <li>pos         — 품사. 일본어 품사명 (名詞, 動詞, 形容詞 등)</li>
 *   <li>synonyms    — 일본어 유의어 (쉼표 구분)</li>
 *   <li>antonyms    — 일본어 반의어 (쉼표 구분)</li>
 *   <li>description — 한국어 설명 (한 줄)</li>
 *   <li>jlptLevel   — JLPT 급수 (N1~N5)</li>
 *   <li>bookmark    — 북마크 여부 (0 또는 1)</li>
 *   <li>image       — 이미지 URL</li>
 *   <li>createdAt   — 최초 생성 시각</li>
 *   <li>updatedAt   — 마지막 수정 시각</li>
 * </ul>
 */
@Repository
public class GraphRepository {

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
                          String synonyms, String antonyms, String description, String jlptLevel) {
        neo4jClient.query("""
            MERGE (w:Word {lemma: $lemma})
            ON CREATE SET w.surface = $surface,
                          w.reading = $reading,
                          w.meaning = $meaning,
                          w.pos = $pos,
                          w.synonyms = $synonyms,
                          w.antonyms = $antonyms,
                          w.description = $description,
                          w.jlptLevel = $jlptLevel,
                          w.bookmark = 0,
                          w.image = '',
                          w.createdAt = datetime()
            RETURN w.surface AS oldSurface, w.meaning AS oldMeaning,
                   w.synonyms AS oldSynonyms, w.antonyms AS oldAntonyms,
                   w.description AS oldDescription, w.jlptLevel AS oldJlptLevel
            """)
            .bind(surface).to("surface")
            .bind(lemma).to("lemma")
            .bind(reading).to("reading")
            .bind(meaning).to("meaning")
            .bind(pos).to("pos")
            .bind(synonyms).to("synonyms")
            .bind(antonyms).to("antonyms")
            .bind(description).to("description")
            .bind(jlptLevel).to("jlptLevel")
            .fetch().first()
            .ifPresent(row -> {
                String mergedSurface = mergeValues((String) row.get("oldSurface"), surface);
                String mergedMeaning = mergeValues((String) row.get("oldMeaning"), meaning);
                String mergedSynonyms = mergeValues((String) row.get("oldSynonyms"), synonyms);
                String mergedAntonyms = mergeValues((String) row.get("oldAntonyms"), antonyms);
                String mergedDescription = mergeValues((String) row.get("oldDescription"), description);
                // jlptLevel: 기존 값이 있으면 유지, 없으면 새 값 설정
                String oldJlpt = (String) row.get("oldJlptLevel");
                String finalJlpt = (oldJlpt != null && !oldJlpt.isEmpty()) ? oldJlpt : jlptLevel;

                neo4jClient.query("""
                    MATCH (w:Word {lemma: $lemma})
                    SET w.surface = $surface,
                        w.reading = $reading,
                        w.meaning = $meaning,
                        w.pos = $pos,
                        w.synonyms = $synonyms,
                        w.antonyms = $antonyms,
                        w.description = $description,
                        w.jlptLevel = $jlptLevel,
                        w.updatedAt = datetime()
                    """)
                    .bind(lemma).to("lemma")
                    .bind(reading).to("reading")
                    .bind(pos).to("pos")
                    .bind(mergedSurface).to("surface")
                    .bind(mergedMeaning).to("meaning")
                    .bind(mergedSynonyms).to("synonyms")
                    .bind(mergedAntonyms).to("antonyms")
                    .bind(mergedDescription).to("description")
                    .bind(finalJlpt).to("jlptLevel")
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
            WHERE w.pos IS NULL OR w.pos = ''
               OR w.meaning IS NULL OR w.meaning = ''
            RETURN w.lemma AS lemma, w.meaning AS meaning, w.pos AS pos,
                   w.synonyms AS synonyms, w.antonyms AS antonyms,
                   w.description AS description, w.jlptLevel AS jlptLevel
            LIMIT $limit
            """)
            .bind(limit).to("limit")
            .fetch().all().stream().toList();
    }

    /**
     * Word 노드의 품질 필드를 업데이트한다.
     */
    public void updateWordEnrichment(String lemma, String meaning, String pos,
                                     String synonyms, String antonyms, String description) {
        neo4jClient.query("""
            MATCH (w:Word {lemma: $lemma})
            SET w.meaning = $meaning,
                w.pos = $pos,
                w.synonyms = $synonyms,
                w.antonyms = $antonyms,
                w.description = $description,
                w.updatedAt = datetime()
            """)
            .bind(lemma).to("lemma")
            .bind(meaning).to("meaning")
            .bind(pos).to("pos")
            .bind(synonyms).to("synonyms")
            .bind(antonyms).to("antonyms")
            .bind(description).to("description")
            .run();
    }

    /**
     * 기존 Word 노드의 누적 필드를 reconcile된 최종값으로 덮어쓴다 (누적 아님).
     * reconcile 후 의미적 중복이 제거된 값을 직접 SET한다.
     */
    public void setWordFields(String lemma, String surface, String meaning, String pos,
                              String synonyms, String antonyms, String description) {
        neo4jClient.query("""
            MATCH (w:Word {lemma: $lemma})
            SET w.surface = $surface,
                w.meaning = $meaning,
                w.pos = $pos,
                w.synonyms = $synonyms,
                w.antonyms = $antonyms,
                w.description = $description,
                w.updatedAt = datetime()
            """)
            .bind(lemma).to("lemma")
            .bind(surface).to("surface")
            .bind(meaning).to("meaning")
            .bind(pos).to("pos")
            .bind(synonyms).to("synonyms")
            .bind(antonyms).to("antonyms")
            .bind(description).to("description")
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
}
