package com.blue.learnjp.repository;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.summary.ResultSummary;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GraphRepositoryTests {

    @Test
    void mergeWordInitializesBookmarkToNegativeThree() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.RecordFetchSpec<Map<String, Object>> fetch = mock(Neo4jClient.RecordFetchSpec.class);
        Neo4jClient.UnboundRunnableSpec query = mock(Neo4jClient.UnboundRunnableSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> bind = mock(Neo4jClient.OngoingBindSpec.class);

        when(query.bind(any())).thenReturn(bind);
        when(bind.to(anyString())).thenReturn(query);
        when(query.fetch()).thenReturn(fetch);
        when(fetch.first()).thenReturn(Optional.empty());
        when(neo4jClient.query(anyString())).thenReturn(query);

        GraphRepository repository = new GraphRepository(neo4jClient);

        repository.mergeWord("薬局", "薬局", "やっきょく", "약국", "명사", "", "", "", "", "", "JLPT:N4", 0, "[]", "dict");

        verify(neo4jClient).query(org.mockito.ArgumentMatchers.contains("w.bookmark = $initialBookmark"));
        verify(query).bind(GraphRepository.INITIAL_BOOKMARK);
    }

    @Test
    void upsertJakoWordUpdatesReadingForExistingWord() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.RecordFetchSpec<Map<String, Object>> sourceFetch = mock(Neo4jClient.RecordFetchSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.RecordFetchSpec<Map<String, Object>> existenceFetch = mock(Neo4jClient.RecordFetchSpec.class);

        Neo4jClient.UnboundRunnableSpec sourceQuery = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.UnboundRunnableSpec existenceQuery = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.UnboundRunnableSpec updateQuery = mock(Neo4jClient.UnboundRunnableSpec.class);

        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> sourceBind = mock(Neo4jClient.OngoingBindSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> existenceBind = mock(Neo4jClient.OngoingBindSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> updateBind = mock(Neo4jClient.OngoingBindSpec.class);

        when(existenceQuery.bind(any())).thenReturn(existenceBind);
        when(existenceBind.to(anyString())).thenReturn(existenceQuery);
        when(existenceQuery.fetch()).thenReturn(existenceFetch);
        when(existenceFetch.all()).thenReturn(java.util.List.of(Map.of("lemma", "食べる")));

        when(sourceQuery.bind(any())).thenReturn(sourceBind);
        when(sourceBind.to(anyString())).thenReturn(sourceQuery);
        when(sourceQuery.fetch()).thenReturn(sourceFetch);
        when(sourceFetch.first()).thenReturn(Optional.of(Map.of("oldSource", "MANUAL")));

        when(updateQuery.bind(any())).thenReturn(updateBind);
        when(updateBind.to(anyString())).thenReturn(updateQuery);
        when(updateQuery.run()).thenReturn(mock(ResultSummary.class));

        when(neo4jClient.query(anyString())).thenReturn(existenceQuery, sourceQuery, updateQuery);

        GraphRepository repository = new GraphRepository(neo4jClient);

        repository.upsertJakoWord(
            "食べる",
            "食べる",
            "食べる",
            "たべる",
            "먹다",
            "동사",
            "下一段他動詞",
            "하1단 타동사",
            "",
            "",
            "",
            "NEWS",
            1,
            "[]",
            "12345"
        );

        var queryCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(neo4jClient, times(3)).query(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues().get(2)).contains("w.reading = $reading");
        verify(updateQuery).bind("たべる");
    }

    @Test
    void upsertJakoWordPreservesExistingSourceWhenIncomingSourceIsBlank() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.RecordFetchSpec<Map<String, Object>> sourceFetch = mock(Neo4jClient.RecordFetchSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.RecordFetchSpec<Map<String, Object>> existenceFetch = mock(Neo4jClient.RecordFetchSpec.class);

        Neo4jClient.UnboundRunnableSpec sourceQuery = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.UnboundRunnableSpec existenceQuery = mock(Neo4jClient.UnboundRunnableSpec.class);
        Neo4jClient.UnboundRunnableSpec updateQuery = mock(Neo4jClient.UnboundRunnableSpec.class);

        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> sourceBind = mock(Neo4jClient.OngoingBindSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> existenceBind = mock(Neo4jClient.OngoingBindSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> updateBind = mock(Neo4jClient.OngoingBindSpec.class);

        when(existenceQuery.bind(any())).thenReturn(existenceBind);
        when(existenceBind.to(anyString())).thenReturn(existenceQuery);
        when(existenceQuery.fetch()).thenReturn(existenceFetch);
        when(existenceFetch.all()).thenReturn(java.util.List.of(Map.of("lemma", "食べる")));

        when(sourceQuery.bind(any())).thenReturn(sourceBind);
        when(sourceBind.to(anyString())).thenReturn(sourceQuery);
        when(sourceQuery.fetch()).thenReturn(sourceFetch);
        when(sourceFetch.first()).thenReturn(Optional.of(Map.of("oldSource", "MANUAL")));

        when(updateQuery.bind(any())).thenReturn(updateBind);
        when(updateBind.to(anyString())).thenReturn(updateQuery);
        when(updateQuery.run()).thenReturn(mock(ResultSummary.class));

        when(neo4jClient.query(anyString())).thenReturn(existenceQuery, sourceQuery, updateQuery);

        GraphRepository repository = new GraphRepository(neo4jClient);

        repository.upsertJakoWord(
            "食べる",
            "食べる",
            "食べる",
            "たべる",
            "먹다",
            "동사",
            "下一段他動詞",
            "하1단 타동사",
            "",
            "",
            "",
            "",
            1,
            "[]",
            "12345"
        );

        verify(updateQuery).bind("MANUAL");
    }

    @Test
    void adjustWordBookmarksUpdatesBookmarkByDelta() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.RecordFetchSpec<Map<String, Object>> countFetch = mock(Neo4jClient.RecordFetchSpec.class);
        Neo4jClient.UnboundRunnableSpec updateQuery = mock(Neo4jClient.UnboundRunnableSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> updateBind = mock(Neo4jClient.OngoingBindSpec.class);

        when(updateQuery.bind(any())).thenReturn(updateBind);
        when(updateBind.to(anyString())).thenReturn(updateQuery);
        when(updateQuery.fetch()).thenReturn(countFetch);
        when(countFetch.first()).thenReturn(Optional.of(Map.of("updatedCount", 2)));
        when(neo4jClient.query(anyString())).thenReturn(updateQuery);

        GraphRepository repository = new GraphRepository(neo4jClient);

        int updated = repository.adjustWordBookmarks(Map.of(
            "薬局", -1,
            "薬", 1
        ));

        assertThat(updated).isEqualTo(2);
        verify(neo4jClient).query(org.mockito.ArgumentMatchers.contains("w.bookmark = coalesce(w.bookmark, $initialBookmark) + update.delta"));
        var updatesCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(updateQuery).bind(updatesCaptor.capture());
        assertThat((List<Map<String, Object>>) updatesCaptor.getValue()).containsExactlyInAnyOrder(
            Map.of("lemma", "薬局", "delta", -1),
            Map.of("lemma", "薬", "delta", 1)
        );
        verify(updateQuery).bind(GraphRepository.INITIAL_BOOKMARK);
    }

    @Test
    void findQuizWordsBySourcesUsesBookmarkWeightedSampling() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.RecordFetchSpec<Map<String, Object>> fetch = mock(Neo4jClient.RecordFetchSpec.class);
        Neo4jClient.UnboundRunnableSpec query = mock(Neo4jClient.UnboundRunnableSpec.class);
        @SuppressWarnings("unchecked")
        Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> bind = mock(Neo4jClient.OngoingBindSpec.class);

        when(query.bind(any())).thenReturn(bind);
        when(bind.to(anyString())).thenReturn(query);
        when(query.fetch()).thenReturn(fetch);
        when(fetch.all()).thenReturn(List.of());
        when(neo4jClient.query(anyString())).thenReturn(query);

        GraphRepository repository = new GraphRepository(neo4jClient);

        repository.findQuizWordsBySources(
            List.of("JLPT:N5"),
            List.of("食べる"),
            3,
            true,
            true,
            true
        );

        verify(neo4jClient).query(org.mockito.ArgumentMatchers.contains("ORDER BY sampleKey"));
        verify(neo4jClient).query(org.mockito.ArgumentMatchers.contains("WHEN bookmarkValue <= -3 THEN 6.0"));
        verify(query).bind(GraphRepository.INITIAL_BOOKMARK);
    }
}
