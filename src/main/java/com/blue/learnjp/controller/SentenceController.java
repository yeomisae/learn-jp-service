package com.blue.learnjp.controller;

import com.blue.learnjp.dto.AnalysisResult;
import com.blue.learnjp.service.SentenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        String sentence = request.get("sentence");
        if (sentence == null || sentence.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        AnalysisResult result = sentenceService.process(sentence);
        return ResponseEntity.ok(result);
    }
}
