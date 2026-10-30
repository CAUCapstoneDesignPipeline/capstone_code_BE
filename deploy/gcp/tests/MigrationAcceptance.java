import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.sql.Connection;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

/** Launches only an isolated postgres:16.15 Testcontainer; Python tests use its PG16 clients. */
public class MigrationAcceptance {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        try (var db = new PostgreSQLContainer("postgres:16.15").withDatabaseName("capstone")) {
            db.start();
            try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                 var sql = connection.createStatement()) {
                sql.execute("CREATE ROLE capstone_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD 'synthetic-fixture-only'");
                sql.execute("ALTER DATABASE capstone OWNER TO capstone_app");
                sql.execute("CREATE DATABASE rehearsal OWNER capstone_app");
            }
            Flyway.configure().dataSource(db.getJdbcUrl(), "capstone_app", "synthetic-fixture-only")
                .locations("filesystem:" + root.resolve("src/main/resources/db/migration"))
                .target("2").load().migrate();
            try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                 var sql = connection.createStatement()) {

                sql.execute("INSERT INTO app_user(id,display_name) VALUES ('00000000-0000-0000-0000-000000000001','Synthetic One'),('00000000-0000-0000-0000-000000000002','Synthetic Two')");
                sql.execute("INSERT INTO note(user_id,title,body,version) VALUES ('00000000-0000-0000-0000-000000000001','Fixture A','NFC fixture A',2),('00000000-0000-0000-0000-000000000002','Fixture B','NFC fixture B',3)");
                sql.execute("INSERT INTO user_identity(user_id,provider,provider_subject,email_verified) SELECT id,'google','synthetic-'||id,true FROM app_user");
                sql.execute("INSERT INTO refresh_token(user_id,family_id,token_hash,expires_at) SELECT id,gen_random_uuid(),repeat(substr(id::text,36,1),64),now()+interval '1 day' FROM app_user");
                // Legacy AI tables can contain history even while product capabilities are off.
                sql.execute("INSERT INTO analysis_job(note_id,note_version,status,extractor) SELECT id,version,'succeeded','synthetic-legacy' FROM note");
                sql.execute("INSERT INTO concept(id,user_id,label) VALUES ('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Alpha'),('10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','Bridge'),('10000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','Target')");
                sql.execute("INSERT INTO candidate(id,user_id,source_concept_id,bridge_concept_id,target_concept_id,kind,status,summary) VALUES ('20000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000003','candidate','accepted','Synthetic legacy accepted result')");
                sql.execute("INSERT INTO candidate_claim(candidate_id,kind,sort_order,body) VALUES ('20000000-0000-0000-0000-000000000001','conclusion',0,'Synthetic legacy conclusion')");
                sql.execute("INSERT INTO verification_result(candidate_id,layer,outcome,detail) VALUES ('20000000-0000-0000-0000-000000000001',1,'pass','{}')");
                sql.execute("INSERT INTO review(candidate_id,action,original) VALUES ('20000000-0000-0000-0000-000000000001','approve','{\"summary\":\"Synthetic legacy accepted result\"}')");
                sql.execute("CREATE SEQUENCE fixture_sequence START 10; ALTER SEQUENCE fixture_sequence OWNER TO capstone_app; SELECT nextval('fixture_sequence')");
            }
            String services = "";
            for (String[] item : new String[][]{{"source", "capstone"}, {"target", "rehearsal"}}) {
                services += "[" + item[0] + "]\nhost=127.0.0.1\nport=5432\ndbname=" + item[1]
                    + "\nuser=capstone_app\npassword=synthetic-fixture-only\n";
            }
            services += "[admin_target]\nhost=127.0.0.1\nport=5432\ndbname=rehearsal\nuser=" + db.getUsername() + "\npassword=" + db.getPassword() + "\n";
            db.copyFileToContainer(Transferable.of(services.getBytes(), 0600), "/tmp/migration-services.conf");
            var process = new ProcessBuilder("python3", "deploy/gcp/tests/migration_acceptance.py", db.getContainerId())
                .directory(root.toFile()).inheritIO().start();
            if (process.waitFor() != 0) throw new IllegalStateException("Migration acceptance failed; data suppressed");
            // The source/restore acceptance above deliberately uses the existing V1/V2 release.
            // Upgrade only these disposable databases, preserving all original column values.
            int upgraded = 0;
            for (String database : List.of("capstone", "rehearsal")) {
                String url = "jdbc:postgresql://" + db.getHost() + ":" + db.getMappedPort(5432) + "/" + database;
                try (var connection = DriverManager.getConnection(url, "capstone_app", "synthetic-fixture-only")) {
                    Map<String, String> queries = originalQueries(connection);
                    Map<String, List<String>> before = capture(connection, queries);
                    var flyway = Flyway.configure().dataSource(url, "capstone_app", "synthetic-fixture-only")
                        .locations("filesystem:" + root.resolve("src/main/resources/db/migration")).load();
                    upgraded += flyway.migrate().migrationsExecuted;
                    flyway.validate();
                    if (!before.equals(capture(connection, queries)))
                        throw new IllegalStateException("Feature migration changed original data/history; values suppressed");
                    if (flyway.migrate().migrationsExecuted != 0)
                        throw new IllegalStateException("Feature migration was not repeatable without changes");
                }
            }
            System.out.println("V2 restored data and Flyway checksums preserved; new migrations applied across two isolated databases: " + upgraded);
        }
    }
    private static Map<String, String> originalQueries(Connection connection) throws Exception {
        Map<String, String> queries = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT table_name, string_agg(quote_ident(column_name), ',' ORDER BY ordinal_position) AS columns FROM information_schema.columns WHERE table_schema='public' GROUP BY table_name ORDER BY table_name")) {
            while (rows.next()) {
                String table = rows.getString(1);
                String predicate = table.equals("flyway_schema_history") ? " WHERE version IN ('1','2')" : "";
                queries.put(table, "SELECT row_to_json(v)::text FROM (SELECT " + rows.getString(2) + " FROM \"" + table.replace("\"", "\"\"") + "\"" + predicate + ") v ORDER BY 1");
            }
        }
        return queries;
    }

    private static Map<String, List<String>> capture(Connection connection, Map<String, String> queries) throws Exception {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (var entry : queries.entrySet()) {
            List<String> table = new ArrayList<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(entry.getValue())) {
                while (rows.next()) table.add(rows.getString(1));
            }
            values.put(entry.getKey(), table);
        }
        return values;
    }

}
