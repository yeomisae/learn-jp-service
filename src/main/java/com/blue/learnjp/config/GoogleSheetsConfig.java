package com.blue.learnjp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "google.sheets")
public record GoogleSheetsConfig(
    String credentialsPath,
    String spreadsheetId,
    Map<String, String> spreadsheets
) {}
