/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.spring.jdbc.db;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.lang.NonNullApi;
import io.micrometer.core.lang.NonNullFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;


/**
 * @author Kristof Depypere
 */
@NonNullApi
@NonNullFields
public class PostgreSQLDatabaseMetrics implements MeterBinder {

    public static final String SELECT = "SELECT ";
    private final Logger logger = LoggerFactory.getLogger(PostgreSQLDatabaseMetrics.class);

    private final String database;
    private final DataSource postgresDataSource;
    private final Iterable<Tag> tags;
    private final Map<String, Double> beforeResetValuesCacheMap;
    private final Map<String, Double> previousValueCacheMap;

    public PostgreSQLDatabaseMetrics(DataSource postgresDataSource, String database) {
        this(postgresDataSource, database, Tags.of(createDbTag(database)));
    }

    private static Tag createDbTag(String database) {
        return Tag.of("database", database);
    }

    public PostgreSQLDatabaseMetrics(DataSource postgresDataSource, String database, Iterable<Tag> tags) {
        this.postgresDataSource = postgresDataSource;
        this.database = database;
        this.tags = Tags.of(tags).and(createDbTag(database));
        this.beforeResetValuesCacheMap = new ConcurrentHashMap<>();
        this.previousValueCacheMap = new ConcurrentHashMap<>();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("postgres.size", postgresDataSource, dataSource -> getDatabaseSize())
            .tags(tags)
            .description("The database size")
            .register(registry);
        Gauge.builder("postgres.connections", postgresDataSource, dataSource -> getConnectionCount())
            .tags(tags)
            .description("Number of active connections to the given db")
            .register(registry);
        Gauge.builder("postgres.hitratio", postgresDataSource, dataSource -> getHitRatio())
            .tags(tags)
            .description("Percentage of blocks in this database that were shared buffer hits vs. read from disk")
            .register(registry);
        FunctionCounter.builder("postgres.transactions", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.transactions", this::getTransactionCount))
            .tags(tags)
            .description("Total number of transactions executed (commits + rollbacks)")
            .register(registry);
        Gauge.builder("postgres.locks.count", postgresDataSource, dataSource -> getLockCount())
            .tags(tags)
            .description("Number of locks on the given db")
            .register(registry);
        FunctionCounter.builder("postgres.tempbytes", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.tempbytes", this::getTempBytes))
            .tags(tags)
            .description("The total amount of temporary bytes written to disk to execute queries")
            .register(registry);

        registerRowCountMetrics(registry);
        registerCheckpointMetrics(registry);
    }

    private void registerRowCountMetrics(MeterRegistry registry) {
        FunctionCounter.builder("postgres.rows.fetched", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.rows.fetched", this::getReadCount))
            .tags(tags)
            .description("Number of rows fetched from the db")
            .register(registry);
        FunctionCounter.builder("postgres.rows.inserted", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.rows.inserted", this::getInsertCount))
            .tags(tags)
            .description("Number of rows inserted from the db")
            .register(registry);
        FunctionCounter.builder("postgres.rows.updated", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.rows.updated", this::getUpdateCount))
            .tags(tags)
            .description("Number of rows updated from the db")
            .register(registry);
        FunctionCounter.builder("postgres.rows.deleted", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.rows.deleted", this::getDeleteCount))
            .tags(tags)
            .description("Number of rows deleted from the db")
            .register(registry);
        Gauge.builder("postgres.rows.dead", postgresDataSource, dataSource -> getDeadTupleCount())
            .tags(tags)
            .description("Total number of dead rows in the current database")
            .register(registry);
    }

    private void registerCheckpointMetrics(MeterRegistry registry) {
        FunctionCounter.builder("postgres.checkpoints.timed", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.checkpoints.timed", this::getTimedCheckpointsCount))
            .tags(tags)
            .description("Number of checkpoints timed")
            .register(registry);
        FunctionCounter.builder("postgres.checkpoints.req", postgresDataSource,
            dataSource -> resettableFunctionalCounter("postgres.checkpoints.req", this::getRequestedCheckpointsCount))
            .tags(tags)
            .description("Number of checkpoints requested")
            .register(registry);
        Gauge.builder("postgres.checkpoints.bufferratio", postgresDataSource, dataSource -> getCheckpointBufferRatio())
            .tags(tags)
            .description("The percentage of total buffer written during a checkpoint vs total buffers written")
            .register(registry);
    }

    protected Long getDatabaseSize() {
        return runQuery("SELECT pg_database_size('" + database + "')", Long.class);
    }

    protected Long getLockCount() {
        return runQuery("SELECT count(*) FROM pg_locks l JOIN pg_database d ON l.DATABASE=d.oid WHERE d.datname='"+database+"'", Long.class);
    }

    protected Long getConnectionCount() {
        String query = getDBStatQuery("SUM(numbackends)");
        return runQuery(query, Long.class);
    }

    protected Long getReadCount() {
        String query = getDBStatQuery("tup_fetched");
        return runQuery(query, Long.class);
    }

    protected Long getInsertCount() {
        String query = getDBStatQuery("tup_inserted");
        return runQuery(query, Long.class);
    }

    protected Long getTempBytes() {
        String query = getDBStatQuery("tmp_bytes");
        return runQuery(query, Long.class);
    }

    protected Long getUpdateCount() {
        String query = getDBStatQuery("tup_updated");
        return runQuery(query, Long.class);
    }

    protected Long getDeleteCount() {
        String query = getDBStatQuery("tup_deleted");
        return runQuery(query, Long.class);
    }

    protected Float getHitRatio() {
        String query = getDBStatQuery("(blks_hit*100)::NUMERIC / (blks_hit+blks_read)");
        return runQuery(query, Float.class);
    }

    protected Long getTransactionCount() {
        String query = getDBStatQuery("xact_commit + xact_rollback");
        return runQuery(query, Long.class);
    }

    protected Integer getDeadTupleCount() {
        String query = getUserTableQuery("n_dead_tup");
        return runQuery(query, Integer.class);
    }

    protected Long getTimedCheckpointsCount() {
        String query = getBgWriterQuery("checkpoints_timed");
        return runQuery(query, Long.class);
    }
    protected Long getRequestedCheckpointsCount() {
        String query = getBgWriterQuery("checkpoints_req");
        return runQuery(query, Long.class);
    }

    protected Float getCheckpointBufferRatio() {
        String query = getBgWriterQuery("(buffers_checkpoint*100)::NUMERIC / (buffers_backend+buffers_clean+buffers_checkpoint)");
        return runQuery(query, Float.class);
    }

    /**
     * Function that makes sure functional counter values survive pg_stat_reset calls.
     */
    protected Double resettableFunctionalCounter(String functionalCounterKey, DoubleSupplier function) {
        Double result = function.getAsDouble();
        Double previousResult = previousValueCacheMap.getOrDefault(functionalCounterKey, 0D);
        Double beforeResetValue = beforeResetValuesCacheMap.getOrDefault(functionalCounterKey, 0D);
        Double correctedValue = result + beforeResetValue;

        if (correctedValue < previousResult) {
            beforeResetValuesCacheMap.put(functionalCounterKey, previousResult);
            correctedValue = previousResult + result;
        }
        previousValueCacheMap.put(functionalCounterKey, correctedValue);
        return correctedValue;
    }

    private <T> T runQuery(String query, Class<T> returnClass) {
        try (Connection connection = postgresDataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet resultSet = statement.executeQuery(query)) {
                    return resultSet.getObject(1, returnClass);
                }
            }
        } catch (SQLException e) {
            logger.error("Error getting statistic from postgreSQL database");
            return null;
        }
    }

    private String getDBStatQuery(String statName) {
        return SELECT + statName + " FROM pg_stat_database WHERE datname = '"+database+"'";
    }

    private String getUserTableQuery(String statName) {
        return SELECT + statName + " FROM pg_stat_user_tables";
    }

    private String getBgWriterQuery(String statName) {
        return SELECT + statName + " FROM pg_stat_bgwriter";
    }
}