/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.coprocessor;

import static org.apache.phoenix.schema.types.PDataType.TRUE_BYTES;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimerTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.GuardedBy;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.compile.MutationPlan;
import org.apache.phoenix.compile.PostDDLCompiler;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MetaDataMutationResult;
import org.apache.phoenix.coprocessor.MetaDataProtocol.MutationCode;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.execute.MutationState;
import org.apache.phoenix.hbase.index.util.IndexManagementUtil;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.index.PhoenixIndexCodec;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.jdbc.PhoenixDriver;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.MetaDataClient;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.schema.TableRef;
import org.apache.phoenix.schema.types.PLong;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.UpgradeUtil;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ServiceException;


/**
 * Coprocessor for metadata related operations. This coprocessor would only be registered
 * to SYSTEM.TABLE.
 */
public class MetaDataRegionObserver extends BaseRegionObserver {
    public static final Log LOG = LogFactory.getLog(MetaDataRegionObserver.class);
    protected ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    private boolean enableRebuildIndex = QueryServicesOptions.DEFAULT_INDEX_FAILURE_HANDLING_REBUILD;
    private long rebuildIndexTimeInterval = QueryServicesOptions.DEFAULT_INDEX_FAILURE_HANDLING_REBUILD_INTERVAL;
    private boolean blockWriteRebuildIndex = false;
    public static final String REBUILD_INDEX_APPEND_TO_URL_STRING = "REBUILDINDEX";
    private static Map<PName, Long> batchExecutedPerTableMap = new HashMap<PName, Long>();
    
    @GuardedBy("MetaDataRegionObserver.class")
    private static Properties rebuildIndexConnectionProps;

    @Override
    public void preClose(final ObserverContext<RegionCoprocessorEnvironment> c,
            boolean abortRequested) {
        executor.shutdownNow();
        GlobalCache.getInstance(c.getEnvironment()).getMetaDataCache().invalidateAll();
    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        // sleep a little bit to compensate time clock skew when SYSTEM.CATALOG moves
        // among region servers because we relies on server time of RS which is hosting
        // SYSTEM.CATALOG
        Configuration config = env.getConfiguration();
        long sleepTime = config.getLong(QueryServices.CLOCK_SKEW_INTERVAL_ATTRIB,
            QueryServicesOptions.DEFAULT_CLOCK_SKEW_INTERVAL);
        try {
            if(sleepTime > 0) {
                Thread.sleep(sleepTime);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        enableRebuildIndex =
                config.getBoolean(
                    QueryServices.INDEX_FAILURE_HANDLING_REBUILD_ATTRIB,
                    QueryServicesOptions.DEFAULT_INDEX_FAILURE_HANDLING_REBUILD);
        rebuildIndexTimeInterval =
                config.getLong(
                    QueryServices.INDEX_FAILURE_HANDLING_REBUILD_INTERVAL_ATTRIB,
                    QueryServicesOptions.DEFAULT_INDEX_FAILURE_HANDLING_REBUILD_INTERVAL);
        blockWriteRebuildIndex = config.getBoolean(QueryServices.INDEX_FAILURE_BLOCK_WRITE,
        	QueryServicesOptions.DEFAULT_INDEX_FAILURE_BLOCK_WRITE);
        
    }
    
    private static String getJdbcUrl(RegionCoprocessorEnvironment env) {
        String zkQuorum = env.getConfiguration().get(HConstants.ZOOKEEPER_QUORUM);
        String zkClientPort = env.getConfiguration().get(HConstants.ZOOKEEPER_CLIENT_PORT,
            Integer.toString(HConstants.DEFAULT_ZOOKEPER_CLIENT_PORT));
        String zkParentNode = env.getConfiguration().get(HConstants.ZOOKEEPER_ZNODE_PARENT,
            HConstants.DEFAULT_ZOOKEEPER_ZNODE_PARENT);
        return PhoenixRuntime.JDBC_PROTOCOL + PhoenixRuntime.JDBC_PROTOCOL_SEPARATOR + zkQuorum
            + PhoenixRuntime.JDBC_PROTOCOL_SEPARATOR + zkClientPort
            + PhoenixRuntime.JDBC_PROTOCOL_SEPARATOR + zkParentNode;
    }

    @Override
    public void postOpen(ObserverContext<RegionCoprocessorEnvironment> e) {
        final RegionCoprocessorEnvironment env = e.getEnvironment();

        Runnable r = new Runnable() {
            @Override
            public void run() {
                HTableInterface metaTable = null;
                HTableInterface statsTable = null;
                try {
                    ReadOnlyProps props=new ReadOnlyProps(env.getConfiguration().iterator());
                    Thread.sleep(1000);
                    metaTable = env.getTable(
                            SchemaUtil.getPhysicalName(PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME_BYTES, props));
                    statsTable = env.getTable(
                            SchemaUtil.getPhysicalName(PhoenixDatabaseMetaData.SYSTEM_STATS_NAME_BYTES, props));
                    final HTableInterface mTable=metaTable;
                    final HTableInterface sTable=statsTable;
                    User.runAsLoginUser(new PrivilegedExceptionAction<Void>() {
                        @Override
                        public Void run() throws Exception {
                            if (UpgradeUtil.truncateStats(mTable, sTable)) {
                                LOG.info("Stats are successfully truncated for upgrade 4.7!!");
                            }
                            return null;
                        }
                    });

                } catch (Exception exception) {
                    LOG.warn("Exception while truncate stats..,"
                            + " please check and delete stats manually inorder to get proper result with old client!!");
                    LOG.warn(exception.getStackTrace());
                } finally {
                    try {
                        if (metaTable != null) {
                            metaTable.close();
                        }
                        if (statsTable != null) {
                            statsTable.close();
                        }
                    } catch (IOException e) {}
                }
            }
        };
        (new Thread(r)).start();

        if (!enableRebuildIndex && !blockWriteRebuildIndex) {
            LOG.info("Failure Index Rebuild is skipped by configuration.");
            return;
        }
        // turn off verbose deprecation logging
        Logger deprecationLogger = Logger.getLogger("org.apache.hadoop.conf.Configuration.deprecation");
        if (deprecationLogger != null) {
            deprecationLogger.setLevel(Level.WARN);
        }
        try {
            Class.forName(PhoenixDriver.class.getName());
            initRebuildIndexConnectionProps(e.getEnvironment().getConfiguration());
            // starts index rebuild schedule work
            BuildIndexScheduleTask task = new BuildIndexScheduleTask(e.getEnvironment());
            executor.scheduleWithFixedDelay(task, 10000, rebuildIndexTimeInterval, TimeUnit.MILLISECONDS);
        } catch (ClassNotFoundException ex) {
            LOG.error("BuildIndexScheduleTask cannot start!", ex);
        }
    }

    /**
     * Task runs periodically to build indexes whose INDEX_NEED_PARTIALLY_REBUILD is set true
     *
     */
    public static class BuildIndexScheduleTask extends TimerTask {
        // inProgress is to prevent timer from invoking a new task while previous one is still
        // running
        private final static AtomicInteger inProgress = new AtomicInteger(0);
        RegionCoprocessorEnvironment env;
        private long rebuildIndexBatchSize = HConstants.LATEST_TIMESTAMP;
        private long configuredBatches = 10;
        
        public BuildIndexScheduleTask(RegionCoprocessorEnvironment env) {
            this.env = env;
            Configuration configuration = env.getConfiguration();
            this.rebuildIndexBatchSize = configuration.getLong(
                    QueryServices.INDEX_FAILURE_HANDLING_REBUILD_PERIOD, HConstants.LATEST_TIMESTAMP);
            this.configuredBatches = configuration.getLong(
                    QueryServices.INDEX_FAILURE_HANDLING_REBUILD_NUMBER_OF_BATCHES_PER_TABLE, configuredBatches);
        }

        @Override
        public void run() {
            // FIXME: we should replay the data table Put, as doing a partial index build would only add
            // the new rows and not delete the previous index value. Also, we should restrict the scan
            // to only data within this region (as otherwise *every* region will be running this code
            // separately, all updating the same data.
            RegionScanner scanner = null;
            PhoenixConnection conn = null;
            if (inProgress.get() > 0) {
                LOG.debug("New ScheduledBuildIndexTask skipped as there is already one running");
                return;
            }
            try {
                inProgress.incrementAndGet();
                Scan scan = new Scan();
                SingleColumnValueFilter filter = new SingleColumnValueFilter(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                    PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES,
                    CompareFilter.CompareOp.GREATER, PLong.INSTANCE.toBytes(0L));
                filter.setFilterIfMissing(true);
                scan.setFilter(filter);
                scan.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                    PhoenixDatabaseMetaData.TABLE_NAME_BYTES);
                scan.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                    PhoenixDatabaseMetaData.DATA_TABLE_NAME_BYTES);
                scan.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                    PhoenixDatabaseMetaData.INDEX_STATE_BYTES);
                scan.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                    PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES);
                PreparedStatement updateDisabledTimeStampSmt = null;

                Map<PTable, List<Pair<PTable,Long>>> dataTableToIndexesMap = null;
                MetaDataClient client = null;
                boolean hasMore = false;
                List<Cell> results = new ArrayList<Cell>();
                scanner = this.env.getRegion().getScanner(scan);

                do {
                    results.clear();
                    hasMore = scanner.next(results);
                    if (results.isEmpty()) break;

                    Result r = Result.create(results);
                    byte[] disabledTimeStamp = r.getValue(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                        PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES);
                    Cell indexStateCell = r.getColumnLatestCell(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES, PhoenixDatabaseMetaData.INDEX_STATE_BYTES);
                    byte[] indexState = indexStateCell == null ? null: CellUtil.cloneValue(indexStateCell);
                    if (disabledTimeStamp == null || disabledTimeStamp.length == 0 || (indexState != null
                            && PIndexState.BUILDING == PIndexState.fromSerializedValue(Bytes.toString(indexState)))) {
                        // Don't rebuild the building index , because they are marked for aysnc
                        continue;
                    }

                    // disableTimeStamp has to be a positive value
                    long disabledTimeStampVal = PLong.INSTANCE.getCodec().decodeLong(disabledTimeStamp, 0, SortOrder.getDefault());
                    if (disabledTimeStampVal <= 0) {
                        continue;
                    }

                    byte[] dataTable = r.getValue(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                        PhoenixDatabaseMetaData.DATA_TABLE_NAME_BYTES);
                    if ((dataTable == null || dataTable.length == 0)
                            || (indexState == null || indexState.length == 0)) {
                        // data table name can't be empty
                        continue;
                    }

                    byte[][] rowKeyMetaData = new byte[3][];
                    SchemaUtil.getVarChars(r.getRow(), 3, rowKeyMetaData);
                    byte[] schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
                    byte[] indexTable = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];

                    // validity check
                    if (indexTable == null || indexTable.length == 0) {
                        LOG.debug("Index rebuild has been skipped for row=" + r);
                        continue;
                    }

                    if (conn == null) {
                        conn = getRebuildIndexConnection(env.getConfiguration());
                        client = new MetaDataClient(conn);
                        dataTableToIndexesMap = Maps.newHashMap();
                    }
                    String dataTableFullName = SchemaUtil.getTableName(schemaName, dataTable);
                    PTable dataPTable = PhoenixRuntime.getTableNoCache(conn, dataTableFullName);

                    String indexTableFullName = SchemaUtil.getTableName(schemaName, indexTable);
                    PTable indexPTable = PhoenixRuntime.getTableNoCache(conn, indexTableFullName);
                    // Sanity check in case index was removed from table
                    if (!dataPTable.getIndexes().contains(indexPTable)) {
                        continue;
                    }
                    if (!MetaDataUtil.tableRegionsOnline(this.env.getConfiguration(), indexPTable)) {
                        LOG.debug("Index rebuild has been skipped because not all regions of index table="
                                + indexPTable.getName() + " are online.");
                        continue;
                    }
                    // Allow index to begin incremental maintenance as index is back online and we
                    // cannot transition directly from DISABLED -> ACTIVE
                    if (Bytes.compareTo(PIndexState.DISABLE.getSerializedBytes(), indexState) == 0) {
                        updateIndexState(conn, indexTableFullName, env, PIndexState.DISABLE, PIndexState.INACTIVE, disabledTimeStampVal);
                        continue;
                    }
                    long currentTime = System.currentTimeMillis();
                    long forwardOverlapDurationMs = env.getConfiguration().getLong(
                            QueryServices.INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_FORWARD_TIME_ATTRIB, 
                                    QueryServicesOptions.DEFAULT_INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_FORWARD_TIME);
                    // Wait until no failures have occurred in at least forwardOverlapDurationMs
                    if (indexStateCell.getTimestamp() + forwardOverlapDurationMs > currentTime) {
                        continue; // Haven't waited long enough yet
                    }
                    Long upperBoundOfRebuild = HConstants.LATEST_TIMESTAMP;
                    if (Bytes.compareTo(PIndexState.INACTIVE.getSerializedBytes(), indexState) == 0) {
                        upperBoundOfRebuild = indexStateCell.getTimestamp() + forwardOverlapDurationMs;
                    } else if (Bytes.compareTo(PIndexState.ACTIVE.getSerializedBytes(), indexState) == 0) {
                        // Since the index state cell is updated every time the INDEX_DISABLED_TIMESTAMP
                        // changes, we know the upper bound.
                        upperBoundOfRebuild = indexStateCell.getTimestamp() + 1;
                    } else {
                        LOG.warn("Unexpected index state of " + indexTableFullName + ":" + Bytes.toStringBinary(indexState));
                        continue; // Ignore as this is an unexpected state
                    }
                    List<Pair<PTable,Long>> indexesToPartiallyRebuild = dataTableToIndexesMap.get(dataPTable);
                    if (indexesToPartiallyRebuild == null) {
                        indexesToPartiallyRebuild = Lists.newArrayListWithExpectedSize(dataPTable.getIndexes().size());
                        dataTableToIndexesMap.put(dataPTable, indexesToPartiallyRebuild);
                    }
                    LOG.debug("We have found " + indexPTable.getIndexState() + " Index:" + indexPTable.getName()
                            + " on data table:" + dataPTable.getName() + " which failed to be updated at "
                            + indexPTable.getIndexDisableTimestamp());
                    indexesToPartiallyRebuild.add(new Pair<PTable,Long>(indexPTable,upperBoundOfRebuild));
                } while (hasMore);
                if (dataTableToIndexesMap != null) {
                    long backwardOverlapDurationMs = env.getConfiguration().getLong(
                        QueryServices.INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_BACKWARD_TIME_ATTRIB,
                        env.getConfiguration().getLong(QueryServices.INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_TIME_ATTRIB, 
                                QueryServicesOptions.DEFAULT_INDEX_FAILURE_HANDLING_REBUILD_OVERLAP_BACKWARD_TIME));
                    for (Map.Entry<PTable, List<Pair<PTable,Long>>> entry : dataTableToIndexesMap.entrySet()) {
                        PTable dataPTable = entry.getKey();
                        List<Pair<PTable,Long>> pairs = entry.getValue();
                        List<PTable> indexesToPartiallyRebuild = Lists.newArrayListWithExpectedSize(pairs.size());
                        ReadOnlyProps props = new ReadOnlyProps(env.getConfiguration().iterator());
                        try (HTableInterface metaTable = env.getTable(
                                SchemaUtil.getPhysicalName(PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME_BYTES, props))) {
                            long earliestDisableTimestamp = Long.MAX_VALUE;
                            long latestUpperBoundTimestamp = Long.MIN_VALUE;
                            List<IndexMaintainer> maintainers = Lists
                                    .newArrayListWithExpectedSize(pairs.size());
                            for (Pair<PTable,Long> pair : pairs) {
                               PTable index = pair.getFirst();
                               Long upperBoundTimestamp = pair.getSecond();
                                long disabledTimeStampVal = index.getIndexDisableTimestamp();
                                if (disabledTimeStampVal > 0) {
                                    if (disabledTimeStampVal < earliestDisableTimestamp) {
                                        earliestDisableTimestamp = disabledTimeStampVal;
                                    }
                                    indexesToPartiallyRebuild.add(index);
                                    maintainers.add(index.getIndexMaintainer(dataPTable, conn));
                                }
                               if (upperBoundTimestamp > latestUpperBoundTimestamp) {
                                   latestUpperBoundTimestamp = upperBoundTimestamp;
                               }
                            }
                            // No indexes are disabled, so skip this table
                            if (earliestDisableTimestamp == Long.MAX_VALUE) {
                                continue;
                            }
                            long scanBeginTime = Math.max(0, earliestDisableTimestamp - backwardOverlapDurationMs);
                            LOG.info("Starting to build " + dataPTable + " indexes " + indexesToPartiallyRebuild
                                    + " from timestamp=" + scanBeginTime + " until " + latestUpperBoundTimestamp);

                            TableRef tableRef = new TableRef(null, dataPTable, HConstants.LATEST_TIMESTAMP, false);
                            // TODO Need to set high timeout
                            PostDDLCompiler compiler = new PostDDLCompiler(conn);
                            MutationPlan plan = compiler.compile(Collections.singletonList(tableRef), null, null, null,
                                    HConstants.LATEST_TIMESTAMP);
                            Scan dataTableScan = IndexManagementUtil.newLocalStateScan(plan.getContext().getScan(),
                                    maintainers);

                            long scanEndTime = Math.min(latestUpperBoundTimestamp,
                                getTimestampForBatch(scanBeginTime,batchExecutedPerTableMap.get(dataPTable.getName())));
                            dataTableScan.setTimeRange(scanBeginTime, scanEndTime);
                            dataTableScan.setCacheBlocks(false);
                            dataTableScan.setAttribute(BaseScannerRegionObserver.REBUILD_INDEXES, TRUE_BYTES);

                            ImmutableBytesWritable indexMetaDataPtr = new ImmutableBytesWritable(
                                    ByteUtil.EMPTY_BYTE_ARRAY);
                            IndexMaintainer.serializeAdditional(dataPTable, indexMetaDataPtr, indexesToPartiallyRebuild,
                                    conn);
                            byte[] attribValue = ByteUtil.copyKeyBytesIfNecessary(indexMetaDataPtr);
                            dataTableScan.setAttribute(PhoenixIndexCodec.INDEX_MD, attribValue);
                            MutationState mutationState = plan.execute();
                            long rowCount = mutationState.getUpdateCount();
                            LOG.info(rowCount + " rows of index which are rebuild");
                            for (PTable indexPTable : indexesToPartiallyRebuild) {
                                String indexTableFullName = SchemaUtil.getTableName(
                                        indexPTable.getSchemaName().getString(),
                                        indexPTable.getTableName().getString());
                                if (scanEndTime == latestUpperBoundTimestamp) {
                                    // We compare the absolute value of the index disable timestamp. We don't want to
                                    // pass a negative value because that means an additional index write failed.
                                    updateIndexState(conn, indexTableFullName, env, PIndexState.INACTIVE,
                                            PIndexState.ACTIVE, Math.abs(indexPTable.getIndexDisableTimestamp()));
                                    batchExecutedPerTableMap.remove(dataPTable.getName());
                                } else {
                                    updateIndexState(conn, indexTableFullName, env, indexPTable.getIndexState(),
                                        indexPTable.getIndexState(), scanEndTime);
                                    Long noOfBatches = batchExecutedPerTableMap.get(dataPTable.getName());
                                    if (noOfBatches == null) {
                                        noOfBatches = 0l;
                                    }
                                    batchExecutedPerTableMap.put(dataPTable.getName(), ++noOfBatches);
                                    // clearing cache to get the updated
                                    // disabled timestamp
                                    new MetaDataClient(conn).updateCache(dataPTable.getSchemaName().getString(),
                                            dataPTable.getTableName().getString());
                                    new MetaDataClient(conn).updateCache(indexPTable.getSchemaName().getString(),
                                            indexPTable.getTableName().getString());
                                    LOG.info(
                                            "During Round-robin build: Successfully updated index disabled timestamp  for "
                                                    + indexTableFullName + " to " + scanEndTime);
                                }

                            }
                        } catch (Exception e) {
                            LOG.error("Unable to rebuild " + dataPTable + " indexes " + indexesToPartiallyRebuild, e);
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.warn("ScheduledBuildIndexTask failed!", t);
            } finally {
                inProgress.decrementAndGet();
                if (scanner != null) {
                    try {
                        scanner.close();
                    } catch (IOException ignored) {
                        LOG.debug("ScheduledBuildIndexTask can't close scanner.", ignored);
                    }
                }
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException ignored) {
                        LOG.debug("ScheduledBuildIndexTask can't close connection", ignored);
                    }
                }
            }
        }

        private long getTimestampForBatch(long disabledTimeStamp, Long noOfBatches) {
            if (disabledTimeStamp < 0 || rebuildIndexBatchSize > (HConstants.LATEST_TIMESTAMP
                    - disabledTimeStamp)) { return HConstants.LATEST_TIMESTAMP; }
            long timestampForNextBatch = disabledTimeStamp + rebuildIndexBatchSize;
            if (timestampForNextBatch < 0 || timestampForNextBatch > System.currentTimeMillis()
                    || (noOfBatches != null && noOfBatches > configuredBatches)) {
                // if timestampForNextBatch cross current time , then we should
                // build the complete index
                timestampForNextBatch = HConstants.LATEST_TIMESTAMP;
            }
            return timestampForNextBatch;
        }
    }

    private static void updateIndexState(PhoenixConnection conn, String indexTableName,
            RegionCoprocessorEnvironment env, PIndexState oldState, PIndexState newState,Long indexDisableTimestamp)
            throws ServiceException, Throwable {
        byte[] indexTableKey = SchemaUtil.getTableKeyFromFullName(indexTableName);
        String schemaName = SchemaUtil.getSchemaNameFromFullName(indexTableName);
        String indexName = SchemaUtil.getTableNameFromFullName(indexTableName);
        // Mimic the Put that gets generated by the client on an update of the
        // index state
        Put put = new Put(indexTableKey);
        put.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES, PhoenixDatabaseMetaData.INDEX_STATE_BYTES,
                newState.getSerializedBytes());
        if (indexDisableTimestamp != null) {
            put.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                    PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES,
                    PLong.INSTANCE.toBytes(indexDisableTimestamp));
        }
        if (newState == PIndexState.ACTIVE) {
            put.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                PhoenixDatabaseMetaData.ASYNC_REBUILD_TIMESTAMP_BYTES, PLong.INSTANCE.toBytes(0));
        }
        final List<Mutation> tableMetadata = Collections.<Mutation> singletonList(put);
        MetaDataMutationResult result = conn.getQueryServices().updateIndexState(tableMetadata, null);
        MutationCode code = result.getMutationCode();
        if (code == MutationCode.TABLE_NOT_FOUND) { throw new TableNotFoundException(schemaName, indexName); }
        if (code == MutationCode.UNALLOWED_TABLE_MUTATION) { throw new SQLExceptionInfo.Builder(
                SQLExceptionCode.INVALID_INDEX_STATE_TRANSITION)
                        .setMessage(" currentState=" + oldState + ". requestedState=" + newState)
                        .setSchemaName(schemaName).setTableName(indexName).build().buildException(); }
    }

    private static boolean updateDisableTimestamp(PhoenixConnection conn, String indexTableName,
            long disabledTimestamp, final HTableInterface metaTable,
            final long expectedDisabledTimestamp) throws IOException {
        final byte[] indexTableKey = SchemaUtil.getTableKeyFromFullName(indexTableName);
        final Put put = new Put(indexTableKey);
        put.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES, PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES,
                PLong.INSTANCE.toBytes(disabledTimestamp));
        return User.runAsLoginUser(new PrivilegedExceptionAction<Boolean>() {
            @Override
            public Boolean run() throws Exception {
                return metaTable.checkAndPut(indexTableKey, PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                        PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES, CompareOp.EQUAL,
                        PLong.INSTANCE.toBytes(expectedDisabledTimestamp), put);
            }
        });
    }
    
    private static synchronized void initRebuildIndexConnectionProps(Configuration config) {
        if (rebuildIndexConnectionProps == null) {
            Properties props = new Properties();
            long indexRebuildQueryTimeoutMs =
                    config.getLong(QueryServices.INDEX_REBUILD_QUERY_TIMEOUT_ATTRIB,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_QUERY_TIMEOUT);
            long indexRebuildRPCTimeoutMs =
                    config.getLong(QueryServices.INDEX_REBUILD_RPC_TIMEOUT_ATTRIB,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_RPC_TIMEOUT);
            long indexRebuildClientScannerTimeOutMs =
                    config.getLong(QueryServices.INDEX_REBUILD_CLIENT_SCANNER_TIMEOUT_ATTRIB,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_CLIENT_SCANNER_TIMEOUT);
            int indexRebuildRpcRetriesCounter =
                    config.getInt(QueryServices.INDEX_REBUILD_RPC_RETRIES_COUNTER,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_RPC_RETRIES_COUNTER);
            // Set SCN so that we don't ping server and have the upper bound set back to
            // the timestamp when the failure occurred.
            props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(Long.MAX_VALUE));
            // Set various phoenix and hbase level timeouts and rpc retries
            props.setProperty(QueryServices.THREAD_TIMEOUT_MS_ATTRIB,
                Long.toString(indexRebuildQueryTimeoutMs));
            props.setProperty(HConstants.HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD,
                Long.toString(indexRebuildClientScannerTimeOutMs));
            props.setProperty(HConstants.HBASE_RPC_TIMEOUT_KEY,
                Long.toString(indexRebuildRPCTimeoutMs));
            props.setProperty(HConstants.HBASE_CLIENT_RETRIES_NUMBER,
                Long.toString(indexRebuildRpcRetriesCounter));
            // don't run a second index populations upsert select
            props.setProperty(QueryServices.INDEX_POPULATION_SLEEP_TIME, "0");
            props = PropertiesUtil.combineProperties(props, config);
            PropertiesUtil.addMissingPropertiesFromConfig(props, config);
            rebuildIndexConnectionProps = props;
        }
    }
    
    public static PhoenixConnection getRebuildIndexConnection(Configuration config)
            throws SQLException, ClassNotFoundException {
        initRebuildIndexConnectionProps(config);
        //return QueryUtil.getConnectionOnServer(rebuildIndexConnectionProps, config).unwrap(PhoenixConnection.class);
        return QueryUtil.getConnectionOnServerWithCustomUrl(rebuildIndexConnectionProps,
            REBUILD_INDEX_APPEND_TO_URL_STRING).unwrap(PhoenixConnection.class);
    }

}
