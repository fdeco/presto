/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.shaded.org.apache.thrift.TException;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorColumnHandle;
import com.facebook.presto.spi.ConnectorPartitionResult;
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.ConnectorSplitSource;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.RecordSink;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.TupleDomain;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import io.airlift.log.Logger;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static com.facebook.presto.hadoop.HadoopFileStatus.isDirectory;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test(groups = "hive-s3")
public abstract class AbstractTestHiveClientS3
{
    protected String database;
    protected SchemaTableName tableS3;
    protected SchemaTableName temporaryCreateTable;

    protected HdfsEnvironment hdfsEnvironment;
    protected TestingHiveMetastore metastoreClient;
    protected HiveClient client;

    protected void setupHive(String databaseName)
    {
        database = databaseName;
        tableS3 = new SchemaTableName(database, "presto_test_s3");

        String random = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        temporaryCreateTable = new SchemaTableName(database, "tmp_presto_test_create_s3_" + random);
    }

    protected void setup(String host, int port, String databaseName, String awsAccessKey, String awsSecretKey, String writableBucket)
    {
        setupHive(databaseName);

        HiveClientConfig hiveClientConfig = new HiveClientConfig()
                .setS3AwsAccessKey(awsAccessKey)
                .setS3AwsSecretKey(awsSecretKey);

        String proxy = System.getProperty("hive.metastore.thrift.client.socks-proxy");
        if (proxy != null) {
            hiveClientConfig.setMetastoreSocksProxy(HostAndPort.fromString(proxy));
        }

        HiveCluster hiveCluster = new TestingHiveCluster(hiveClientConfig, host, port);
        ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("hive-s3-%s"));

        hdfsEnvironment = new HdfsEnvironment(new HdfsConfiguration(hiveClientConfig));
        metastoreClient = new TestingHiveMetastore(hiveCluster, executor, hiveClientConfig, writableBucket);
        client = new HiveClient(
                new HiveConnectorId("hive-test"),
                hiveClientConfig,
                metastoreClient,
                new NamenodeStats(),
                new HdfsEnvironment(new HdfsConfiguration(hiveClientConfig)),
                new HadoopDirectoryLister(),
                sameThreadExecutor());
    }

    @Test
    public void testGetRecordsS3()
            throws Exception
    {
        ConnectorTableHandle table = getTableHandle(tableS3);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(client.getColumnHandles(table).values());
        Map<String, Integer> columnIndex = indexColumns(columnHandles);

        ConnectorPartitionResult partitionResult = client.getPartitions(table, TupleDomain.<ConnectorColumnHandle>all());
        assertEquals(partitionResult.getPartitions().size(), 1);
        ConnectorSplitSource splitSource = client.getPartitionSplits(table, partitionResult.getPartitions());

        long sum = 0;
        for (ConnectorSplit split : getAllSplits(splitSource)) {
            try (RecordCursor cursor = client.getRecordSet(split, columnHandles).cursor()) {
                while (cursor.advanceNextPosition()) {
                    sum += cursor.getLong(columnIndex.get("t_bigint"));
                }
            }
        }
        assertEquals(sum, 78300);
    }

    @Test
    public void testGetFileStatus()
            throws Exception
    {
        Path basePath = new Path("s3://presto-test-hive/");
        Path tablePath = new Path(basePath, "presto_test_s3");
        Path filePath = new Path(tablePath, "test1.csv");
        FileSystem fs = basePath.getFileSystem(hdfsEnvironment.getConfiguration(basePath));

        assertTrue(isDirectory(fs.getFileStatus(basePath)));
        assertTrue(isDirectory(fs.getFileStatus(tablePath)));
        assertFalse(isDirectory(fs.getFileStatus(filePath)));
        assertFalse(fs.exists(new Path(basePath, "foo")));
    }

    @Test
    public void testTableCreation()
            throws Exception
    {
        try {
            doCreateTable(temporaryCreateTable, "presto_test");
        }
        finally {
            dropTable(temporaryCreateTable);
        }
    }

    private void doCreateTable(SchemaTableName tableName, String tableOwner)
            throws InterruptedException
    {
        // begin creating the table
        List<ColumnMetadata> columns = ImmutableList.<ColumnMetadata>builder()
                .add(new ColumnMetadata("id", BIGINT, 1, false))
                .build();

        ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(tableName, columns, tableOwner);
        HiveOutputTableHandle outputHandle = client.beginCreateTable(tableMetadata);

        // write the records
        RecordSink sink = client.getRecordSink(outputHandle);

        sink.beginRecord(1);
        sink.appendLong(1);
        sink.finishRecord();

        sink.beginRecord(1);
        sink.appendLong(3);
        sink.finishRecord();

        sink.beginRecord(1);
        sink.appendLong(2);
        sink.finishRecord();

        String fragment = sink.commit();

        // commit the table
        client.commitCreateTable(outputHandle, ImmutableList.of(fragment));

        // Hack to work around the metastore not being configured for S3.
        // The metastore tries to validate the location when creating the
        // table, which fails without explicit configuration for S3.
        // We work around that by using a dummy location when creating the
        // table and update it here to the correct S3 location.
        metastoreClient.updateTableLocation(database, tableName.getTableName(), outputHandle.getTargetPath());

        // load the new table
        ConnectorTableHandle tableHandle = getTableHandle(tableName);
        List<ConnectorColumnHandle> columnHandles = ImmutableList.copyOf(client.getColumnHandles(tableHandle).values());

        // verify the data
        ConnectorPartitionResult partitionResult = client.getPartitions(tableHandle, TupleDomain.<ConnectorColumnHandle>all());
        assertEquals(partitionResult.getPartitions().size(), 1);
        ConnectorSplitSource splitSource = client.getPartitionSplits(tableHandle, partitionResult.getPartitions());
        ConnectorSplit split = getOnlyElement(getAllSplits(splitSource));

        try (RecordCursor cursor = client.getRecordSet(split, columnHandles).cursor()) {
            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 1);

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 3);

            assertTrue(cursor.advanceNextPosition());
            assertEquals(cursor.getLong(0), 2);

            assertFalse(cursor.advanceNextPosition());
        }
    }

    private void dropTable(SchemaTableName table)
    {
        try {
            metastoreClient.dropTable(table.getSchemaName(), table.getTableName());
        }
        catch (RuntimeException e) {
            Logger.get(getClass()).warn(e, "Failed to drop table: %s", table);
        }
    }

    private ConnectorTableHandle getTableHandle(SchemaTableName tableName)
    {
        ConnectorTableHandle handle = client.getTableHandle(tableName);
        checkArgument(handle != null, "table not found: %s", tableName);
        return handle;
    }

    private static List<ConnectorSplit> getAllSplits(ConnectorSplitSource source)
            throws InterruptedException
    {
        ImmutableList.Builder<ConnectorSplit> splits = ImmutableList.builder();
        while (!source.isFinished()) {
            splits.addAll(source.getNextBatch(1000));
        }
        return splits.build();
    }

    private static ImmutableMap<String, Integer> indexColumns(List<ConnectorColumnHandle> columnHandles)
    {
        ImmutableMap.Builder<String, Integer> index = ImmutableMap.builder();
        int i = 0;
        for (ConnectorColumnHandle columnHandle : columnHandles) {
            checkArgument(columnHandle instanceof HiveColumnHandle, "columnHandle is not an instance of HiveColumnHandle");
            HiveColumnHandle hiveColumnHandle = (HiveColumnHandle) columnHandle;
            index.put(hiveColumnHandle.getName(), i);
            i++;
        }
        return index.build();
    }

    private static class TestingHiveMetastore
            extends CachingHiveMetastore
    {
        private final String writableBucket;

        public TestingHiveMetastore(HiveCluster hiveCluster, ExecutorService executor, HiveClientConfig hiveClientConfig, String writableBucket)
        {
            super(hiveCluster, executor, hiveClientConfig);
            this.writableBucket = writableBucket;
        }

        @Override
        public Database getDatabase(String databaseName)
                throws NoSuchObjectException
        {
            Database database = super.getDatabase(databaseName);
            database.setLocationUri("s3://" + writableBucket + "/");
            return database;
        }

        @Override
        public void createTable(Table table)
        {
            // hack to work around the metastore not being configured for S3
            table.getSd().setLocation("/");
            super.createTable(table);
        }

        @Override
        public void dropTable(String databaseName, String tableName)
        {
            try {
                // hack to work around the metastore not being configured for S3
                Table table = getTable(databaseName, tableName);
                table.getSd().setLocation("/");
                try (HiveMetastoreClient client = clientProvider.createMetastoreClient()) {
                    client.alter_table(databaseName, tableName, table);
                    client.drop_table(databaseName, tableName, false);
                }
            }
            catch (TException e) {
                throw Throwables.propagate(e);
            }
        }

        public void updateTableLocation(String databaseName, String tableName, String location)
        {
            try {
                Table table = getTable(databaseName, tableName);
                table.getSd().setLocation(location);
                try (HiveMetastoreClient client = clientProvider.createMetastoreClient()) {
                    client.alter_table(databaseName, tableName, table);
                }
            }
            catch (TException e) {
                throw Throwables.propagate(e);
            }
        }
    }
}
