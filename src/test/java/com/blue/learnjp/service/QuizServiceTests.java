package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.dto.QuizTurnRequest;
import com.blue.learnjp.dto.QuizTurnResponse;
import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.repository.GraphRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class QuizServiceTests {

    @Test
    void createWordSetUsesDefaultJlptStrategyAndSelectsRequiredWord() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findQuizTargetBySources(
            List.of("JLPT:N5", "JLPT:N4", "JLPT:N3", "JLPT:N2", "JLPT:N1"),
            List.of(),
            true,
            true,
            true
        )).thenReturn(Optional.of(Map.of(
            "wordId", "word-1",
            "lemma", "食べる",
            "reading", "たべる",
            "meaning", "먹다",
            "pos", "동사",
            "posDetail", "下一段他動詞",
            "posDesc", "하1단 타동사",
            "source", "JLPT:N5",
            "starGrade", 1,
            "dictEntryId", "dict-1"
        )));
        when(graphRepository.findQuizCandidateWordsByEdge(
            eq("食べる"),
            eq(List.of("JLPT:N5", "JLPT:N4", "JLPT:N3", "JLPT:N2", "JLPT:N1")),
            eq(List.of("食べる")),
            eq(4),
            eq(true),
            eq(true),
            eq(true)
        )).thenReturn(List.of(
            Map.of(
                "wordId", "word-2",
                "lemma", "どうも",
                "reading", "どうも",
                "meaning", "아무래도",
                "pos", "부사",
                "posDetail", "副詞",
                "posDesc", "부사",
                "source", "JLPT:N5",
                "starGrade", 0,
                "dictEntryId", "dict-2"
            )
        ));
        when(graphRepository.findQuizWordsBySources(
            eq(List.of("JLPT:N5", "JLPT:N4", "JLPT:N3", "JLPT:N2", "JLPT:N1")),
            eq(List.of("食べる", "どうも")),
            eq(3),
            eq(true),
            eq(true),
            eq(true)
        )).thenReturn(List.of(
            Map.of(
                "wordId", "word-3",
                "lemma", "辞書",
                "reading", "じしょ",
                "meaning", "사전",
                "pos", "명사",
                "posDetail", "名詞",
                "posDesc", "명사",
                "source", "JLPT:N5",
                "starGrade", 0,
                "dictEntryId", "dict-3"
            )
        ));

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizWordSetResponse response = quizService.createWordSet(new QuizWordSetRequest(
            null, null, null, null, null, null, null
        ));

        assertThat(response.strategyUsed()).isEqualTo("random_jlpt");
        assertThat(response.requestedCount()).isEqualTo(5);
        assertThat(response.returnedCount()).isEqualTo(3);
        assertThat(response.requiredWord()).isEqualTo(
            new QuizWordSetResponse.QuizWord(
                "word-1", "食べる", "たべる", "먹다", "동사", "下一段他動詞", "하1단 타동사", "JLPT:N5", 1, "dict-1"
            )
        );
        assertThat(response.candidateWords()).containsExactly(
            new QuizWordSetResponse.QuizWord(
                "word-2", "どうも", "どうも", "아무래도", "부사", "副詞", "부사", "JLPT:N5", 0, "dict-2"
            ),
            new QuizWordSetResponse.QuizWord(
                "word-3", "辞書", "じしょ", "사전", "명사", "名詞", "명사", "JLPT:N5", 0, "dict-3"
            )
        );
        assertThat(response.allowDropCandidates()).isTrue();
        assertThat(response.maxCandidateWordsToUse()).isEqualTo(1);
        assertThat(response.maxExtraContentWords()).isEqualTo(2);
        assertThat(response.words()).hasSize(3);
    }

    @Test
    void createWordSetNormalizesLevelsAndExcludeLemmas() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findQuizTargetBySources(anyList(), anyList(), anyBoolean(), anyBoolean(), anyBoolean()))
            .thenReturn(Optional.empty());

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

        verify(graphRepository).findQuizTargetBySources(
            List.of("JLPT:N5", "JLPT:N4"),
            List.of("食べる", "行く"),
            false,
            true,
            false
        );
    }

    @Test
    void createWordSetUsesEdgeCandidatesBeforeRandomFallback() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findQuizTargetBySources(anyList(), anyList(), anyBoolean(), anyBoolean(), anyBoolean()))
            .thenReturn(Optional.of(Map.of(
                "lemma", "辞書",
                "reading", "じしょ",
                "meaning", "사전",
                "pos", "명사",
                "posDetail", "名詞",
                "posDesc", "명사",
                "source", "JLPT:N5",
                "starGrade", 1,
                "dictEntryId", "dict-2"
            )));
        when(graphRepository.findQuizCandidateWordsByEdge(
            eq("辞書"),
            eq(List.of("JLPT:N5")),
            eq(List.of("辞書")),
            eq(2),
            eq(true),
            eq(true),
            eq(true)
        ))
            .thenReturn(List.of(
                Map.of(
                    "lemma", "友達",
                    "reading", "ともだち",
                    "meaning", "친구",
                    "pos", "명사",
                    "posDetail", "名詞",
                    "posDesc", "명사",
                    "source", "JLPT:N5",
                    "starGrade", 0,
                    "dictEntryId", "dict-1"
                )
            ));
        when(graphRepository.findQuizWordsBySources(
            eq(List.of("JLPT:N5")),
            eq(List.of("辞書", "友達")),
            eq(1),
            eq(true),
            eq(true),
            eq(true)
        )).thenReturn(List.of(
                Map.of(
                    "lemma", "どうも",
                    "reading", "どうも",
                    "meaning", "아무래도",
                    "pos", "부사",
                    "posDetail", "副詞",
                    "posDesc", "부사",
                    "source", "JLPT:N5",
                    "starGrade", 0,
                    "dictEntryId", "dict-3"
                )
            ));

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizWordSetResponse response = quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt", List.of("N5"), 3, List.of(), true, true, true
        ));

        assertThat(response.requiredWord().lemma()).isEqualTo("辞書");
        assertThat(response.candidateWords()).extracting(QuizWordSetResponse.QuizWord::lemma)
            .containsExactly("友達", "どうも");
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
            ))
            .thenReturn(Map.of(
                "薬局", Map.of("wordId", "word-pharmacy", "lemma", "薬局", "reading", "やっきょく", "source", "JLPT:N5", "meaning", "약국", "bookmark", -4),
                "薬", Map.of("wordId", "word-medicine", "lemma", "薬", "reading", "くすり", "source", "JLPT:N5", "meaning", "약", "bookmark", -2)
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
        assertThat(response.targetResults()).containsExactly(
            new QuizBookmarkUpdateResponse.TargetResult("word-pharmacy", "薬局", "やっきょく", "JLPT:N5", "약국", "wrong", -4),
            new QuizBookmarkUpdateResponse.TargetResult("word-medicine", "薬", "くすり", "JLPT:N5", "약", "correct", -2)
        );
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
        assertThat(response.targetResults()).containsExactly(
            new QuizBookmarkUpdateResponse.TargetResult("", "薬局", "", "", "", "unchanged", null)
        );
        verify(graphRepository).adjustWordBookmarks(Map.of());
    }

    @Test
    void updateBookmarksIgnoresNoiseOutsideTargetSet() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(jakoService.generateVariants("画面")).thenReturn(List.of());
        when(jakoService.lookup("画面")).thenReturn(JakoLookupResult.notFound());
        when(graphRepository.findWordsByLemmas(List.of("背中")))
            .thenReturn(Map.of("背中", Map.of("lemma", "背中")))
            .thenReturn(Map.of("背中", Map.of("lemma", "背中", "bookmark", -1)));
        when(graphRepository.findWordsByLemmas(List.of("撮る", "残る", "背中")))
            .thenReturn(Map.of("背中", Map.of("lemma", "背中", "bookmark", -1)));
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
        assertThat(response.targetResults()).containsExactly(
            new QuizBookmarkUpdateResponse.TargetResult("", "撮る", "", "", "", "unchanged", null),
            new QuizBookmarkUpdateResponse.TargetResult("", "残る", "", "", "", "unchanged", null),
            new QuizBookmarkUpdateResponse.TargetResult("", "背中", "", "", "", "correct", -1)
        );
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
            .thenReturn(Map.of("食べる", Map.of("lemma", "食べる")))
            .thenReturn(Map.of("食べる", Map.of("lemma", "食べる", "bookmark", -4)));
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
        assertThat(response.targetResults()).containsExactly(
            new QuizBookmarkUpdateResponse.TargetResult("", "食べる", "", "", "", "wrong", -4)
        );
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
        assertThat(response.targetResults()).containsExactly(
            new QuizBookmarkUpdateResponse.TargetResult("", "薬局", "", "", "", "missing", null)
        );
    }

    @Test
    void processTurnUpdatesBookmarksAndCreatesNextWordSet() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findWordsByLemmas(List.of("食べる")))
            .thenReturn(Map.of("食べる", Map.of("lemma", "食べる")))
            .thenReturn(Map.of("食べる", Map.of(
                "wordId", "word-eat",
                "lemma", "食べる",
                "reading", "たべる",
                "source", "JLPT:N5,EXAMPLE:JLPT_EDGE_BACKFILL",
                "meaning", "먹다, 먹이를 먹다.",
                "bookmark", -3
            )));
        when(graphRepository.adjustWordBookmarks(Map.of("食べる", 1))).thenReturn(1);
        when(graphRepository.findQuizTargetBySources(
            List.of("JLPT:N5"),
            List.of("食べる"),
            true,
            true,
            true
        )).thenReturn(Optional.of(Map.of(
            "wordId", "word-water",
            "lemma", "水",
            "reading", "みず",
            "meaning", "물",
            "pos", "명사",
            "posDetail", "名詞",
            "posDesc", "명사",
            "source", "JLPT:N5",
            "starGrade", 1,
            "dictEntryId", "dict-water"
        )));
        when(graphRepository.findQuizCandidateWordsByEdge(
            eq("水"),
            eq(List.of("JLPT:N5")),
            eq(List.of("食べる", "水")),
            eq(1),
            eq(true),
            eq(true),
            eq(true)
        )).thenReturn(List.of());
        when(graphRepository.findQuizWordsBySources(
            eq(List.of("JLPT:N5")),
            eq(List.of("食べる", "水")),
            eq(1),
            eq(true),
            eq(true),
            eq(true)
        )).thenReturn(List.of());

        QuizService quizService = new QuizService(graphRepository, jakoService);

        QuizTurnResponse response = quizService.processTurn(new QuizTurnRequest(
            "random_jlpt",
            List.of("N5"),
            2,
            List.of("食べる"),
            true,
            true,
            true,
            List.of("食べる"),
            List.of(),
            List.of("食べる")
        ));

        assertThat(response.bookmark().updatedCount()).isEqualTo(1);
        assertThat(response.targetDisplayLines()).containsExactly("• 食べる(たべる): N5, 먹다 ✅ (-3)");
        assertThat(response.mustCopyTargetBlock()).isEqualTo("출제단어:\n\n• 食べる(たべる): N5, 먹다 ✅ (-3)");
        assertThat(response.mustCopySeparator()).isEqualTo("———");
        assertThat(response.wordSet().requiredWord().lemma()).isEqualTo("水");
        assertThat(response.status()).isEqualTo("ok");
    }

}
