package com.nousresearch.hermes.connector.builtin;

import com.nousresearch.hermes.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * E3: Generic SQL Connector.
 *
 * <p>Executes SQL queries against any JDBC-compatible database.
 * Configured with JDBC URL, username, and password.</p>
 *
 * <p>Operations:</p>
 * <ul>
 *   <li>query - SELECT query, returns rows as list of maps</li>
 *   <li>execute - INSERT/UPDATE/DELETE, returns affected row count</li>
 *   <li>tables - list tables in the database</li>
 * </ul>
 *
 * <p>Security: Only SELECT is allowed for query operation.
 * INSERT/UPDATE/DELETE require explicit execute operation.
 * DDL (DROP/ALTER/TRUNCATE) is blocked by default.</p>
 */
public class SqlConnector implements Connector {

    private static final Logger logger = LoggerFactory.getLogger(SqlConnector.class);

    private String jdbcUrl;
    private String username;
    private String password;
    private String driverClass;
    private Connection connection;

    @Override
    public String getName() { return "sql"; }

    @Override
    public String getLabel() { return "SQL Database"; }

    @Override
    public String getDescription() {
        return "Universal SQL database connector. Supports query (SELECT) and execute (INSERT/UPDATE/DELETE).";
    }

    @Override
    public boolean testConnection() {
        try (Connection conn = getConnection()) {
            return conn.isValid(5);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> execute(String operation, Map<String, Object> params) {
        String sql = (String) params.get("sql");
        if (sql == null || sql.isBlank()) {
            return Map.of("success", false, "error", "SQL is required");
        }

        // Security: block DDL
        String upperSql = sql.trim().toUpperCase();
        if (upperSql.startsWith("DROP") || upperSql.startsWith("ALTER")
            || upperSql.startsWith("TRUNCATE") || upperSql.startsWith("CREATE")) {
            return Map.of("success", false, "error", "DDL statements are not allowed");
        }

        try (Connection conn = getConnection()) {
            switch (operation) {
                case "query" -> {
                    // Only allow SELECT
                    if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")
                        && !upperSql.startsWith("SHOW") && !upperSql.startsWith("DESCRIBE")
                        && !upperSql.startsWith("EXPLAIN")) {
                        return Map.of("success", false, "error", "Query operation only allows SELECT/WITH/SHOW/DESCRIBE/EXPLAIN");
                    }
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        int columnCount = rs.getMetaData().getColumnCount();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= columnCount; i++) {
                                String colName = rs.getMetaData().getColumnLabel(i);
                                Object value = rs.getObject(i);
                                row.put(colName, value);
                            }
                            rows.add(row);
                        }
                        return Map.of("success", true, "rows", rows, "rowCount", rows.size());
                    }
                }
                case "execute" -> {
                    try (Statement stmt = conn.createStatement()) {
                        int affected = stmt.executeUpdate(sql);
                        return Map.of("success", true, "affectedRows", affected);
                    }
                }
                case "tables" -> {
                    try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                        List<String> tables = new ArrayList<>();
                        while (rs.next()) {
                            tables.add(rs.getString("TABLE_NAME"));
                        }
                        return Map.of("success", true, "tables", tables);
                    }
                }
                default -> {
                    return Map.of("success", false, "error", "Unknown operation: " + operation);
                }
            }
        } catch (SQLException e) {
            logger.error("SQL operation failed: {} - {}", operation, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @Override
    public List<ConnectorOperation> getSupportedOperations() {
        return List.of(
            new ConnectorOperation("query", "SQL Query", "Execute a SELECT query",
                Map.of("sql", Map.of("type", "string", "required", true)),
                Map.of("rows", Map.of("type", "array"), "rowCount", Map.of("type", "integer"))),
            new ConnectorOperation("execute", "SQL Execute", "Execute INSERT/UPDATE/DELETE",
                Map.of("sql", Map.of("type", "string", "required", true)),
                Map.of("affectedRows", Map.of("type", "integer"))),
            new ConnectorOperation("tables", "List Tables", "List all tables in the database",
                Map.of(),
                Map.of("tables", Map.of("type", "array")))
        );
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("jdbcUrl", Map.of("type", "string", "required", true, "label", "JDBC URL"));
        schema.put("username", Map.of("type", "string", "required", true));
        schema.put("password", Map.of("type", "password", "required", true));
        schema.put("driverClass", Map.of("type", "string", "required", false,
            "description", "JDBC driver class (e.g. com.mysql.cj.jdbc.Driver)"));
        return schema;
    }

    @Override
    public void configure(Map<String, Object> config) {
        this.jdbcUrl = (String) config.get("jdbcUrl");
        this.username = (String) config.get("username");
        this.password = (String) config.get("password");
        this.driverClass = (String) config.get("driverClass");
        // Close existing connection
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
        }
    }

    @Override
    public boolean isHealthy() {
        return testConnection();
    }

    // ============ Internal ============

    private Connection getConnection() throws SQLException {
        if (connection != null && connection.isValid(5)) {
            return connection;
        }
        if (driverClass != null && !driverClass.isBlank()) {
            try {
                Class.forName(driverClass);
            } catch (ClassNotFoundException e) {
                throw new SQLException("Driver not found: " + driverClass);
            }
        }
        connection = DriverManager.getConnection(jdbcUrl, username, password);
        return connection;
    }
}
