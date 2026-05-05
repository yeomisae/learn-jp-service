package com.blue.learnjp.repository;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.summary.ResultSummary;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GraphRepositoryTests {

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
}
