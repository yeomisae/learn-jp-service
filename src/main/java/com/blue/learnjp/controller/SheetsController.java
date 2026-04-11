package com.blue.learnjp.controller;

import com.blue.learnjp.service.GoogleSheetsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class SheetsController {

    private final Optional<GoogleSheetsService> googleSheetsService;

    public SheetsController(Optional<GoogleSheetsService> googleSheetsService) {
        this.googleSheetsService = googleSheetsService;
    }

    @PostMapping("/sheets/sync")
    public ResponseEntity<Map<String, Object>> sync() {
        if (googleSheetsService.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "skipped", "reason", "Google Sheets not configured"));
        }
        int exported = googleSheetsService.get().exportWords();
        return ResponseEntity.ok(Map.of("status", "ok", "exported", exported));
    }
}
