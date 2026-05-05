package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.repository.GraphRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class QuizServiceTests {

    @Test
    void createWordSetUsesDefaultJlptStrategyAndFilters() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        when(graphRepository.findQuizWordsBySources(
            List.of("JLPT:N5", "JLPT:N4", "JLPT:N3", "JLPT:N2", "JLPT:N1"),
            List.of(),
            3,
            true,
            true,
            true
        )).thenReturn(List.of(
            Map.of(
                "lemma", "食べる",
                "reading", "たべる",
                "meaning", "먹다",
                "pos", "동사",
                "posDetail", "下一段他動詞",
                "posDesc", "하1단 타동사",
                "source", "JLPT:N5",
                "starGrade", 1,
                "dictEntryId", "dict-1"
            )
        ));

        QuizService quizService = new QuizService(graphRepository);

        QuizWordSetResponse response = quizService.createWordSet(new QuizWordSetRequest(
            null, null, null, null, null, null, null
        ));

        assertThat(response.strategyUsed()).isEqualTo("random_jlpt");
        assertThat(response.requestedCount()).isEqualTo(3);
        assertThat(response.returnedCount()).isEqualTo(1);
        assertThat(response.words()).containsExactly(
            new QuizWordSetResponse.QuizWord(
                "食べる", "たべる", "먹다", "동사", "下一段他動詞", "하1단 타동사", "JLPT:N5", 1, "dict-1"
            )
        );
    }

    @Test
    void createWordSetNormalizesLevelsAndExcludeLemmas() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        when(graphRepository.findQuizWordsBySources(anyList(), anyList(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
            .thenReturn(List.of());

        QuizService quizService = new QuizService(graphRepository);

        quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt",
            List.of("n5", "N4", "N4"),
            2,
            List.of(" 食べる ", "", "行く"),
            false,
            true,
            false
        ));

        verify(graphRepository).findQuizWordsBySources(
            List.of("JLPT:N5", "JLPT:N4"),
            List.of("食べる", "行く"),
            2,
            false,
            true,
            false
        );
    }

    @Test
    void createWordSetRejectsUnsupportedStrategy() {
        QuizService quizService = new QuizService(mock(GraphRepository.class));

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "connected", List.of("N5"), 3, List.of(), true, true, true
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported strategy");
    }

    @Test
    void createWordSetRejectsUnsupportedLevel() {
        QuizService quizService = new QuizService(mock(GraphRepository.class));

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt", List.of("N0"), 3, List.of(), true, true, true
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported level");
    }
}
