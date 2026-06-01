package com.blue.learnjp.controller;

import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizTurnRequest;
import com.blue.learnjp.dto.QuizTurnResponse;
import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.service.QuizHistoryService;
import com.blue.learnjp.service.QuizService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final QuizService quizService;
    private final QuizHistoryService quizHistoryService;

    public QuizController(QuizService quizService, QuizHistoryService quizHistoryService) {
        this.quizService = quizService;
        this.quizHistoryService = quizHistoryService;
    }

    @PostMapping("/word-set")
    public ResponseEntity<?> createWordSet(@RequestBody(required = false) QuizWordSetRequest request) {
        try {
            QuizWordSetResponse response = quizService.createWordSet(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/bookmark")
    public ResponseEntity<?> updateBookmarks(@RequestBody(required = false) QuizBookmarkUpdateRequest request) {
        try {
            QuizBookmarkUpdateResponse response = quizService.updateBookmarks(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/turn")
    public ResponseEntity<?> processTurn(@RequestBody(required = false) QuizTurnRequest request) {
        try {
            QuizTurnResponse response = quizService.processTurn(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/history/daily")
    public ResponseEntity<?> dailyHistory(
        @RequestParam(required = false) LocalDate date,
        @RequestParam(required = false, defaultValue = "Asia/Seoul") String zone
    ) {
        try {
            return ResponseEntity.ok(quizHistoryService.dailyReport(date, ZoneId.of(zone)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
