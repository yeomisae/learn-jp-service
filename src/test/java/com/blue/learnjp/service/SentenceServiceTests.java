package com.blue.learnjp.service;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.dto.JakoLookupResult;
import com.blue.learnjp.repository.GraphRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SentenceServiceTests {

    @Test
    void processHappyPathStoresSentenceWordsEdgesExamplesAndSyncsSheets() {
        OpenClawService openClawService = mock(OpenClawService.class);
        NaverJakoDictionaryService jakoService = mock(NaverJakoDictionaryService.class);
        GraphRepository graphRepository = mock(GraphRepository.class);
        GoogleSheetsService googleSheetsService = mock(GoogleSheetsService.class);
        ExampleQueueService exampleQueueService = mock(ExampleQueueService.class);

        SentenceService sentenceService = new SentenceService(
            openClawService,
            jakoService,
            graphRepository,
            Optional.of(googleSheetsService),
            exampleQueueService
        );

        String sentence = "寿司を食べる";
        AnalysisResult analysisResult = new AnalysisResult(
            List.of(
                new AnalysisResult.WordInfo("寿司", "寿司", "すし", "명사", "", "", "", ""),
                new AnalysisResult.WordInfo("食べる", "食べる", "たべる", "동사", "", "", "", "")
            ),
            List.of(new AnalysisResult.EdgeInfo("寿司", "食べる", "목적어-동사"))
        );

        when(graphRepository.sentenceExists(sentence)).thenReturn(false);
        when(openClawService.analyze(sentence)).thenReturn(analysisResult);
        when(jakoService.lookup("寿司", "すし")).thenReturn(new JakoLookupResult(
            true,
            "寿司",
            "すし",
            "초밥",
            "명사",
            "名詞",
            "명사",
            "",
            1,
            "[]",
            "dict-1",
            List.of(new JakoLookupResult.Example("寿司が好きです", "초밥을 좋아해요"))
        ));
        when(jakoService.lookup("食べる", "たべる")).thenReturn(new JakoLookupResult(
            true,
            "食べる",
            "たべる",
            "먹다",
            "동사",
            "下一段他動詞",
            "하1단 타동사",
            "",
            2,
            "[]",
            "dict-2",
            List.of()
        ));
        when(googleSheetsService.exportWords()).thenReturn(2);

        AnalysisResult actual = sentenceService.process(sentence, "NEWS");

        assertThat(actual).isEqualTo(analysisResult);
        verify(graphRepository).createSentence(sentence);
        verify(graphRepository).upsertJakoWord("寿司", "寿司", "寿司", "すし", "초밥", "명사", "名詞", "명사", "", "", "", "NEWS", 1, "[]", "dict-1");
        verify(graphRepository).upsertJakoWord("食べる", "食べる", "食べる", "たべる", "먹다", "동사", "下一段他動詞", "하1단 타동사", "", "", "", "NEWS", 2, "[]", "dict-2");
        verify(graphRepository).createCoOccursEdge("寿司", "食べる", sentence, "목적어-동사");
        verify(exampleQueueService).enqueue("寿司が好きです", "NEWS");
        verify(googleSheetsService).exportWords();
        verify(openClawService).analyze(sentence);
        verifyNoMoreInteractions(exampleQueueService);
    }
}
