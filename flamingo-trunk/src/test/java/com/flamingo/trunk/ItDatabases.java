package com.flamingo.trunk;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * DB-test isolation on the local compose Postgres (host :5434).
 *
 * <p>Testcontainers cannot reach Docker's named pipe from the Maven JVM on this
 * Windows host (verified 2026-08-27), so DB-backed tests get isolation via
 * PER-RUN DATABASES instead: each suite DROPs + CREATEs its own database —
 * fresh V1–V8 schema every run, zero contact with the dev (`flamingo`) or
 * contract-test (`flamingo_test`) databases. The isolation guarantee survives;
 * only the mechanism differs.</p>
 */
public final class ItDatabases {

    public record Instance(String jdbcUrl, String user, String password) {}

    private ItDatabases() {}

    public static Instance prepare(String dbName) throws Exception {
        String adminUrl = "jdbc:postgresql://"
                + env("FLYWAY_DB_HOST", "localhost") + ":"
                + env("FLYWAY_DB_PORT", "5434") + "/postgres";
        String user = env("FLYWAY_DB_USER", "flamingo");
        String pass = env("FLYWAY_DB_PASSWORD", "flamingo");
        try (Connection c = DriverManager.getConnection(adminUrl, user, pass);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + dbName);
        }
        return new Instance(
                "jdbc:postgresql://" + env("FLYWAY_DB_HOST", "localhost") + ":"
                        + env("FLYWAY_DB_PORT", "5434") + "/" + dbName,
                user, pass);
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
