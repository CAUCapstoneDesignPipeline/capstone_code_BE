import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
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
                .load().migrate();
            try (var connection = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
                 var sql = connection.createStatement()) {

                sql.execute("INSERT INTO app_user(id,display_name) VALUES ('00000000-0000-0000-0000-000000000001','Synthetic One'),('00000000-0000-0000-0000-000000000002','Synthetic Two')");
                sql.execute("INSERT INTO note(user_id,title,body,version) VALUES ('00000000-0000-0000-0000-000000000001','Fixture A','NFC fixture A',2),('00000000-0000-0000-0000-000000000002','Fixture B','NFC fixture B',3)");
                sql.execute("INSERT INTO user_identity(user_id,provider,provider_subject,email_verified) SELECT id,'google','synthetic-'||id,true FROM app_user");
                sql.execute("INSERT INTO refresh_token(user_id,family_id,token_hash,expires_at) SELECT id,gen_random_uuid(),repeat(substr(id::text,36,1),64),now()+interval '1 day' FROM app_user");
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
        }
    }
}
