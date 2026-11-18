package com.anode.productdesigner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Un Postgres 17 pour toute la JVM de test (Testcontainers) et, par classe de test, une base neuve : Flyway la migre au premier
 * accès du contexte Spring. Chaque classe garde ainsi ses données propres, comme avec un dossier temporaire sous SQLite.
 */
public final class TestDb {

    private TestDb() {}

    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine").withCommand("postgres", "-c", "max_connections=300");
    private static final AtomicInteger N = new AtomicInteger();

    static {
        PG.start();
    }

    /** L'URL JDBC d'une base vide. */
    static synchronized String fraiche() {
        String nom = "pd" + N.incrementAndGet();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            c.createStatement().execute("CREATE DATABASE " + nom);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return PG.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + nom + "$1");
    }

    /** À appeler depuis le @DynamicPropertySource de la classe de test. */
    public static void enregistrer(DynamicPropertyRegistry registry) {
        String url = fraiche();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }
}
