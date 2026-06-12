package com.blue.learnjp.service;

import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.repository.UserWordStateRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class UserWordStateMigrationService {

    private final GraphRepository graphRepository;
    private final UserWordStateRepository userWordStateRepository;

    public UserWordStateMigrationService(GraphRepository graphRepository,
                                         UserWordStateRepository userWordStateRepository) {
        this.graphRepository = graphRepository;
        this.userWordStateRepository = userWordStateRepository;
    }

    public MigrationResult migrate(boolean resetNeo4jBookmark) {
        List<Map<String, Object>> states = graphRepository.findUserWordStatesForMigration();
        int copied = userWordStateRepository.upsertStates(states);
        int reset = resetNeo4jBookmark ? graphRepository.resetLegacyWordBookmarksToZero() : 0;
        return new MigrationResult(states.size(), copied, reset, resetNeo4jBookmark);
    }

    public record MigrationResult(
        int neo4jRows,
        int sqliteRowsCopied,
        int neo4jBookmarksReset,
        boolean resetNeo4jBookmark
    ) {}
}
