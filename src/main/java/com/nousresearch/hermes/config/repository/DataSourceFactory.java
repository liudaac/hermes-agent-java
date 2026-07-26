package com.nousresearch.hermes.config.repository;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * HikariCP DataSource factory for MySQL connections.
 *
 * <p>Configuration via system properties or environment variables:</p>
 * <pre>
 * -Ddb.url=jdbc:mysql://localhost:3306/hermes
 * -Ddb.username=hermes
 * -Ddb.password=secret
 * -Ddb.pool.size=10
 * </pre>
 *
 * Or environment variables:
 * <pre>
 * DB_URL=jdbc:mysql://localhost:3306/hermes
 * DB_USERNAME=hermes
 * DB_PASSWORD=secret
 * </pre>
 */
public class DataSourceFactory {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceFactory.class);
    private static volatile DataSource sharedDataSource;

    /**
     * Create a HikariCP DataSource from system properties / env vars.
     */
    public static DataSource create() {
        if (sharedDataSource != null) {
            return sharedDataSource;
        }
        synchronized (DataSourceFactory.class) {
            if (sharedDataSource != null) {
                return sharedDataSource;
            }
            String url = getProp("db.url", "jdbc:mysql://localhost:3306/hermes");
            String username = getProp("db.username", "root");
            String password = getProp("db.password", "");
            int poolSize = Integer.parseInt(getProp("db.pool.size", "10"));

            sharedDataSource = create(url, username, password, poolSize);
            return sharedDataSource;
        }
    }

    /**
     * Create a HikariCP DataSource with explicit parameters.
     */
    public static DataSource create(String url, String username, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setLeakDetectionThreshold(60_000);
        config.setPoolName("hermes-config-pool");

        // MySQL optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "128");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useSSL", "true");
        config.addDataSourceProperty("serverTimezone", "Asia/Shanghai");

        logger.info("HikariCP DataSource created: url={}, poolSize={}", url, poolSize);
        return new HikariDataSource(config);
    }

    /**
     * Set a shared DataSource (for testing or custom init).
     */
    public static void setSharedDataSource(DataSource ds) {
        sharedDataSource = ds;
    }

    private static String getProp(String key, String defaultValue) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(key.replace(".", "_").toUpperCase());
        if (val != null && !val.isBlank()) return val;
        return defaultValue;
    }
}
