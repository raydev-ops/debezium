/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.debezium.connector.postgresql.snapshot.InitialOnlySnapshotter;
import io.debezium.connector.postgresql.snapshot.SnapshotterWrapper;
import io.debezium.connector.postgresql.spi.Snapshotter;
import org.apache.kafka.connect.source.SourceRecord;
import org.fest.assertions.Assertions;
import org.junit.After;
import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.relational.TableId;
import io.debezium.schema.TopicSelector;

/**
 * Integration test for {@link io.debezium.connector.postgresql.PostgresConnectorConfig.SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE}
 *
 * @author Jiri Pechanec (jpechane@redhat.com)
 */
public class SnapshotWithOverridesProducerIT extends AbstractRecordsProducerTest {

    private static final String STATEMENTS =
            "CREATE SCHEMA over;" +
            "CREATE TABLE over.t1 (pk INT, PRIMARY KEY(pk));" +
            "CREATE TABLE over.t2 (pk INT, PRIMARY KEY(pk));" +
            "INSERT INTO over.t1 VALUES (1);" +
            "INSERT INTO over.t1 VALUES (2);" +
            "INSERT INTO over.t1 VALUES (3);" +
            "INSERT INTO over.t1 VALUES (101);" +
            "INSERT INTO over.t1 VALUES (102);" +
            "INSERT INTO over.t1 VALUES (103);" +
            "INSERT INTO over.t2 VALUES (1);" +
            "INSERT INTO over.t2 VALUES (2);" +
            "INSERT INTO over.t2 VALUES (3);" +
            "INSERT INTO over.t2 VALUES (101);" +
            "INSERT INTO over.t2 VALUES (102);" +
            "INSERT INTO over.t2 VALUES (103);";

    private RecordsSnapshotProducer snapshotProducer;
    private PostgresTaskContext context;
    private PostgresConnectorConfig config;

    public void before(Configuration overrides) throws SQLException {
        TestHelper.dropAllSchemas();

        config = new PostgresConnectorConfig(TestHelper.defaultConfig().with(overrides).build());
        TopicSelector<TableId> selector = PostgresTopicSelector.create(config);
        context = new PostgresTaskContext(
                config,
                TestHelper.getSchema(config),
                selector
        );
    }

    @After
    public void after() throws Exception {
        if (snapshotProducer != null) {
            snapshotProducer.stop();
        }
    }

    @Test
    public void shouldUseOverriddenSelectStatementDuringSnapshotting() throws Exception {
        before(Configuration.create()
                .with(PostgresConnectorConfig.SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE, "over.t1")
                .with(PostgresConnectorConfig.SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE.name() + ".over.t1", "SELECT * FROM over.t1 WHERE pk > 100")
                .build());
        snapshotProducer = buildStreamProducer(context, config);

        final int expectedRecordsCount = 3 + 6;

        TestHelper.execute(STATEMENTS);
        TestConsumer consumer = testConsumer(expectedRecordsCount, "over");

        snapshotProducer.start(consumer, e -> {});
        consumer.await(TestHelper.waitTimeForRecords(), TimeUnit.SECONDS);

        final Map<String, List<SourceRecord>> recordsByTopic = recordsByTopic(expectedRecordsCount, consumer);
        Assertions.assertThat(recordsByTopic.get("test_server.over.t1")).hasSize(3);
        Assertions.assertThat(recordsByTopic.get("test_server.over.t2")).hasSize(6);
    }

    @Test
    public void shouldUseMultipleOverriddenSelectStatementsDuringSnapshotting() throws Exception {
        before(Configuration.create()
                .with(PostgresConnectorConfig.SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE, "over.t1,over.t2")
                .with(PostgresConnectorConfig.SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE.name() + ".over.t1", "SELECT * FROM over.t1 WHERE pk > 101")
                .with(PostgresConnectorConfig.SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE.name() + ".over.t2", "SELECT * FROM over.t2 WHERE pk > 100")
                .build());
        snapshotProducer = buildStreamProducer(context, config);

        final int expectedRecordsCount = 2 + 3;

        TestHelper.execute(STATEMENTS);
        TestConsumer consumer = testConsumer(expectedRecordsCount, "over");

        snapshotProducer.start(consumer, e -> {});
        consumer.await(TestHelper.waitTimeForRecords(), TimeUnit.SECONDS);

        final Map<String, List<SourceRecord>> recordsByTopic = recordsByTopic(expectedRecordsCount, consumer);
        Assertions.assertThat(recordsByTopic.get("test_server.over.t1")).hasSize(2);
        Assertions.assertThat(recordsByTopic.get("test_server.over.t2")).hasSize(3);
    }

    private Map<String, List<SourceRecord>> recordsByTopic(final int expectedRecordsCount, TestConsumer consumer) {
        final Map<String, List<SourceRecord>> recordsByTopic = new HashMap<>();
        for (int i = 0; i < expectedRecordsCount; i++) {
            final SourceRecord record = consumer.remove();
            recordsByTopic.putIfAbsent(record.topic(), new ArrayList<SourceRecord>());
            recordsByTopic.compute(record.topic(), (k, v) -> { v.add(record); return v; });
        }
        return recordsByTopic;
    }

    private RecordsSnapshotProducer buildStreamProducer(PostgresTaskContext ctx, PostgresConnectorConfig config) {
        Snapshotter sn = new InitialOnlySnapshotter();
        SnapshotterWrapper snw = new SnapshotterWrapper(sn, config, null, null);
        return new RecordsSnapshotProducer(ctx, TestHelper.sourceInfo(), snw);
    }
}
