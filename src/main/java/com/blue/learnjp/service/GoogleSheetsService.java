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
        Map<String, String> spreadsheets = config.spreadsheets();
        if (spreadsheets == null || spreadsheets.isEmpty()) {
            // 하위호환: 단일 spreadsheetId만 있는 경우
            String spreadsheetId = config.spreadsheetId();
            if (spreadsheetId == null || spreadsheetId.isEmpty()) return 0;
            return exportAll(spreadsheetId);
        }

        int total = 0;

        // JLPT 시트: source에 JLPT 포함된 단어
        String jlptId = spreadsheets.get("jlpt");
        if (jlptId != null && !jlptId.isEmpty()) {
            Collection<Map<String, Object>> jlptWords = neo4jClient.query(
                "MATCH (w:Word) WHERE w.source CONTAINS 'JLPT' RETURN w.lemma AS lemma, w.meaning AS meaning, w.pos AS pos, w.posDesc AS posDesc, w.reading AS reading, w.synonyms AS synonyms, w.antonyms AS antonyms, w.description AS description, w.bookmark AS bookmark, w.image AS image, w.createdAt AS createdAt ORDER BY w.createdAt"
            ).fetch().all();
            writeToSheet(jlptId, jlptWords);
            total += jlptWords.size();
            log.info("Exported {} JLPT words", jlptWords.size());
        }

        // NEWS 시트: source에 NEWS 포함된 단어 (중복 허용)
        String newsId = spreadsheets.get("news");
        if (newsId != null && !newsId.isEmpty()) {
            Collection<Map<String, Object>> newsWords = neo4jClient.query(
                "MATCH (w:Word) WHERE w.source CONTAINS 'NEWS' RETURN w.lemma AS lemma, w.meaning AS meaning, w.pos AS pos, w.posDesc AS posDesc, w.reading AS reading, w.synonyms AS synonyms, w.antonyms AS antonyms, w.description AS description, w.bookmark AS bookmark, w.image AS image, w.createdAt AS createdAt ORDER BY w.createdAt"
            ).fetch().all();
            writeToSheet(newsId, newsWords);
            total += newsWords.size();
            log.info("Exported {} NEWS words", newsWords.size());
        }

        // default 시트: JLPT도 NEWS도 아닌 나머지
        String defaultId = spreadsheets.get("default");
        if (defaultId != null && !defaultId.isEmpty()) {
            Collection<Map<String, Object>> defaultWords = neo4jClient.query(
                "MATCH (w:Word) WHERE NOT w.source CONTAINS 'JLPT' AND NOT w.source CONTAINS 'NEWS' RETURN w.lemma AS lemma, w.meaning AS meaning, w.pos AS pos, w.posDesc AS posDesc, w.reading AS reading, w.synonyms AS synonyms, w.antonyms AS antonyms, w.description AS description, w.bookmark AS bookmark, w.image AS image, w.createdAt AS createdAt ORDER BY w.createdAt"
            ).fetch().all();
            writeToSheet(defaultId, defaultWords);
            total += defaultWords.size();
            log.info("Exported {} default words", defaultWords.size());
        }

        return total;
    }

    private int exportAll(String spreadsheetId) {
        Collection<Map<String, Object>> words = neo4jClient.query(
            "MATCH (w:Word) RETURN w.lemma AS lemma, w.meaning AS meaning, w.pos AS pos, w.posDesc AS posDesc, w.reading AS reading, w.synonyms AS synonyms, w.antonyms AS antonyms, w.description AS description, w.bookmark AS bookmark, w.image AS image, w.createdAt AS createdAt ORDER BY w.createdAt"
        ).fetch().all();
        writeToSheet(spreadsheetId, words);
        log.info("Exported {} words to single spreadsheet", words.size());
        return words.size();
    }

    private void writeToSheet(String spreadsheetId, Collection<Map<String, Object>> words) {
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
        } catch (Exception e) {
            log.error("Failed to export words to Google Sheets {}", spreadsheetId, e);
            throw new RuntimeException("Google Sheets export failed", e);
        }
    }

    private List<List<Object>> buildSheetData(Collection<Map<String, Object>> words) {
        List<List<Object>> data = new ArrayList<>();

        data.add(List.of("W", "M", "POS", "P", "S", "A", "D", "B", "I", "C"));

        for (Map<String, Object> word : words) {
            String createdAt = "";
            Object raw = word.get("createdAt");
            if (raw instanceof ZonedDateTime zdt) {
                createdAt = zdt.format(DATE_FORMAT);
            } else if (raw != null) {
                createdAt = raw.toString();
            }

            Object bookmarkVal = word.get("bookmark");
            String bookmark = normalizeBookmarkForSheet(bookmarkVal);

            // POS: posDesc 우선, 없으면 pos 원본
            String posDesc = nullSafe(word.get("posDesc"));
            String posDisplay = posDesc.isEmpty() ? nullSafe(word.get("pos")) : posDesc;

            data.add(List.of(
                nullSafe(word.get("lemma")),
                nullSafe(word.get("meaning")),
                posDisplay,
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

    static String normalizeBookmarkForSheet(Object bookmarkVal) {
        if (bookmarkVal instanceof Number number) {
            return Integer.compare(number.intValue(), 0) < 0 ? "-1"
                : Integer.compare(number.intValue(), 0) > 0 ? "1" : "0";
        }
        if (bookmarkVal instanceof String text && !text.isBlank()) {
            try {
                int value = Integer.parseInt(text.trim());
                return value < 0 ? "-1" : value > 0 ? "1" : "0";
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }
        return "0";
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
