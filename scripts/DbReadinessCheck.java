import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Read-only production-readiness counters. Credentials are read from the process environment. */
public class DbReadinessCheck {
    public static void main(String[] args) throws Exception {
        String url = required("DB_URL");
        String username = required("DB_USERNAME");
        String password = required("DB_PASSWORD");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            printSingle(statement, "SOURCES", "SELECT COUNT(*) FROM site");
            printSingle(statement, "ALL_PAGES", "SELECT COUNT(*) FROM page");
            printSingle(statement, "INDEXABLE_PAGES", "SELECT COUNT(*) FROM page WHERE code < 400");
            printSingle(statement, "SEMANTIC_PROCESSED_PAGES", "SELECT COUNT(DISTINCT page_id) " +
                    "FROM assistant_chunk WHERE status IN ('READY','SKIPPED')");
            printSingle(statement, "SEMANTIC_MISSING_PAGES", "SELECT COUNT(*) FROM page p WHERE p.code < 400 " +
                    "AND NOT EXISTS (SELECT 1 FROM assistant_chunk c WHERE c.page_id = p.id)");
            printSingle(statement, "DATABASE_BYTES", "SELECT pg_database_size(current_database())");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT status, COUNT(*) FROM assistant_chunk GROUP BY status ORDER BY status")) {
                while (rows.next()) {
                    System.out.println("SEMANTIC_" + rows.getString(1) + "=" + rows.getLong(2));
                }
            }
        }
    }

    private static void printSingle(Statement statement, String name, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            System.out.println(name + "=" + result.getLong(1));
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
