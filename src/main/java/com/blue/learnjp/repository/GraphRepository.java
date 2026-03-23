package com.blue.learnjp.repository;

import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Neo4jClient를 사용한 Cypher 직접 실행 Repository.
 * 노드 MERGE + 엣지 CREATE 를 하나의 트랜잭션으로 처리한다.
 */
@Repository
public class GraphRepository {

    private final Neo4jClient neo4jClient;

    public GraphRepository(Neo4jClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    /**
     * 단어 노드를 MERGE한다. 있으면 갱신, 없으면 생성.
     */
    public void mergeWord(String surface, String lemma, String reading, String meaning, String pos) {
        neo4jClient.query("""
            MERGE (w:Word {lemma: $lemma})
            ON CREATE SET w.surface = $surface,
                          w.reading = $reading,
                          w.meaning = $meaning,
                          w.pos = $pos,
                          w.createdAt = datetime()
            ON MATCH SET  w.surface = $surface,
                          w.reading = $reading,
                          w.meaning = $meaning,
                          w.pos = $pos
            """)
            .bind(surface).to("surface")
            .bind(lemma).to("lemma")
            .bind(reading).to("reading")
            .bind(meaning).to("meaning")
            .bind(pos).to("pos")
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
