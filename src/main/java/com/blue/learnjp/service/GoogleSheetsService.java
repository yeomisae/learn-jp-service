package com.blue.learnjp.service;

import com.blue.learnjp.config.GoogleSheetsConfig;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ClearValuesRequest;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnExpression("!'${google.sheets.credentials-path:}'.isEmpty()")
public class GoogleSheetsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsService.class);
    private static final String SHEET_RANGE = "시트1";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");

    private final GoogleSheetsConfig config;
    private final Sheets sheetsClient;
    private final Neo4jClient neo4jClient;

    public GoogleSheetsService(GoogleSheetsConfig config, Neo4jClient neo4jClient) {
        this.config = config;
        this.neo4jClient = neo4jClient;
        this.sheetsClient = buildSheetsClient(config.credentialsPath());
    }

    public int exportWords() {
        String spreadsheetId = config.spreadsheetId();

        Collection<Map<String, Object>> words = neo4jClient.query(
            "MATCH (w:Word) RETURN w.lemma AS lemma, w.meaning AS meaning, w.pos AS pos, w.reading AS reading, w.synonyms AS synonyms, w.antonyms AS antonyms, w.description AS description, w.bookmark AS bookmark, w.image AS image, w.createdAt AS createdAt ORDER BY w.createdAt"
        ).fetch().all();

        log.info("Exporting {} words to spreadsheet: {}", words.size(), spreadsheetId);

        try {
            sheetsClient.spreadsheets().values()
                .clear(spreadsheetId, SHEET_RANGE, new ClearValuesRequest())
                .execute();

            List<List<Object>> data = buildSheetData(words);
            ValueRange body = new ValueRange().setValues(data);

            sheetsClient.spreadsheets().values()
                .update(spreadsheetId, SHEET_RANGE, body)
                .setValueInputOption("RAW")
                .execute();

            log.info("Successfully exported {} words", words.size());
            return words.size();
        } catch (Exception e) {
            log.error("Failed to export words to Google Sheets", e);
            throw new RuntimeException("Google Sheets export failed", e);
        }
    }

    private List<List<Object>> buildSheetData(Collection<Map<String, Object>> words) {
        List<List<Object>> data = new ArrayList<>();

        data.add(List.of("Word (W)", "Meaning (M)", "Part of Speech (POS)", "Pronunciation (P)",
            "Synonyms (S)", "Antonyms (A)", "Description (D)", "Bookmark (B)", "Image (I)", "Calendar Date (C)"));

        for (Map<String, Object> word : words) {
            String createdAt = "";
            Object raw = word.get("createdAt");
            if (raw instanceof ZonedDateTime zdt) {
                createdAt = zdt.format(DATE_FORMAT);
            } else if (raw != null) {
                createdAt = raw.toString();
            }

            Object bookmarkVal = word.get("bookmark");
            String bookmark = bookmarkVal != null ? bookmarkVal.toString() : "0";

            data.add(List.of(
                nullSafe(word.get("lemma")),
                nullSafe(word.get("meaning")),
                nullSafe(word.get("pos")),
                nullSafe(word.get("reading")),
                nullSafe(word.get("synonyms")),
                nullSafe(word.get("antonyms")),
                nullSafe(word.get("description")),
                bookmark,
                nullSafe(word.get("image")),
                createdAt
            ));
        }

        return data;
    }

    private static String nullSafe(Object value) {
        return value != null ? value.toString() : "";
    }

    private Sheets buildSheetsClient(String credentialsPath) {
        try {
            GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream(credentialsPath))
                .createScoped(List.of(SheetsScopes.SPREADSHEETS));

            return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
            ).setApplicationName("learn-jp-service").build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Google Sheets client", e);
        }
    }
}
