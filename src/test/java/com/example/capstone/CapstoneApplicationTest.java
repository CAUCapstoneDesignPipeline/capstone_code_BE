package com.example.capstone;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CapstoneApplicationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16.15");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private FlywayMigrationInitializer flywayMigrationInitializer;

    private Connection connection;

    @BeforeEach
    void beginTransaction() throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollbackTransaction() throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void startsContextAndConnectsToPostgresWithFlywayEnabled() throws Exception {
        assertThat(query("SELECT 1")).containsExactly("1");
        assertThat(flywayMigrationInitializer).isNotNull();
        assertThat(flyway.info().applied()).hasSize(2);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void createsEntireInitialErdWithoutUnconfirmedCatalogSeeds() throws Exception {
        assertThat(query("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    AND table_name <> 'flyway_schema_history'
                """))
                .containsExactlyInAnyOrder("app_user", "user_identity", "refresh_token", "topic", "note",
                        "analysis_job", "concept", "concept_mention", "concept_merge", "relation_type",
                        "relation_type_rule", "evidence_span", "relation", "relation_evidence", "candidate",
                        "candidate_step", "candidate_claim", "verification_result", "review", "oauth_attempt");
        assertThat(query("SELECT count(*) FROM relation_type")).containsExactly("0");
        assertThat(query("SELECT count(*) FROM relation_type_rule")).containsExactly("0");
    }

    @Test
    void enforcesUserScopedNamesAndRejectsAnotherUsersTopic() throws Exception {
        UUID owner = user();
        UUID another = user();
        UUID topic = insert("INSERT INTO topic (user_id, name) VALUES (?, '주제') RETURNING id", owner);
        insert("INSERT INTO topic (user_id, name) VALUES (?, '주제') RETURNING id", another);
        rejects("23505", "INSERT INTO topic (user_id, name) VALUES (?, '주제')", owner);
        note(owner, null, "노트");
        note(another, null, "노트");
        note(owner, topic, "노트");
        rejects("23505", "INSERT INTO note (user_id, title) VALUES (?, '노트')", owner);
        rejects("23503", "INSERT INTO note (user_id, topic_id, title) VALUES (?, ?, '외부 주제')", another, topic);
        rejects("23514", "INSERT INTO note (user_id, title) VALUES (?, '금지/제목')", owner);
    }

    @Test
    void deletesTopicOnlyWhenMovingNotesToUnclassifiedDoesNotConflict() throws Exception {
        UUID owner = user();
        UUID topic = insert("INSERT INTO topic (user_id, name) VALUES (?, '주제') RETURNING id", owner);
        UUID classified = note(owner, topic, "같은 제목");
        UUID unclassified = note(owner, null, "같은 제목");
        rejects("23505", "DELETE FROM topic WHERE id = ?", topic);
        assertThat(query("SELECT topic_id FROM note WHERE id = ?", classified)).containsExactly(topic.toString());
        execute("DELETE FROM note WHERE id = ?", unclassified);
        execute("DELETE FROM topic WHERE id = ?", topic);
        assertThat(query("SELECT user_id FROM note WHERE id = ? AND topic_id IS NULL", classified))
                .containsExactly(owner.toString());
    }

    @Test
    void allowsOnlyOnePendingOrRunningAnalysisPerNote() throws Exception {
        UUID note = note(user(), null, "분석할 노트");
        UUID job = insert("""
                INSERT INTO analysis_job (note_id, note_version, extractor)
                VALUES (?, 0, 'test') RETURNING id
                """, note);
        rejects("23505", """
                INSERT INTO analysis_job (note_id, note_version, extractor, status)
                VALUES (?, 0, 'test', 'running')
                """, note);
        execute("UPDATE analysis_job SET status = 'succeeded' WHERE id = ?", job);
        insert("INSERT INTO analysis_job (note_id, note_version, extractor) VALUES (?, 1, 'test') RETURNING id", note);
        rejects("23514", "UPDATE analysis_job SET status = 'unknown' WHERE id = ?", job);
    }

    @Test
    void preservesEvidenceAfterNoteDeletionAndChecksRangeAndLossState() throws Exception {
        UUID owner = user();
        UUID note = note(owner, null, "근거 노트");
        UUID span = insert("""
                INSERT INTO evidence_span (user_id, note_id, note_version, start_offset, end_offset, quote)
                VALUES (?, ?, 0, 0, 2, '근거') RETURNING id
                """, owner, note);
        rejects("23514", "UPDATE evidence_span SET end_offset = start_offset WHERE id = ?", span);
        rejects("23514", "UPDATE evidence_span SET status = 'lost' WHERE id = ?", span);
        // The future service must mark lost evidence; ON DELETE SET NULL alone does not update its status.
        execute("UPDATE evidence_span SET status = 'lost', lost_at = now() WHERE id = ?", span);
        execute("DELETE FROM note WHERE id = ?", note);
        assertThat(query("SELECT status FROM evidence_span WHERE id = ? AND note_id IS NULL AND user_id = ?", span, owner))
                .containsExactly("lost");
    }

    @Test
    void enforcesGraphAndCandidateConstraintsAndSingleReview() throws Exception {
        UUID owner = user();
        UUID source = insert("INSERT INTO concept (user_id, label) VALUES (?, 'A') RETURNING id", owner);
        UUID bridge = insert("INSERT INTO concept (user_id, label) VALUES (?, 'B') RETURNING id", owner);
        UUID target = insert("INSERT INTO concept (user_id, label) VALUES (?, 'C') RETURNING id", owner);
        execute("INSERT INTO relation_type (code, label) VALUES ('test_relation', '테스트 전용')");
        rejects("23514", """
                INSERT INTO relation (source_concept_id, target_concept_id, type_code, origin)
                VALUES (?, ?, 'test_relation', 'extracted')
                """, source, source);
        rejects("23514", """
                INSERT INTO relation (source_concept_id, target_concept_id, type_code, origin, as_hypothesis)
                VALUES (?, ?, 'test_relation', 'extracted', true)
                """, source, target);
        UUID candidate = insert("""
                INSERT INTO candidate (user_id, source_concept_id, bridge_concept_id, target_concept_id, kind, summary)
                VALUES (?, ?, ?, ?, 'candidate', '테스트 후보') RETURNING id
                """, owner, source, bridge, target);
        rejects("23505", """
                INSERT INTO candidate (user_id, source_concept_id, bridge_concept_id, target_concept_id, kind, summary)
                VALUES (?, ?, ?, ?, 'hypothesis', '중복 경로')
                """, owner, source, bridge, target);
        rejects("23514", "INSERT INTO review (candidate_id, action, original) VALUES (?, 'modify', '{}')", candidate);
        execute("INSERT INTO review (candidate_id, action, original) VALUES (?, 'reject', '{}')", candidate);
        rejects("23505", "INSERT INTO review (candidate_id, action, original) VALUES (?, 'reject', '{}')", candidate);
        rejects("23514", "INSERT INTO verification_result (candidate_id, layer, outcome) VALUES (?, 4, 'pass')", candidate);
    }

    private UUID user() throws SQLException {
        return insert("INSERT INTO app_user (display_name) VALUES ('테스트 사용자') RETURNING id");
    }

    private UUID note(UUID owner, UUID topic, String title) throws SQLException {
        return insert("INSERT INTO note (user_id, topic_id, title) VALUES (?, ?, ?) RETURNING id", owner, topic, title);
    }

    private UUID insert(String sql, Object... arguments) throws SQLException {
        return UUID.fromString(query(sql, arguments).getFirst());
    }

    private List<String> query(String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = prepare(sql, arguments); ResultSet result = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private void execute(String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = prepare(sql, arguments)) {
            statement.executeUpdate();
        }
    }

    private PreparedStatement prepare(String sql, Object... arguments) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < arguments.length; index++) {
            statement.setObject(index + 1, arguments[index]);
        }
        return statement;
    }

    private void rejects(String sqlState, String sql, Object... arguments) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            assertThatThrownBy(() -> execute(sql, arguments))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(sqlState));
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }
}
