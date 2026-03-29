package com.blue.learnjp.controller;

import com.blue.learnjp.domain.Source;
import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.dto.ImportRequest;
import com.blue.learnjp.service.SentenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SentenceController {

    private final SentenceService sentenceService;

    public SentenceController(SentenceService sentenceService) {
        this.sentenceService = sentenceService;
    }

    @PostMapping("/sentences")
    public ResponseEntity<AnalysisResult> processSentence(@RequestBody Map<String, String> request) {
        return processSentenceWithSource(request, null);
    }

    @PostMapping("/sentences/{source}")
    public ResponseEntity<AnalysisResult> processSentenceWithSource(
            @RequestBody Map<String, String> request,
            @PathVariable(required = false) String source) {
        String sentence = request.get("sentence");
        if (sentence == null || sentence.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // source enum 검증
        String validatedSource = "";
        if (source != null && !source.isBlank()) {
            try {
                validatedSource = Source.valueOf(source.toUpperCase()).name();
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }

        AnalysisResult result = sentenceService.process(sentence, validatedSource);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/words/import")
    public ResponseEntity<AnalysisResult> importWords(@RequestBody ImportRequest request) {
        if (request.sentence() == null || request.sentence().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (request.words() == null || request.words().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        AnalysisResult result = new AnalysisResult(request.words(), request.edges() != null ? request.edges() : java.util.List.of());
        sentenceService.importResult(request.sentence(), result, "");
        return ResponseEntity.ok(result);
    }
}
