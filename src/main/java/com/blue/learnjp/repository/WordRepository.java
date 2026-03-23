package com.blue.learnjp.repository;

import com.blue.learnjp.domain.Word;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WordRepository extends Neo4jRepository<Word, Long> {

    Optional<Word> findByLemma(String lemma);
}
