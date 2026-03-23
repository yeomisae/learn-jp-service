package com.blue.learnjp.domain;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.time.LocalDateTime;

@Node("Sentence")
public class Sentence {

    @Id
    @GeneratedValue
    private Long id;

    private String text;
    private LocalDateTime createdAt;

    protected Sentence() {
    }

    public Sentence(String text) {
        this.text = text;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getText() { return text; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
