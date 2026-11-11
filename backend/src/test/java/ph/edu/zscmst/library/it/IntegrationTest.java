package ph.edu.zscmst.library.it;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests against a real PostgreSQL database (migrated by Flyway on start-up). Set
 * TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD; by default it uses the Docker Compose
 * database on port 5433 with a separate "smartlibrary_test" database. Without a reachable
 * database these tests are skipped, so a plain {@code mvn test} still works anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.sip2.enabled=false",
        "app.bootstrap-admin.username=admin",
        "app.bootstrap-admin.password=Admin-Test-123",
        "app.storage.dir=${java.io.tmpdir}/smart-library-test-files",
        "spring.datasource.hikari.maximum-pool-size=5"})
public abstract class IntegrationTest {

    static final String URL = env("TEST_DB_URL", "jdbc:postgresql://localhost:5433/smartlibrary_test");
    static final String USER = env("TEST_DB_USERNAME", "smartlibrary");
    static final String PASSWORD = env("TEST_DB_PASSWORD", "smartlibrary");

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    @BeforeAll
    static void requireDatabase() {
        DriverManager.setLoginTimeout(3);
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            Assumptions.assumeTrue(c.isValid(2), "test database not reachable");
        } catch (Exception e) {
            Assumptions.abort("No test database at " + URL + " (" + e.getMessage() + "); skipping integration tests");
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> URL);
        r.add("spring.datasource.username", () -> USER);
        r.add("spring.datasource.password", () -> PASSWORD);
    }
}
