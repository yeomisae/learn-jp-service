package com.blue.learnjp.controller;

import com.blue.learnjp.config.GoogleSheetsConfig;
import com.blue.learnjp.dto.ExportResult;
import com.blue.learnjp.service.GoogleSheetsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@ConditionalOnExpression("!'${google.sheets.credentials-path:}'.isEmpty()")
public class ExportController {

    private final GoogleSheetsService googleSheetsService;
    private final GoogleSheetsConfig googleSheetsConfig;

    public ExportController(GoogleSheetsService googleSheetsService,
                            GoogleSheetsConfig googleSheetsConfig) {
        this.googleSheetsService = googleSheetsService;
        this.googleSheetsConfig = googleSheetsConfig;
    }

    @PostMapping("/words/export/sheets")
    public ResponseEntity<ExportResult> exportToSheets() {
        try {
            int count = googleSheetsService.exportWords();
            return ResponseEntity.ok(new ExportResult(count, googleSheetsConfig.spreadsheetId(), "success"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(new ExportResult(0, googleSheetsConfig.spreadsheetId(), "error: " + e.getMessage()));
        }
    }
}
