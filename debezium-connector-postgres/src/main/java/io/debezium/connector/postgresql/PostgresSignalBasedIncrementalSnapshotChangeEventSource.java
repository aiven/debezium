/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.postgresql;

import static io.debezium.connector.postgresql.PostgresConnectorConfig.IncrementalSnapshotChunkQueryBuilderType.SIMPLE;
import static io.debezium.connector.postgresql.PostgresConnectorConfig.IncrementalSnapshotChunkQueryBuilderType.UNION_BASED;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.snapshot.incremental.SignalBasedIncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.Column;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.DatabaseSchema;
import io.debezium.util.Clock;

/**
 * Custom PostgreSQL implementation of the {@link SignalBasedIncrementalSnapshotChangeEventSource} implementation
 * which performs an explicit schema refresh of a table prior to the incremental snapshot starting.
 *
 * @author Chris Cranford
 */
public class PostgresSignalBasedIncrementalSnapshotChangeEventSource
        extends SignalBasedIncrementalSnapshotChangeEventSource<PostgresPartition, TableId> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresSignalBasedIncrementalSnapshotChangeEventSource.class);

    private final PostgresConnection jdbcConnection;
    private final PostgresSchema schema;
    private final PostgresConnectorConfig.IncrementalSnapshotChunkQueryBuilderType queryBuilderType;

    public PostgresSignalBasedIncrementalSnapshotChangeEventSource(PostgresConnectorConfig config,
                                                                   JdbcConnection jdbcConnection,
                                                                   EventDispatcher<PostgresPartition, TableId> dispatcher,
                                                                   DatabaseSchema<?> databaseSchema,
                                                                   Clock clock,
                                                                   SnapshotProgressListener<PostgresPartition> progressListener,
                                                                   DataChangeEventListener<PostgresPartition> dataChangeEventListener,
                                                                   NotificationService<PostgresPartition, ? extends OffsetContext> notificationService) {
        super(config, jdbcConnection, dispatcher, databaseSchema, clock, progressListener, dataChangeEventListener, notificationService);
        this.jdbcConnection = (PostgresConnection) jdbcConnection;
        this.schema = (PostgresSchema) databaseSchema;
        this.queryBuilderType = config.getIncrementalSnapshotQueryBuilderType();
    }

    @Override
    protected Table refreshTableSchema(Table table) throws SQLException {
        LOGGER.debug("Refreshing table '{}' schema for incremental snapshot.", table.id());
        schema.refresh(jdbcConnection, table.id(), true);
        return schema.tableFor(table.id());
    }

    @Override
    protected String buildChunkQuery(Table table, int limit, Optional<String> additionalCondition) {
        // Select query builder based on the configuration only on PostgreSQL Connector (incremental.snapshot.query.builder.type)
        if (queryBuilderType == UNION_BASED) {
            return buildUnionBasedChunkQuery(table, limit, additionalCondition);
        }

        return super.buildChunkQuery(table, limit, additionalCondition);
    }

    @Override
    protected PreparedStatement readTableChunkStatement(String sql) throws SQLException {
        if (queryBuilderType == SIMPLE) {
            return super.readTableChunkStatement(sql);
        }

        final PreparedStatement statement = jdbcConnection.readTablePreparedStatement(connectorConfig, sql,
                OptionalLong.empty());
        if (context.isNonInitialChunk()) {
            final Object[] maximumKey = context.maximumKey().get();
            final Object[] chunkEndPosition = context.chunkEndPosititon();
            // Fill boundaries placeholders
            int pos = 0;
            final List<Column> queryColumns = getQueryColumns(getCurrentTable());

            // Loop over each boundary condition
            for (int i = 0; i < chunkEndPosition.length; i++) {
                // Fill lower bound placeholders for each column
                for (int j = 0; j < i + 1; j++) {
                    jdbcConnection.setQueryColumnValue(statement, queryColumns.get(j), ++pos, chunkEndPosition[j]);
                }
                // Fill upper bound placeholders for each column
                for (int j = 0; j < i + 1; j++) {
                    jdbcConnection.setQueryColumnValue(statement, queryColumns.get(j), ++pos, maximumKey[j]);
                }
            }
        }
        return statement;
    }

    private String buildUnionBasedChunkQuery(Table table, int limit, Optional<String> additionalCondition) {
        List<String> boundConditions = Collections.emptyList();

        // Add condition when this is not the first query
        if (context.isNonInitialChunk()) {
            // Window boundaries
            boundConditions = getBoundConditions(table);
        }

        List<String> orderByColumnNames = getQueryColumns(table).stream()
                .map(Column::name)
                .collect(Collectors.toList());

        return jdbcConnection.buildUnionBasedSelectWithRowLimits(table.id(),
                limit,
                buildProjection(table),
                boundConditions,
                additionalCondition,
                orderByColumnNames);
    }

    private List<String> getBoundConditions(Table table) {
        // Provides boundary conditions for the incremental snapshot query
        // For one column the condition will be (? will always be the last value seen for the given column)
        // (k1 > ?) AND (k1 <= ?)
        // For two columns
        // (k1 > ?) AND (k1 <= ?)
        // (k1 = ? AND k2 > ?) AND (k1 <> ? OR k2 <= ?)
        // For three columns
        // (k1 > ?) AND (k1 <= ?)
        // (k1 = ? AND k2 > ?) AND (k1 <> ? OR k2 <= ?)
        // (k1 >= ? AND k2 >= ? AND k3 > ?) AND (k1 <> ? OR k2 <> ? OR k3 <= ?)
        // For four columns
        // (k1 > ?) AND (k1 <= ?)
        // (k1 = ? AND k2 > ?) AND (k1 <> ? OR k2 <= ?)
        // (k1 >= ? AND k2 >= ? AND k3 > ?) AND (k1 <> ? OR k2 <> ? OR k3 <= ?)
        // (k1 = ? AND k2 = ? AND k3 = ? AND k4 > ?) AND (k1 <> ? OR k2 <> ? OR k3 <> ? OR k4 <= ?)
        // etc.
        List<String> conditionList = new ArrayList<>();
        final List<Column> pkColumns = getQueryColumns(table);

        for (int i = 0; i < pkColumns.size(); i++) {
            StringBuilder sql = new StringBuilder("(");

            for (int j = 0; j < i + 1; j++) {
                final boolean isLastIterationForJ = (i == j);

                sql.append(jdbcConnection.quotedColumnIdString(pkColumns.get(j).name()));
                sql.append(isLastIterationForJ ? " > ?" : " = ?");

                if (!isLastIterationForJ) {
                    sql.append(" AND ");
                }
            }

            sql.append(") AND (");

            for (int j = 0; j < i + 1; j++) {
                final boolean isLastIterationForJ = (i == j);

                sql.append(jdbcConnection.quotedColumnIdString(pkColumns.get(j).name()));
                sql.append(isLastIterationForJ ? " <= ?" : " <> ?");

                if (!isLastIterationForJ) {
                    sql.append(" OR ");
                }
            }

            sql.append(")");
            conditionList.add(sql.toString());
        }

        return conditionList;
    }

    private Table getCurrentTable() {
        final TableId currentTableId = context.currentDataCollectionId().getId();
        final Table cTable = schema.tableFor(currentTableId);
        if (cTable == null) {
            throw new IllegalStateException("Table " + currentTableId + " not found in schema");
        }
        return cTable;
    }

}
