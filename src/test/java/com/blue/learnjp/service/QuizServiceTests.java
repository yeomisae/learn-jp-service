package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.JakoLookupResult;
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
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
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

        QuizService quizService = new QuizService(graphRepository, jakoService);

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
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findQuizWordsBySources(anyList(), anyList(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
            .thenReturn(List.of());

        QuizService quizService = new QuizService(graphRepository, jakoService);

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
        QuizService quizService = new QuizService(mock(GraphRepository.class), mock(NaverJakoDictionaryService.class));

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "connected", List.of("N5"), 3, List.of(), true, true, true
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported strategy");
    }

    @Test
    void createWordSetRejectsUnsupportedLevel() {
        QuizService quizService = new QuizService(mock(GraphRepository.class), mock(NaverJakoDictionaryService.class));

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt", List.of("N0"), 3, List.of(), true, true, true
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported level");
    }

    @Test
    void updateBookmarksAppliesWrongAndCorrectDeltas() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findWordsByLemmas(List.of("薬局", "薬")))
            .thenReturn(Map.of(
                "薬局", Map.of("lemma", "薬局"),
                "薬", Map.of("lemma", "薬")
            ));
        when(graphRepository.adjustWordBookmarks(Map.of(
            "薬局", -1,
            "薬", 1
        ))).thenReturn(2);

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizBookmarkUpdateResponse response = quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("薬局", "薬"),
            List.of(" 薬局 ", ""),
            List.of("薬")
        ));

        assertThat(response.updatedCount()).isEqualTo(2);
        assertThat(response.appliedDeltas()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "薬局", -1,
            "薬", 1
        ));
        assertThat(response.resolvedMappings()).isEmpty();
        assertThat(response.ignoredLemmas()).isEmpty();
        assertThat(response.missingLemmas()).isEmpty();
        assertThat(response.status()).isEqualTo("ok");
    }

    @Test
    void updateBookmarksCancelsOverlappingLemmaDeltas() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.adjustWordBookmarks(Map.of())).thenReturn(0);

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizBookmarkUpdateResponse response = quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("薬局"),
            List.of("薬局"),
            List.of("薬局")
        ));

        assertThat(response.updatedCount()).isEqualTo(0);
        assertThat(response.appliedDeltas()).isEmpty();
        assertThat(response.resolvedMappings()).isEmpty();
        assertThat(response.ignoredLemmas()).isEmpty();
        assertThat(response.missingLemmas()).isEmpty();
        verify(graphRepository).adjustWordBookmarks(Map.of());
    }

    @Test
    void updateBookmarksIgnoresNoiseOutsideTargetSet() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(jakoService.generateVariants("画面")).thenReturn(List.of());
        when(jakoService.lookup("画面")).thenReturn(JakoLookupResult.notFound());
        when(graphRepository.findWordsByLemmas(List.of("背中")))
            .thenReturn(Map.of("背中", Map.of("lemma", "背中")));
        when(graphRepository.adjustWordBookmarks(Map.of("背中", 1))).thenReturn(1);

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizBookmarkUpdateResponse response = quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("撮る", "残る", "背中"),
            List.of("画面"),
            List.of("背中")
        ));

        assertThat(response.updatedCount()).isEqualTo(1);
        assertThat(response.appliedDeltas()).containsExactlyEntriesOf(Map.of("背中", 1));
        assertThat(response.ignoredLemmas()).containsExactly("画面");
        assertThat(response.missingLemmas()).isEmpty();
    }

    @Test
    void updateBookmarksResolvesLemmaIntoTargetSetViaJako() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(jakoService.generateVariants("食べます")).thenReturn(List.of());
        when(jakoService.lookup("食べます")).thenReturn(new JakoLookupResult(
            true, "食べる", "たべる", "먹다", "동사", "", "", "", 0, "[]", "dict", List.of()
        ));
        when(graphRepository.findWordsByLemmas(List.of("食べる")))
            .thenReturn(Map.of("食べる", Map.of("lemma", "食べる")));
        when(graphRepository.adjustWordBookmarks(Map.of("食べる", -1))).thenReturn(1);

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizBookmarkUpdateResponse response = quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("食べる"),
            List.of("食べます"),
            List.of()
        ));

        assertThat(response.updatedCount()).isEqualTo(1);
        assertThat(response.appliedDeltas()).containsExactlyEntriesOf(Map.of("食べる", -1));
        assertThat(response.resolvedMappings()).containsExactlyEntriesOf(Map.of("食べます", "食べる"));
        assertThat(response.ignoredLemmas()).isEmpty();
        assertThat(response.missingLemmas()).isEmpty();
    }

    @Test
    void updateBookmarksReportsMissingTargetLemmasWhenResolvedButAbsentInDb() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findWordsByLemmas(List.of("薬局"))).thenReturn(Map.of());
        when(graphRepository.adjustWordBookmarks(Map.of())).thenReturn(0);

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizBookmarkUpdateResponse response = quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("薬局"),
            List.of("薬局"),
            List.of()
        ));

        assertThat(response.updatedCount()).isEqualTo(0);
        assertThat(response.appliedDeltas()).isEmpty();
        assertThat(response.missingLemmas()).containsExactly("薬局");
    }
}
