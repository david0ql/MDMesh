package com.hmdm.persistence;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <p>Schema drift check: a database created ONLY from the Liquibase changelog must satisfy every SQL statement the
 * server's mappers can send.</p>
 *
 * <p>It exists because of a real incident class: tables and columns were added by hand to the production database and
 * never written as a changeSet, so production worked while every fresh install (and every restore into a new server)
 * was broken. It also loads every mapper the way the server does at start, so a misplaced MyBatis annotation — which
 * once kept the server from starting for ~11 minutes after a deployment — fails here instead of in production.</p>
 *
 * <p>How: it migrates an EMPTY PostgreSQL database with the changelog, then asks PostgreSQL to parse (never run) each
 * mapped statement. "Relation does not exist" (42P01) and "column does not exist" (42703) are drift.</p>
 *
 * <p>Needs a throwaway empty database, so it only runs when {@code SCHEMA_DRIFT_JDBC_URL} is set (plus
 * {@code SCHEMA_DRIFT_DB_USER} / {@code SCHEMA_DRIFT_DB_PASSWORD}); {@code scripts/schema-drift-check.sh} starts one
 * in Docker and runs this test. Never point it at a database that holds data.</p>
 */
public class SchemaDriftTest {

    /**
     * Every changelog a real server start applies: the main one, then the notification module and the plugin
     * platform (it creates the {@code plugins} table the plugins register in), then each bundled plugin. Without the
     * module changelogs a "fresh install" would lack their tables and their role permissions.
     */
    static final String[] CHANGELOGS = {
            "liquibase/db.changelog.xml",
            "liquibase/notification.changelog.xml",
            "liquibase/plugin.changelog.xml",
            "liquibase/devicelog.changelog.xml",
            "liquibase/devicelog.postgres.changelog.xml",
            "liquibase/deviceinfo.changelog.xml",
            "liquibase/audit.changelog.xml",
            "liquibase/messaging.changelog.xml",
            "liquibase/xtra.changelog.xml",
            "liquibase/push.changelog.xml",
    };

    /** A parameter object that answers every property with null, so dynamic SQL renders without real arguments. */
    private static final class AnyParams extends HashMap<String, Object> {
        @Override
        public Object get(Object key) {
            return null;
        }

        @Override
        public boolean containsKey(Object key) {
            return true;
        }
    }

    @Test
    public void everyMappedStatementParsesAgainstAFreshlyMigratedDatabase() throws Exception {
        String url = System.getenv("SCHEMA_DRIFT_JDBC_URL");
        Assume.assumeTrue("SCHEMA_DRIFT_JDBC_URL not set: run scripts/schema-drift-check.sh", url != null && !url.isEmpty());
        String user = System.getenv("SCHEMA_DRIFT_DB_USER");
        String password = System.getenv("SCHEMA_DRIFT_DB_PASSWORD");

        UnpooledDataSource dataSource = new UnpooledDataSource("org.postgresql.Driver", url, user, password);

        // 1. An empty database, migrated as the server does at start (same changelogs, same contexts).
        try (Connection c = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            for (String changeLog : CHANGELOGS) {
                new Liquibase(changeLog, new ClassLoaderResourceAccessor(), database).update("common,private");
            }
        }

        // 2. Every mapper, loaded the way the server's persistence module loads them (annotations + XML).
        Configuration configuration = new Configuration(new Environment("drift", new JdbcTransactionFactory(), dataSource));
        configuration.getTypeAliasRegistry().registerAliases("com.hmdm.persistence.domain");
        configuration.addMappers("com.hmdm.persistence.mapper");

        Map<String, MappedStatement> statements = new TreeMap<>();
        for (Object o : configuration.getMappedStatements()) {
            if (o instanceof MappedStatement) {
                MappedStatement ms = (MappedStatement) o;
                statements.put(ms.getId(), ms);
            }
        }
        Assert.assertTrue("no mapped statements found", statements.size() > 50);

        // 3. Parse each one. Only missing tables/columns count; a statement whose dynamic SQL cannot be rendered
        //    without real arguments, or that is otherwise not parseable with null arguments, is reported as skipped.
        Map<String, String> drift = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        int parsed = 0;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            for (MappedStatement ms : statements.values()) {
                String sql = render(ms);
                if (sql == null) {
                    skipped.add(ms.getId());
                    continue;
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.getParameterMetaData();   // makes the server parse and analyse the statement; nothing runs
                    parsed++;
                } catch (SQLException e) {
                    String state = e.getSQLState();
                    if ("42P01".equals(state) || "42703".equals(state)) {
                        drift.put(ms.getId(), firstLine(e.getMessage()));
                    } else {
                        skipped.add(ms.getId());
                    }
                }
            }
        }
        System.out.println("[schema-drift] statements=" + statements.size() + " parsed=" + parsed
                + " skipped=" + skipped.size() + " drift=" + drift.size());
        if (!drift.isEmpty()) {
            StringBuilder sb = new StringBuilder("The changelog does not create what these statements need "
                    + "(add a changeSet; never alter production by hand):\n");
            for (Map.Entry<String, String> e : drift.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            Assert.fail(sb.toString());
        }
        // The check is only worth something if it really covers the mappers.
        Assert.assertTrue("only " + parsed + " of " + statements.size() + " statements could be parsed",
                parsed * 10 >= statements.size() * 8);
    }

    /** The statement's SQL with JDBC placeholders, rendered with null arguments; null when it cannot be rendered. */
    private static String render(MappedStatement ms) {
        List<Object> candidates = new ArrayList<>();
        candidates.add(new AnyParams());
        Class<?> type = ms.getParameterMap() == null ? null : ms.getParameterMap().getType();
        if (type != null && !type.isInterface() && !type.isPrimitive() && !Map.class.isAssignableFrom(type)) {
            try {
                candidates.add(type.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // no usable default constructor
            }
        }
        candidates.add(null);
        for (Object candidate : candidates) {
            try {
                BoundSql bound = ms.getBoundSql(candidate);
                if (bound.getSql() != null && !bound.getSql().trim().isEmpty()) {
                    return bound.getSql();
                }
            } catch (RuntimeException ignored) {
                // try the next kind of argument
            }
        }
        return null;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
