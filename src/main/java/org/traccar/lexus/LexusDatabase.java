package org.traccar.lexus;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second database connection, to the OpenGTS schema. Runs the SQL written in
 * the configuration with named parameters (":name"), the way the 3.2 fork did
 * with database.insertPosition and friends. Traccar keeps its own database.
 */
@Singleton
public class LexusDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexusDatabase.class);

    private record ParsedQuery(String sql, List<String> parameters) {
    }

    private final LexusConfig config;
    private final Map<String, ParsedQuery> queries = new ConcurrentHashMap<>();
    private HikariDataSource dataSource;

    @Inject
    public LexusDatabase(LexusConfig config) {
        this.config = config;
        String url = config.getString("lexus.database.url");
        if (config.isEnabled() && url != null) {
            HikariConfig hikari = new HikariConfig();
            String driver = config.getString("lexus.database.driver");
            if (driver != null) {
                hikari.setDriverClassName(driver);
            }
            hikari.setJdbcUrl(url);
            hikari.setUsername(config.getString("lexus.database.user"));
            hikari.setPassword(config.getString("lexus.database.password"));
            hikari.setMaximumPoolSize(config.getInteger("lexus.database.maxPoolSize", 10));
            hikari.setPoolName("lexus");
            // do not stop the whole server when OpenGTS is down at start-up
            hikari.setInitializationFailTimeout(-1);
            dataSource = new HikariDataSource(hikari);
        }
    }

    public boolean isAvailable() {
        return dataSource != null;
    }

    public boolean hasQuery(String key) {
        return isAvailable() && config.getString(key) != null;
    }

    /** Same rules as the old QueryBuilder: ":name" outside quotes, names are case insensitive. */
    static ParsedQuery parse(String query) {
        int length = query.length();
        StringBuilder sql = new StringBuilder(length);
        List<String> parameters = new ArrayList<>();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < length; i++) {
            char c = query.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                }
            } else if (c == '\'') {
                inSingleQuote = true;
            } else if (c == '"') {
                inDoubleQuote = true;
            } else if (c == ':' && i + 1 < length && Character.isJavaIdentifierStart(query.charAt(i + 1))) {
                int j = i + 2;
                while (j < length && Character.isJavaIdentifierPart(query.charAt(j))) {
                    j++;
                }
                parameters.add(query.substring(i + 1, j).toLowerCase());
                c = '?';
                i = j - 1;
            }
            sql.append(c);
        }
        return new ParsedQuery(sql.toString(), parameters);
    }

    private PreparedStatement prepare(
            Connection connection, String key, Map<String, Object> values) throws SQLException {
        ParsedQuery query = queries.computeIfAbsent(key, k -> parse(config.getString(k).trim()));
        PreparedStatement statement = connection.prepareStatement(query.sql());
        int index = 1;
        for (String name : query.parameters()) {
            Object value = values.get(name);
            if (value == null) {
                statement.setNull(index, Types.NULL);
            } else if (value instanceof Date date) {
                statement.setTimestamp(index, new Timestamp(date.getTime()));
            } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                    || value instanceof Double || value instanceof String) {
                statement.setObject(index, value);
            } else {
                statement.setString(index, value.toString());
            }
            index++;
        }
        return statement;
    }

    /** Parameter names must be given in lower case. */
    public void update(String key, Map<String, Object> values) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, key, values)) {
            statement.execute();
        }
    }

    /** First row of the result, column labels in lower case; null when there is no row. */
    public Map<String, Object> selectFirst(String key, Map<String, Object> values) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, key, values);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            ResultSetMetaData metaData = resultSet.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                row.put(metaData.getColumnLabel(i).toLowerCase(), resultSet.getObject(i));
            }
            return row;
        }
    }

    /** First column of every row, as text; null values are skipped. */
    public List<String> selectColumn(String key, Map<String, Object> values) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, key, values);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String value = resultSet.getString(1);
                if (value != null) {
                    result.add(value.trim());
                }
            }
        }
        return result;
    }

    public static Map<String, Object> parameters() {
        return new HashMap<>();
    }

    public void close() {
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (RuntimeException e) {
                LOGGER.warn("Lexus database close failed", e);
            }
        }
    }

}
