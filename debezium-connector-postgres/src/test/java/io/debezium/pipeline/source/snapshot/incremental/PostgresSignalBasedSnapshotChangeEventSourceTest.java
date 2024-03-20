/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline.source.snapshot.incremental;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.connector.postgresql.PostgresConnectorConfig;
import io.debezium.connector.postgresql.PostgresSignalBasedIncrementalSnapshotChangeEventSource;
import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.relational.Column;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;

public class PostgresSignalBasedSnapshotChangeEventSourceTest {

    public static final String CONNECTION_TEST = "Debezium Test";

    protected PostgresConnectorConfig config() {
        return new PostgresConnectorConfig(Configuration.create()
                .with(RelationalDatabaseConnectorConfig.SIGNAL_DATA_COLLECTION, "debezium.signal")
                .with(RelationalDatabaseConnectorConfig.TOPIC_PREFIX, "core")
                .with(PostgresConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_QUERY_BUILDER_TYPE, "union_based")
                .build());
    }

    @Test
    public void testBuildUnionQueryOnePkColumn() {
        final PostgresSignalBasedIncrementalSnapshotChangeEventSource source = new PostgresSignalBasedIncrementalSnapshotChangeEventSource(
                config(), new PostgresConnection(config().getJdbcConfig(), CONNECTION_TEST), null, null, null, SnapshotProgressListener.NO_OP(),
                DataChangeEventListener.NO_OP(), null);
        final IncrementalSnapshotContext<TableId> context = new SignalBasedIncrementalSnapshotContext<>();
        source.setContext(context);
        final Column pk1 = Column.editor().name("pk1").create();
        final Column val1 = Column.editor().name("val1").create();
        final Column val2 = Column.editor().name("val2").create();
        final Table table = Table.editor().tableId(new TableId(null, "s1", "table1"))
                .addColumn(pk1)
                .addColumn(val1)
                .addColumn(val2)
                .setPrimaryKeyNames("pk1").create();
        assertThat(source.buildChunkQuery(table, Optional.empty())).isEqualTo("SELECT * FROM \"s1\".\"table1\" ORDER BY \"pk1\" LIMIT 1024");
        context.nextChunkPosition(new Object[]{ 1, 5 });
        context.maximumKey(new Object[]{ 10, 50 });
        assertThat(source.buildChunkQuery(table, Optional.empty())).isEqualTo(
                "SELECT * FROM \"s1\".\"table1\" WHERE (\"pk1\" > ?) AND (\"pk1\" <= ?) ORDER BY \"pk1\" LIMIT 1024");
    }

    @Test
    public void testBuildUnionQueryOnePkColumnWithAdditionalCondition() {
        final PostgresSignalBasedIncrementalSnapshotChangeEventSource source = new PostgresSignalBasedIncrementalSnapshotChangeEventSource(
                config(), new PostgresConnection(config().getJdbcConfig(), CONNECTION_TEST), null, null, null, SnapshotProgressListener.NO_OP(),
                DataChangeEventListener.NO_OP(), null);
        final IncrementalSnapshotContext<TableId> context = new SignalBasedIncrementalSnapshotContext<>();
        source.setContext(context);
        final Column pk1 = Column.editor().name("pk1").create();
        final Column val1 = Column.editor().name("val1").create();
        final Column val2 = Column.editor().name("val2").create();
        final Table table = Table.editor().tableId(new TableId(null, "s1", "table1"))
                .addColumn(pk1)
                .addColumn(val1)
                .addColumn(val2)
                .setPrimaryKeyNames("pk1").create();
        assertThat(source.buildChunkQuery(table, Optional.of("\"val1\"=foo")))
                .isEqualTo("SELECT * FROM \"s1\".\"table1\" WHERE \"val1\"=foo ORDER BY \"pk1\" LIMIT 1024");
        context.nextChunkPosition(new Object[]{ 1, 5 });
        context.maximumKey(new Object[]{ 10, 50 });
        assertThat(source.buildChunkQuery(table, Optional.of("\"val1\"=foo"))).isEqualTo(
                "SELECT * FROM \"s1\".\"table1\" WHERE (\"pk1\" > ?) AND (\"pk1\" <= ?) AND \"val1\"=foo ORDER BY \"pk1\" LIMIT 1024");
    }

    @Test
    public void testBuildUnionQueryTwoPkColumnsWithAdditionalConditionWithSurrogateKey() {
        final PostgresSignalBasedIncrementalSnapshotChangeEventSource source = new PostgresSignalBasedIncrementalSnapshotChangeEventSource(
                config(), new PostgresConnection(config().getJdbcConfig(), CONNECTION_TEST), null, null, null, SnapshotProgressListener.NO_OP(),
                DataChangeEventListener.NO_OP(), null);
        final IncrementalSnapshotContext<TableId> context = new SignalBasedIncrementalSnapshotContext<>();
        source.setContext(context);
        final Column pk1 = Column.editor().name("pk1").create();
        final Column pk2 = Column.editor().name("pk2").create();
        final Column val1 = Column.editor().name("val1").create();
        final Column val2 = Column.editor().name("val2").create();
        final Table table = Table.editor().tableId(new TableId(null, "s1", "table1"))
                .addColumn(pk1)
                .addColumn(pk2)
                .addColumn(val1)
                .addColumn(val2)
                .setPrimaryKeyNames("pk1", "pk2").create();
        context.addDataCollectionNamesToSnapshot("12345", List.of(table.id().toString()), List.of(), "pk2");
        assertThat(source.buildChunkQuery(table, Optional.of("\"val1\"=foo")))
                .isEqualTo("SELECT * FROM \"s1\".\"table1\" WHERE \"val1\"=foo ORDER BY \"pk2\" LIMIT 1024");
        context.nextChunkPosition(new Object[]{ 1, 5 });
        context.maximumKey(new Object[]{ 10, 50 });
        assertThat(source.buildChunkQuery(table, Optional.of("\"val1\"=foo"))).isEqualTo(
                "SELECT * FROM \"s1\".\"table1\" WHERE (\"pk2\" > ?) AND (\"pk2\" <= ?) AND \"val1\"=foo ORDER BY \"pk2\" LIMIT 1024");
    }

    @Test
    public void testBuildUnionQueryThreePkColumns() {
        final PostgresSignalBasedIncrementalSnapshotChangeEventSource source = new PostgresSignalBasedIncrementalSnapshotChangeEventSource(
                config(), new PostgresConnection(config().getJdbcConfig(), CONNECTION_TEST), null, null, null, SnapshotProgressListener.NO_OP(),
                DataChangeEventListener.NO_OP(), null);
        final IncrementalSnapshotContext<TableId> context = new SignalBasedIncrementalSnapshotContext<>();
        source.setContext(context);
        final Column pk1 = Column.editor().name("pk1").create();
        final Column pk2 = Column.editor().name("pk2").create();
        final Column pk3 = Column.editor().name("pk3").create();
        final Column val1 = Column.editor().name("val1").create();
        final Column val2 = Column.editor().name("val2").create();
        final Table table = Table.editor().tableId(new TableId(null, "s1", "table1"))
                .addColumn(pk1)
                .addColumn(pk2)
                .addColumn(pk3)
                .addColumn(val1)
                .addColumn(val2)
                .setPrimaryKeyNames("pk1", "pk2", "pk3").create();
        assertThat(source.buildChunkQuery(table, Optional.empty()))
                .isEqualTo("SELECT * FROM \"s1\".\"table1\" ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024");
        context.nextChunkPosition(new Object[]{ 1, 5 });
        context.nextChunkPosition(new Object[]{ 1, 5 });
        context.maximumKey(new Object[]{ 10, 50 });
        String result = source.buildChunkQuery(table, Optional.empty());
        assertThat(result).isEqualTo(
                "SELECT * FROM (" +
                        "(SELECT * FROM \"s1\".\"table1\" " +
                        "WHERE (\"pk1\" > ?) AND (\"pk1\" <= ?) " +
                        "ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024) " +
                        "UNION " +
                        "(SELECT * FROM \"s1\".\"table1\" " +
                        "WHERE (\"pk1\" = ? AND \"pk2\" > ?) AND (\"pk1\" <> ? OR \"pk2\" <= ?) " +
                        "ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024) " +
                        "UNION " +
                        "(SELECT * FROM \"s1\".\"table1\" " +
                        "WHERE (\"pk1\" = ? AND \"pk2\" = ? AND \"pk3\" > ?) AND (\"pk1\" <> ? OR \"pk2\" <> ? OR \"pk3\" <= ?) " +
                        "ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024)" +
                        ") AS t " +
                        "ORDER BY \"t\".\"pk1\", \"t\".\"pk2\", \"t\".\"pk3\" LIMIT 1024");
    }

    @Test
    public void testBuildUnionQueryThreePkColumnsWithAdditionalCondition() {
        final PostgresSignalBasedIncrementalSnapshotChangeEventSource source = new PostgresSignalBasedIncrementalSnapshotChangeEventSource(
                config(), new PostgresConnection(config().getJdbcConfig(), CONNECTION_TEST), null, null, null, SnapshotProgressListener.NO_OP(),
                DataChangeEventListener.NO_OP(), null);
        final IncrementalSnapshotContext<TableId> context = new SignalBasedIncrementalSnapshotContext<>();
        source.setContext(context);
        final Column pk1 = Column.editor().name("pk1").create();
        final Column pk2 = Column.editor().name("pk2").create();
        final Column pk3 = Column.editor().name("pk3").create();
        final Column val1 = Column.editor().name("val1").create();
        final Column val2 = Column.editor().name("val2").create();
        final Table table = Table.editor().tableId(new TableId(null, "s1", "table1"))
                .addColumn(pk1)
                .addColumn(pk2)
                .addColumn(pk3)
                .addColumn(val1)
                .addColumn(val2)
                .setPrimaryKeyNames("pk1", "pk2", "pk3").create();
        assertThat(source.buildChunkQuery(table, Optional.of("\"val1\"=foo")))
                .isEqualTo("SELECT * FROM \"s1\".\"table1\" WHERE \"val1\"=foo ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024");
        context.nextChunkPosition(new Object[]{ 1, 5 });
        context.maximumKey(new Object[]{ 10, 50 });
        assertThat(source.buildChunkQuery(table, Optional.of("\"val1\"=foo"))).isEqualTo(
                "SELECT * FROM (" +
                        "(SELECT * FROM \"s1\".\"table1\" " +
                        "WHERE (\"pk1\" > ?) AND (\"pk1\" <= ?) AND \"val1\"=foo " +
                        "ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024) " +
                        "UNION " +
                        "(SELECT * FROM \"s1\".\"table1\" " +
                        "WHERE (\"pk1\" = ? AND \"pk2\" > ?) AND (\"pk1\" <> ? OR \"pk2\" <= ?) AND \"val1\"=foo " +
                        "ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024) " +
                        "UNION " +
                        "(SELECT * FROM \"s1\".\"table1\" " +
                        "WHERE (\"pk1\" = ? AND \"pk2\" = ? AND \"pk3\" > ?) AND (\"pk1\" <> ? OR \"pk2\" <> ? OR \"pk3\" <= ?) AND \"val1\"=foo " +
                        "ORDER BY \"pk1\", \"pk2\", \"pk3\" LIMIT 1024)" +
                        ") AS t " +
                        "ORDER BY \"t\".\"pk1\", \"t\".\"pk2\", \"t\".\"pk3\" LIMIT 1024");
    }
}
