package com.blue.learnjp.controller;

import com.blue.learnjp.dto.QuizBookmarkUpdateRequest;
import com.blue.learnjp.dto.QuizBookmarkUpdateResponse;
import com.blue.learnjp.dto.QuizAnswerGradeSubmitRequest;
import com.blue.learnjp.dto.QuizAnswerGradeSubmitResponse;
import com.blue.learnjp.dto.QuizAnswerSubmitRequest;
import com.blue.learnjp.dto.QuizAnswerSubmitResponse;
import com.blue.learnjp.dto.QuizNextProblemRequest;
import com.blue.learnjp.dto.QuizNextProblemResponse;
import com.blue.learnjp.dto.QuizProblemCreateRequest;
import com.blue.learnjp.dto.QuizProblemDraftRequest;
import com.blue.learnjp.dto.QuizProblemDraftResponse;
import com.blue.learnjp.dto.QuizProblemResponse;
import com.blue.learnjp.dto.QuizSessionEndRequest;
import com.blue.learnjp.dto.QuizSessionRequest;
import com.blue.learnjp.dto.QuizSessionResponse;
import com.blue.learnjp.dto.QuizStartRequest;
import com.blue.learnjp.dto.QuizStartResponse;
import com.blue.learnjp.dto.QuizTurnRequest;
import com.blue.learnjp.dto.QuizTurnResponse;
import com.blue.learnjp.dto.QuizWordSetRequest;
import com.blue.learnjp.dto.QuizWordSetResponse;
import com.blue.learnjp.service.QuizHistoryService;
import com.blue.learnjp.service.QuizLifecycleService;
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
    private final QuizLifecycleService quizLifecycleService;

    public QuizController(QuizService quizService, QuizHistoryService quizHistoryService,
                          QuizLifecycleService quizLifecycleService) {
        this.quizService = quizService;
        this.quizHistoryService = quizHistoryService;
        this.quizLifecycleService = quizLifecycleService;
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

    @PostMapping("/session")
    public ResponseEntity<?> startOrUpdateSession(@RequestBody(required = false) QuizSessionRequest request) {
        try {
            QuizSessionResponse response = quizLifecycleService.startOrUpdateSession(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> startAndCreateDraft(@RequestBody(required = false) QuizStartRequest request) {
        try {
            QuizStartResponse response = quizLifecycleService.startAndCreateDraft(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/session/end")
    public ResponseEntity<?> endSession(@RequestBody(required = false) QuizSessionEndRequest request) {
        try {
            QuizSessionResponse response = quizLifecycleService.endSession(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/problems/draft")
    public ResponseEntity<?> createProblemDraft(@RequestBody(required = false) QuizProblemDraftRequest request) {
        try {
            QuizProblemDraftResponse response = quizLifecycleService.createDraft(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/problems")
    public ResponseEntity<?> createProblem(@RequestBody(required = false) QuizProblemCreateRequest request) {
        try {
            QuizProblemResponse response = quizLifecycleService.createProblem(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/problems/open")
    public ResponseEntity<?> openProblem(@RequestParam String scopeId) {
        try {
            QuizProblemResponse response = quizLifecycleService.findOpenProblem(scopeId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/problems/{problemId}/answers")
    public ResponseEntity<?> submitAnswer(@PathVariable String problemId,
                                          @RequestBody(required = false) QuizAnswerSubmitRequest request) {
        try {
            QuizAnswerSubmitResponse response = quizLifecycleService.submitAnswer(problemId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/problems/{problemId}/grades")
    public ResponseEntity<?> gradeAnswers(@PathVariable String problemId,
                                          @RequestBody(required = false) QuizAnswerGradeSubmitRequest request) {
        try {
            QuizAnswerGradeSubmitResponse response = quizLifecycleService.gradeAnswers(problemId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/problems/next")
    public ResponseEntity<?> nextProblem(@RequestBody(required = false) QuizNextProblemRequest request) {
        try {
            QuizNextProblemResponse response = quizLifecycleService.nextProblem(request);
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
