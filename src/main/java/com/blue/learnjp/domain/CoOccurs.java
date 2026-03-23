package com.blue.learnjp.domain;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

import java.time.LocalDateTime;

@RelationshipProperties
public class CoOccurs {

    @Id
    @GeneratedValue
    private Long id;

    @TargetNode
    private Word targetWord;

    private String sentence;
    private String pattern;
    private LocalDateTime createdAt;

    protected CoOccurs() {
    }

    public CoOccurs(Word targetWord, String sentence, String pattern) {
        this.targetWord = targetWord;
        this.sentence = sentence;
        this.pattern = pattern;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Word getTargetWord() { return targetWord; }
    public String getSentence() { return sentence; }
    public String getPattern() { return pattern; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
