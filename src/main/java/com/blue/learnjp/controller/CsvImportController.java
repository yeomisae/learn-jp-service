package com.blue.learnjp.controller;

import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.service.GoogleSheetsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class CsvImportController {

    private static final Logger log = LoggerFactory.getLogger(CsvImportController.class);

    private final GraphRepository graphRepository;
    private final Optional<GoogleSheetsService> googleSheetsService;

    public CsvImportController(GraphRepository graphRepository,
                               Optional<GoogleSheetsService> googleSheetsService) {
        this.graphRepository = graphRepository;
        this.googleSheetsService = googleSheetsService;
    }

    @PostMapping("/words/import/csv")
    public ResponseEntity<Map<String, Object>> importCsv(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty file"));
        }

        int imported = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String header = reader.readLine();
            if (header == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Empty CSV"));
            }

            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = parseCsvLine(line);
                if (fields.length < 4) {
                    skipped++;
                    continue;
                }

                String original = fields[0].trim();
                String furigana = fields[1].trim();
                String english = fields[2].trim();
                String jlptLevel = fields[3].trim();

                if (original.isEmpty()) {
                    skipped++;
                    continue;
                }

                graphRepository.mergeWord(
                    original,   // surface
                    original,   // lemma
                    furigana,   // reading
                    english,    // meaning
                    "",         // pos
                    "",         // synonyms
                    "",         // antonyms
                    "",         // description
                    jlptLevel   // jlptLevel
                );
                imported++;
            }

            log.info("CSV import complete: {} imported, {} skipped", imported, skipped);

            googleSheetsService.ifPresent(sheets -> {
                try {
                    sheets.exportWords();
                } catch (Exception e) {
                    log.warn("Google Sheets sync after CSV import failed: {}", e.getMessage());
                }
            });

            return ResponseEntity.ok(Map.of(
                "imported", imported,
                "skipped", skipped,
                "status", "success"
            ));

        } catch (Exception e) {
            log.error("CSV import failed", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    private String[] parseCsvLine(String line) {
        // Handle quoted CSV fields (e.g., "principle, general rule")
        java.util.List<String> fields = new java.util.ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());

        return fields.toArray(new String[0]);
    }
}
