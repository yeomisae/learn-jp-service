package com.blue.learnjp.controller;

import com.blue.learnjp.service.UserWordStateMigrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/migrations")
public class MigrationController {

    private final UserWordStateMigrationService userWordStateMigrationService;

    public MigrationController(UserWordStateMigrationService userWordStateMigrationService) {
        this.userWordStateMigrationService = userWordStateMigrationService;
    }

    @PostMapping("/user-word-state")
    public ResponseEntity<UserWordStateMigrationService.MigrationResult> migrateUserWordState(
        @RequestParam(defaultValue = "false") boolean resetNeo4jBookmark
    ) {
        return ResponseEntity.ok(userWordStateMigrationService.migrate(resetNeo4jBookmark));
    }
}
