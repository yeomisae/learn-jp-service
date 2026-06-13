package com.blue.learnjp.service;

import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.repository.UserRepository;
import com.blue.learnjp.repository.UserWordStateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class QuizServiceTests {

    private static final String SENDER_ID = "sender-1";
    private static final long USER_ID = 42L;

    private QuizService quizService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService) {
        return quizService(graphRepository, jakoService, null);
    }

    private QuizService quizService(GraphRepository graphRepository, NaverJakoDictionaryService jakoService,
                                    UserWordStateRepository userWordStateRepository) {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByDiscordSenderId(SENDER_ID))
            .thenReturn(Optional.of(new UserRepository.UserRecord(USER_ID, "BLUE", SENDER_ID)));
        return new QuizService(graphRepository, jakoService, null, userWordStateRepository, userRepository);
    }

    @Test
    void createWordSetUsesDefaultJlptStrategyAndSelectsRequiredWord() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findQuizTargetCandidatesBySources(
            List.of("JLPT:N5", "JLPT:N4", "JLPT:N3", "JLPT:N2", "JLPT:N1"),
            List.of(),
            500,
            true,
            true,
            true
        )).thenReturn(List.of(Map.of(
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
            eq(80),
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
            eq(60),
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

        QuizService quizService = quizService(graphRepository, jakoService);

        QuizWordSetResponse response = quizService.createWordSet(new QuizWordSetRequest(
            null, null, null, null, null, null, null,
            SENDER_ID
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
        when(graphRepository.findQuizTargetCandidatesBySources(anyList(), anyList(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
            .thenReturn(List.of());

        QuizService quizService = quizService(graphRepository, jakoService);

        quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt",
            List.of("n5", "N4", "N4"),
            2,
            List.of(" 食べる ", "", "行く"),
            false,
            true,
            false,
            SENDER_ID
        ));

        verify(graphRepository).findQuizTargetCandidatesBySources(
            List.of("JLPT:N5", "JLPT:N4"),
            List.of("食べる", "行く"),
            500,
            false,
            true,
            false
        );
    }

    @Test
    void createWordSetUsesEdgeCandidatesBeforeRandomFallback() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findQuizTargetCandidatesBySources(anyList(), anyList(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean()))
            .thenReturn(List.of(Map.of(
                "wordId", "word-dictionary",
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
            eq(50),
            eq(true),
            eq(true),
            eq(true)
        ))
            .thenReturn(List.of(
                Map.of(
                    "wordId", "word-friend",
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
            eq(50),
            eq(true),
            eq(true),
            eq(true)
        )).thenReturn(List.of(
                Map.of(
                    "wordId", "word-somehow",
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

        QuizService quizService = quizService(graphRepository, jakoService);

        QuizWordSetResponse response = quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt", List.of("N5"), 3, List.of(), true, true, true,
            SENDER_ID
        ));

        assertThat(response.requiredWord().lemma()).isEqualTo("辞書");
        assertThat(response.candidateWords()).extracting(QuizWordSetResponse.QuizWord::lemma)
            .containsExactly("友達", "どうも");
    }

    @Test
    void createWordSetRejectsUnsupportedStrategy() {
        QuizService quizService = quizService(mock(GraphRepository.class), mock(NaverJakoDictionaryService.class));

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "connected", List.of("N5"), 3, List.of(), true, true, true,
            SENDER_ID
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported strategy");
    }

    @Test
    void createWordSetRejectsUnsupportedLevel() {
        QuizService quizService = quizService(mock(GraphRepository.class), mock(NaverJakoDictionaryService.class));

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt", List.of("N0"), 3, List.of(), true, true, true,
            SENDER_ID
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported level");
    }

    @Test
    void createWordSetRejectsMissingDiscordSenderId() {
        QuizService quizService = quizService(mock(GraphRepository.class), mock(NaverJakoDictionaryService.class));

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt", List.of("N5"), 3, List.of(), true, true, true,
            null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("discordSenderId is required");
    }

    @Test
    void createWordSetRejectsUnknownDiscordSenderId() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByDiscordSenderId("unknown-sender")).thenReturn(Optional.empty());
        QuizService quizService = new QuizService(
            mock(GraphRepository.class),
            mock(NaverJakoDictionaryService.class),
            null,
            null,
            userRepository
        );

        assertThatThrownBy(() -> quizService.createWordSet(new QuizWordSetRequest(
            "random_jlpt", List.of("N5"), 3, List.of(), true, true, true,
            "unknown-sender"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown discordSenderId. Run /join first.");
    }

    @Test
    void updateBookmarksAppliesWrongAndCorrectDeltas() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        UserWordStateRepository userWordStateRepository = mock(UserWordStateRepository.class);
        when(graphRepository.findWordsByWordIds(List.of("word-pharmacy", "word-medicine")))
            .thenReturn(Map.of(
                "word-pharmacy", Map.of("wordId", "word-pharmacy", "lemma", "薬局", "reading", "やっきょく", "source", "JLPT:N5", "meaning", "약국"),
                "word-medicine", Map.of("wordId", "word-medicine", "lemma", "薬", "reading", "くすり", "source", "JLPT:N5", "meaning", "약")
            ));
        when(userWordStateRepository.adjustBookmarks(USER_ID, Map.of(
            "word-pharmacy", -1,
            "word-medicine", 1
        ))).thenReturn(2);
        when(userWordStateRepository.findBookmarks(USER_ID, List.of("word-pharmacy", "word-medicine")))
            .thenReturn(Map.of("word-pharmacy", -4, "word-medicine", -2));

        QuizService quizService = quizService(graphRepository, jakoService, userWordStateRepository);

        QuizBookmarkUpdateResponse response = quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("word-pharmacy", "word-medicine"),
            List.of(" word-pharmacy ", ""),
            List.of("word-medicine"),
            SENDER_ID
        ));

        assertThat(response.updatedCount()).isEqualTo(2);
        assertThat(response.appliedDeltas()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "word-pharmacy", -1,
            "word-medicine", 1
        ));
        assertThat(response.resolvedMappings()).isEmpty();
        assertThat(response.ignoredLemmas()).isEmpty();
        assertThat(response.missingLemmas()).isEmpty();
        assertThat(response.targetResults()).containsExactly(
            new QuizBookmarkUpdateResponse.TargetResult("word-pharmacy", "薬局", "やっきょく", "JLPT:N5", "약국", "wrong", -4),
            new QuizBookmarkUpdateResponse.TargetResult("word-medicine", "薬", "くすり", "JLPT:N5", "약", "correct", -2)
        );
        assertThat(response.status()).isEqualTo("ok");
        verify(graphRepository, never()).adjustWordBookmarksByWordId(anyMap());
    }

    @Test
    void updateBookmarksRejectsOverlappingWordIds() {
        QuizService quizService = quizService(mock(GraphRepository.class), mock(NaverJakoDictionaryService.class));

        assertThatThrownBy(() -> quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("word-pharmacy"),
            List.of("word-pharmacy"),
            List.of("word-pharmacy"),
            null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both wrong and correct");
    }

    @Test
    void updateBookmarksRejectsWordIdsOutsideTargetSet() {
        QuizService quizService = quizService(mock(GraphRepository.class), mock(NaverJakoDictionaryService.class));

        assertThatThrownBy(() -> quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("word-target"),
            List.of("word-noise"),
            List.of(),
            null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wrongWordIds must be included");
    }

    @Test
    void updateBookmarksRejectsUnknownTargetWordIds() {
        GraphRepository graphRepository = mock(GraphRepository.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        when(graphRepository.findWordsByWordIds(List.of("missing-word"))).thenReturn(Map.of());

        QuizService quizService = quizService(graphRepository, jakoService);

        assertThatThrownBy(() -> quizService.updateBookmarks(new QuizBookmarkUpdateRequest(
            List.of("missing-word"),
            List.of(),
            List.of(),
            SENDER_ID
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown targetWordIds");
    }

}
