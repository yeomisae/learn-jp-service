package com.blue.learnjp.domain;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.LocalDateTime;

@Node("Word")
public class Word {

    @Id
    @GeneratedValue
    private Long id;

    private String surface;
    private String lemma;
    private String reading;
    private String meaning;
    private String pos;
    private String synonyms;
    private String antonyms;
    private String description;
    private String jlptLevel;
    private int bookmark;
    private String image;
    private LocalDateTime createdAt;

    protected Word() {
    }

    public Word(String surface, String lemma, String reading, String meaning, String pos) {
        this.surface = surface;
        this.lemma = lemma;
        this.reading = reading;
        this.meaning = meaning;
        this.pos = pos;
        this.bookmark = 0;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getSurface() { return surface; }
    public String getLemma() { return lemma; }
    public String getReading() { return reading; }
    public String getMeaning() { return meaning; }
    public String getPos() { return pos; }
    public String getSynonyms() { return synonyms; }
    public String getAntonyms() { return antonyms; }
    public String getDescription() { return description; }
    public String getJlptLevel() { return jlptLevel; }
    public int getBookmark() { return bookmark; }
    public String getImage() { return image; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setSurface(String surface) { this.surface = surface; }
    public void setReading(String reading) { this.reading = reading; }
    public void setMeaning(String meaning) { this.meaning = meaning; }
    public void setPos(String pos) { this.pos = pos; }
    public void setSynonyms(String synonyms) { this.synonyms = synonyms; }
    public void setAntonyms(String antonyms) { this.antonyms = antonyms; }
    public void setDescription(String description) { this.description = description; }
    public void setJlptLevel(String jlptLevel) { this.jlptLevel = jlptLevel; }
    public void setBookmark(int bookmark) { this.bookmark = bookmark; }
    public void setImage(String image) { this.image = image; }
}
