/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.qatest.ddl.sharding.gsi.group2;

import com.alibaba.polardbx.common.jdbc.ParameterContext;
import com.alibaba.polardbx.common.jdbc.ParameterMethod;
import com.alibaba.polardbx.common.utils.Assert;
import com.alibaba.polardbx.common.utils.GeneralUtil;
import com.alibaba.polardbx.common.utils.TStringUtil;
import com.alibaba.polardbx.qatest.DDLBaseNewDBTestCase;
import com.alibaba.polardbx.qatest.util.ConnectionManager;
import com.alibaba.polardbx.qatest.util.JdbcUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.util.Pair;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.math.RandomUtils;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.junit.runners.Parameterized.Parameters;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.alibaba.polardbx.qatest.validator.DataValidator.resultSetContentSameAssert;
import static com.alibaba.polardbx.qatest.validator.DataValidator.selectContentSameAssert;

/**
 * @author chenmo.cm
 */
@FixMethodOrder(value = MethodSorters.JVM)

public class GsiBackfillTest extends DDLBaseNewDBTestCase {

    private static final String PRIMARY_TABLE_NAME = "gsi_backfill_primary";
    private static final String INDEX_NAME = "g_i_backfill";

    private static final String HINT =
        "/*+TDDL:CMD_EXTRA(ALLOW_ADD_GSI=TRUE, GSI_IGNORE_RESTRICTION=TRUE, GSI_BACKFILL_BATCH_SIZE=1000, GSI_BACKFILL_SPEED_LIMITATION=-1, GSI_BACKFILL_PARALLELISM=4, GSI_CHECK_SPEED_LIMITATION=-1, GSI_CHECK_PARALLELISM=4)*/";
    private static final String SLOW_HINT =
        "/*+TDDL: cmd_extra(GSI_BACKFILL_BATCH_SIZE=100, GSI_BACKFILL_SPEED_LIMITATION=-1, GSI_BACKFILL_PARALLELISM=4, GSI_CHECK_SPEED_LIMITATION=-1, GSI_CHECK_PARALLELISM=4, GSI_DEBUG=\"slow\")*/";

    private static final String ASYNC_TABLE_NAME = "gsi_async_backfill";
    private static final String ASYNC_INDEX_NAME = "g_i_async_backfill";
    // Limit speed to 1000 rows/s
    private static final String ASYNC_HINT_LOW_SPEED =
        "/*+TDDL: cmd_extra(ENABLE_ASYNC_DDL=true, PURE_ASYNC_DDL_MODE=true, GSI_BACKFILL_SPEED_LIMITATION=72, GSI_BACKFILL_BATCH_SIZE=2, GSI_CHECK_SPEED_LIMITATION=-1)*/";

    private static final String SINGLE_PK_TMPL = "create table {0}("
        + "id bigint not null auto_increment, "
        + "c1 bigint default null, "
        + "c2 varchar(256) default null, "
        + "primary key(id),"
        + "key i_c2(c1, c2)"
        + ") {1}";
    private static final String SINGLE_PK_TMPL1 = "create table {0}("
        + "id varchar(32) not null, "
        + "c1 bigint default null, "
        + "c2 varchar(256) default null, "
        + "primary key(id),"
        + "key i_c2(c1, c2)"
        + ") {1}";
    private static final String SINGLE_PK_TMPL2 = "create table {0}("
        + "id bigint not null auto_increment, "
        + "c1 bigint default null, "
        + "c2 varchar(256) default null, "
        + "c3 varchar(256) default null, "
        + "primary key(id),"
        + "key i_c2(c1, c2)"
        + ") {1}";
    private static final String MULTI_PK_TMPL = "create table {0}("
        + "id bigint not null, "
        + "id1 bigint not null, "
        + "c1 bigint default null, "
        + "c2 varchar(256) default null, "
        + "primary key(id, id1),"
        + "key i_c2(c1, c2)"
        + ") {1}";
    private static final String MULTI_PK_TMPL1 = "create table {0}("
        + "id varchar(32) not null, "
        + "c1 bigint default null, "
        + "id1 varchar(32) not null, "
        + "c2 varchar(256) default null, "
        + "primary key(id, id1),"
        + "key i_c2(c1, c2)"
        + ") {1}";
    private static final String MULTI_PK_TMPL2 = "create table {0}("
        + "id bigint not null, "
        + "c1 bigint default null, "
        + "id1 varchar(32) not null, "
        + "c2 varchar(256) default null, "
        + "primary key(id, id1),"
        + "key i_c2(c1, c2)"
        + ") {1}";
    private static final String MULTI_PK_TMPL3 = "create table {0}("
        + "id varchar(32) not null, "
        + "c1 bigint default null, "
        + "id1 bigint not null, "
        + "c2 varchar(256) default null, "
        + "primary key(id, id1),"
        + "key i_c2(c1, c2)"
        + ") {1}";
    private static final String PARTITION_DEF = "dbpartition by hash(c1) tbpartition by hash(c1) tbpartitions 2";
    private static final String PARTITION_DEF1 = "dbpartition by hash(c1) tbpartition by hash(c2) tbpartitions 2";

    private static final String INSERT_TMPL = "insert into {0}(c1, c2) values(?, ?)";
    private static final String INSERT_SELECT_TMPL = "insert into {0}(c1, c2) select c1, c2 from {1} limit 10000";

    private static final String INSERT_TMPL1 = "insert into {0}(id, id1, c1, c2) values(?, ?, ?, ?)";

    private static final String INSERT_TMPL2 = "insert into {0}(id, c1, c2) values(?, ?, ?)";

    private static final String CREATE_GSI_TMPL =
        "create global index {0} on {1}(id) covering(c2) dbpartition by hash(id)";
    private static final String CREATE_CLUSTERED_TMPL =
        "create clustered index {0} on {1}(id) dbpartition by hash(id)";
    private static final String CREATE_GSI_TMPL2 =
        "create global index {0} on {1}(id) covering(c2,c3) dbpartition by hash(id)";

    private static final String UPDATE_TMPL = "update {0} set c2=\"updated\" where id%2=0";

    private static final String DELETE_TMPL = "delete from {0} where id%3=0";

    private static final String SELECT_FORCE_TMPL = "select id,c1,c2 from {0} force index({1}) order by id";

    private static final String SELECT_IGNORE_TMPL = "select id,c1,c2 from {0} ignore index({1}) order by id";

    private static final String DROP_GSI_TMPL = "drop index {0} on {1}";

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static final Consumer<Exception> throwException = (e) -> {
        throw GeneralUtil.nestedException(e);
    };

    private static final Consumer<Exception> ignoreDuplicate = (e) -> {
        if (TStringUtil.contains(e.getMessage(),
            "Duplicate entry ")
            && TStringUtil.contains(e.getMessage(),
            "for key 'PRIMARY'")) {
            // Ignore
            return;
        }
        throw GeneralUtil.nestedException(e);
    };

    private final ExecutorService dmlPool = Executors.newFixedThreadPool(10);

    private final String primaryShardingDef;

    public GsiBackfillTest(String primaryShardingDef) {
        this.primaryShardingDef = primaryShardingDef;
    }

    @Parameters(name = "{index}:primaryShardingDef={0}")
    public static List<String[]> prepareDate() {
        return ImmutableList.of(new String[] {PARTITION_DEF}, new String[] {PARTITION_DEF1});
    }

    @Before
    public void before() {
        JdbcUtil.executeUpdateSuccess(mysqlConnection, "DROP TABLE IF EXISTS " + PRIMARY_TABLE_NAME);

        dropTableWithGsi(PRIMARY_TABLE_NAME, ImmutableList.of(INDEX_NAME));
    }

    @Test
    public void singlePkBenchTest() {
        final String mysqlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Future> inserts = new ArrayList<>();
        final AtomicLong pkGen = new AtomicLong(0);
        inserts.add(launchInsertThread(sqlInsert, stop, () -> pkGen.getAndIncrement() % 8, () -> 1000));
        inserts.add(launchInsertThread(sqlInsert, stop, () -> pkGen.getAndIncrement() % 8, () -> 1000));
        inserts.add(
            launchInsertThread(sqlInsert, stop, () -> pkGen.getAndIncrement() % 8, () -> RandomUtils.nextInt(2000)));
        inserts.add(
            launchInsertThread(sqlInsert, stop, () -> pkGen.getAndIncrement() % 8, () -> RandomUtils.nextInt(2000)));

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            // ignore exception
        }

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck(PRIMARY_TABLE_NAME, INDEX_NAME, true);
    }

    @Test
    @Ignore("fix by ???")
    public void singlePkCreateDropTest() {
        final String mysqlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL, PRIMARY_TABLE_NAME);
        final String sqlUpdate = MessageFormat.format(UPDATE_TMPL, PRIMARY_TABLE_NAME);
        final String sqlDelete = MessageFormat.format(DELETE_TMPL, PRIMARY_TABLE_NAME);
        final String sqlSelectPrimary = MessageFormat.format(SELECT_IGNORE_TMPL, PRIMARY_TABLE_NAME, INDEX_NAME);
        final String sqlSelectGsi = MessageFormat.format(SELECT_FORCE_TMPL, PRIMARY_TABLE_NAME, INDEX_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Future> inserts = new ArrayList<>();
        final AtomicLong pkGen = new AtomicLong(0);
        inserts.add(launchDmlCheckThread(sqlInsert, sqlUpdate, sqlDelete, sqlSelectPrimary, sqlSelectGsi, stop,
            () -> pkGen.getAndIncrement() % 8, () -> 10));
        inserts.add(launchDmlCheckThread(sqlInsert, sqlUpdate, sqlDelete, sqlSelectPrimary, sqlSelectGsi, stop,
            () -> pkGen.getAndIncrement() % 8, () -> RandomUtils.nextInt(20)));

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            // ignore exception
        }

        System.out.println("Start create GSI.");

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, SLOW_HINT + sqlCreateGsi);

        System.out.println("Create GSI done.");

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            // ignore exception
        }

        System.out.println("Start drop GSI.");

        final String sqlDropGsi = MessageFormat.format(DROP_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, SLOW_HINT + sqlDropGsi);

        System.out.println("Drop GSI done.");

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            // ignore exception
        }

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        // No check needed.
    }

    @Test
    public void singlePkTest1() {
        final String mysqlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Future> inserts = new ArrayList<>();
        inserts.add(launchInsertThread(sqlInsert, stop, () -> null, () -> 1000));
        inserts.add(launchInsertThread(sqlInsert, stop, () -> 1L, () -> 1000));
        inserts.add(launchInsertThread(sqlInsert, stop, () -> 3L, () -> RandomUtils.nextInt(2000)));
        inserts.add(launchInsertThread(sqlInsert, stop, () -> 5L, () -> RandomUtils.nextInt(2000)));

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            // ignore exception
        }

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck(PRIMARY_TABLE_NAME, INDEX_NAME, true);
    }

    @Test
    public void singlePkInsertSelectTest2() {
        final String mysqlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL, PRIMARY_TABLE_NAME);
        final String sqlInsertSelect = MessageFormat.format(INSERT_SELECT_TMPL, PRIMARY_TABLE_NAME, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Future> inserts = new ArrayList<>();
        inserts.add(launchInsertThread(sqlInsert, stop, RandomUtils::nextLong, () -> RandomUtils.nextInt(2000)));
        inserts.add(launchInsertSelectThread(sqlInsertSelect, stop));
        inserts.add(launchInsertSelectThread(sqlInsertSelect, stop));
        inserts.add(launchInsertSelectThread(sqlInsertSelect, stop));

        //try {
        //    TimeUnit.SECONDS.sleep(3);
        //} catch (InterruptedException e) {
        //    // ignore exception
        //}

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck(PRIMARY_TABLE_NAME, INDEX_NAME, false);
    }

    @Test
    public void singlePkTest3() {
        final String mysqlCreateTable = MessageFormat.format(SINGLE_PK_TMPL1, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL1, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL2, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Future> inserts = new ArrayList<>();
        final Supplier<String> pkGen = () -> RandomStringUtils.randomAlphabetic(32);
        final Supplier<Integer> batchGen = () -> RandomUtils.nextInt(20);

        inserts.add(launchInsertThread2(sqlInsert, stop, pkGen, () -> null, () -> 10, ignoreDuplicate));
        inserts.add(launchInsertThread2(sqlInsert, stop, pkGen, () -> 3L, batchGen, ignoreDuplicate));

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            // ignore exception
        }

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        stop.set(false);
        inserts.clear();

        inserts.add(
            launchInsertThread2(sqlInsert, stop, pkGen, () -> null, () -> RandomUtils.nextInt(20), ignoreDuplicate));

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck(PRIMARY_TABLE_NAME, INDEX_NAME, true);
    }

    @Test
    public void multiPkSequentialInsertTest1() {
        final String mysqlCreateTable = MessageFormat.format(MULTI_PK_TMPL, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(MULTI_PK_TMPL, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL1, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);

        final AtomicLong pk = new AtomicLong(0);
        final Random random = new Random(System.currentTimeMillis());
        // 按照主键升序插入，避免死锁
        final Supplier<Pair<Long, Long>> pkGen = () -> Pair.of(pk.getAndIncrement(),
            Math.abs(RandomUtils.nextLong(random)) % 100000);

        final List<Future> inserts = new ArrayList<>();
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> null, () -> 1000, ignoreDuplicate, true));
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> 1L, () -> 1000, ignoreDuplicate, true));
        //inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> 3L, () -> 1000, ignoreDuplicate, true));
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> 5L, () -> 1000, ignoreDuplicate, true));

        //try {
        //    TimeUnit.SECONDS.sleep(3);
        //} catch (InterruptedException e) {
        //    // ignore exception
        //}

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck1(PRIMARY_TABLE_NAME, INDEX_NAME, true);
    }

    @Test
    public void multiPkRandomInsertTest2() {
        final String mysqlCreateTable = MessageFormat.format(MULTI_PK_TMPL, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(MULTI_PK_TMPL, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL1, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);

        // 并发插入随机主键，大概率出现死锁
        final Random random = new Random(System.currentTimeMillis());
        final Supplier<Pair<Long, Long>> pkGen = () -> Pair.of(Math.abs(RandomUtils.nextLong(random)) % 100000,
            Math.abs(RandomUtils.nextLong(random)) % 100000);

        final List<Future> inserts = new ArrayList<>();
        // 小 batch 插入，降低死锁数量
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> null, () -> 10, ignoreDuplicate, false));
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> 1L, () -> 10, ignoreDuplicate, false));

        //try {
        //    TimeUnit.SECONDS.sleep(1);
        //} catch (InterruptedException e) {
        //    // ignore exception
        //}

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck1(PRIMARY_TABLE_NAME, INDEX_NAME, false);
    }

    @Test
    public void multiPkRandomInsertTest3() {
        final String mysqlCreateTable = MessageFormat.format(MULTI_PK_TMPL1, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(MULTI_PK_TMPL1, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL1, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);

        // 并发插入随机主键，大概率出现死锁
        final Supplier<Pair<String, String>> pkGen = () -> Pair.of(RandomStringUtils.randomAlphabetic(32),
            RandomStringUtils.randomAlphabetic(32));

        final List<Future> inserts = new ArrayList<>();
        // 小 batch 插入，降低死锁数量
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> null, () -> 10, ignoreDuplicate, false));
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> 1L, () -> 10, ignoreDuplicate, false));

        //try {
        //    TimeUnit.SECONDS.sleep(1);
        //} catch (InterruptedException e) {
        //    // ignore exception
        //}

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck1(PRIMARY_TABLE_NAME, INDEX_NAME, false);
    }

    @Test
    public void multiPkRandomInsertTest4() {
        final String mysqlCreateTable = MessageFormat.format(MULTI_PK_TMPL2, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(MULTI_PK_TMPL2, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL1, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);

        // 并发插入随机主键，大概率出现死锁
        final Random random = new Random(System.currentTimeMillis());
        final Supplier<Pair<Long, String>> pkGen = () -> Pair.of(Math.abs(RandomUtils.nextLong(random)) % 100000,
            RandomStringUtils.randomAlphabetic(32));

        final List<Future> inserts = new ArrayList<>();
        // 小 batch 插入，降低死锁数量
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> null, () -> 10, ignoreDuplicate, false));
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> 1L, () -> 10, ignoreDuplicate, false));

        //try {
        //    TimeUnit.SECONDS.sleep(1);
        //} catch (InterruptedException e) {
        //    // ignore exception
        //}

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck1(PRIMARY_TABLE_NAME, INDEX_NAME, false);
    }

    @Test
    public void multiPkRandomInsertTest5() {
        final String mysqlCreateTable = MessageFormat.format(MULTI_PK_TMPL3, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(MULTI_PK_TMPL3, PRIMARY_TABLE_NAME, primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL1, PRIMARY_TABLE_NAME);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);

        // 并发插入随机主键，大概率出现死锁
        final Random random = new Random(System.currentTimeMillis());
        final Supplier<Pair<String, Long>> pkGen = () -> Pair.of(RandomStringUtils.randomAlphabetic(32),
            Math.abs(RandomUtils.nextLong(random)) % 100000);

        final List<Future> inserts = new ArrayList<>();
        // 小 batch 插入，降低死锁数量
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> null, () -> 10, ignoreDuplicate, false));
        inserts.add(launchInsertThread1(sqlInsert, stop, pkGen, () -> 1L, () -> 10, ignoreDuplicate, false));

        //try {
        //    TimeUnit.SECONDS.sleep(1);
        //} catch (InterruptedException e) {
        //    // ignore exception
        //}

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck1(PRIMARY_TABLE_NAME, INDEX_NAME, false);
    }

    /**
     * 测试delete only阶段update的行为（只删不写）
     */
    @Test
    public void testUpdateWithDeleteOnly() {

        JdbcUtil.executeUpdateSuccess(tddlConnection, "drop table if exists " + PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, "drop table if exists " + INDEX_NAME);

        final String mysqlCreateTable = MessageFormat.format(SINGLE_PK_TMPL2, PRIMARY_TABLE_NAME, "");
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL2, PRIMARY_TABLE_NAME, primaryShardingDef);

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        new Thread(() -> {
            try (Connection conn = ConnectionManager.getInstance().newPolarDBXConnection()) {
                JdbcUtil.useDb(conn, tddlDatabase1);
                final long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < 30000) {
                    final ResultSet rs = JdbcUtil.executeQuery("show global index", conn);
                    List<String> result = JdbcUtil.getStringResult(rs, false)
                        .stream()
                        .filter(row -> row.get(3).equalsIgnoreCase(INDEX_NAME))
                        .map(row -> row.get(13))
                        .collect(Collectors.toList());
                    if (1 == result.size()
                        &&
                        (result.get(0).equals("WRITE_ONLY")
                            || result.get(0).equals("WRITE_REORG")
                            || result.get(0).equals("PUBLIC")
                        )) {
                        // Now use hint to fake 2 status insert and update.
                        System.out.println("Start test update in delete only.");

                        JdbcUtil.executeUpdateSuccess(conn,
                            "/*+TDDL: cmd_extra(GSI_DEBUG=\"GsiStatus2\")*/insert into " + PRIMARY_TABLE_NAME
                                + " (c1, c2, c3) values(1, 'hoho', 'hehe')");
                        JdbcUtil.executeUpdateSuccess(conn,
                            "/*+TDDL: cmd_extra(GSI_DEBUG=\"GsiStatus1\")*/update " + PRIMARY_TABLE_NAME
                                + " set c3='haha' where c1 = 1");
                        JdbcUtil.executeUpdateSuccess(mysqlConnection,
                            "insert into " + PRIMARY_TABLE_NAME + " (c1, c2, c3) values(1, 'hoho', 'hehe')");
                        JdbcUtil.executeUpdateSuccess(mysqlConnection,
                            "update " + PRIMARY_TABLE_NAME + " set c3='haha' where c1 = 1");

                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception ignore) {
            }
        }).start();

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL2, INDEX_NAME, PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, "/*+TDDL: cmd_extra(GSI_DEBUG=\"slow\")*/" + sqlCreateGsi);

        try {
            Thread.sleep(1000);
        } catch (Exception ignore) {
        }

        gsiIntegrityCheck2(PRIMARY_TABLE_NAME, INDEX_NAME, true);

        JdbcUtil.executeUpdateSuccess(tddlConnection, "drop table if exists " + PRIMARY_TABLE_NAME);
        JdbcUtil.executeUpdateSuccess(tddlConnection, "drop table if exists " + INDEX_NAME);
    }

    @Test
    public void caseInsensitiveTest() {
        final String mysqlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME.toLowerCase(), "");
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME.toLowerCase(),
            primaryShardingDef);
        final String sqlInsert = MessageFormat.format(INSERT_TMPL, PRIMARY_TABLE_NAME.toUpperCase());

        JdbcUtil.executeUpdateSuccess(mysqlConnection, mysqlCreateTable);
        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Future> inserts = new ArrayList<>();
        inserts.add(launchInsertThread(sqlInsert, stop, () -> null, () -> 1000));
        inserts.add(launchInsertThread(sqlInsert, stop, () -> 1L, () -> 1000));
        inserts.add(launchInsertThread(sqlInsert, stop, () -> 3L, () -> RandomUtils.nextInt(2000)));
        inserts.add(launchInsertThread(sqlInsert, stop, () -> 5L, () -> RandomUtils.nextInt(2000)));

        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            // ignore exception
        }

        final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME.toLowerCase());
        JdbcUtil.executeUpdateSuccess(tddlConnection, HINT + sqlCreateGsi);

        stop.set(true);

        for (Future future : inserts) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        gsiIntegrityCheck(PRIMARY_TABLE_NAME, INDEX_NAME, true);
    }

    private void enableDynamicSpeed(long speed) {
        JdbcUtil.executeUpdateSuccess(tddlConnection,
            "/*+TDDL: node('__META_DB__')*/insert into inst_config (inst_id,param_key,param_val) values ('polardbx-polardbx','GENERAL_DYNAMIC_SPEED_LIMITATION','"
                + speed + "') on duplicate key update param_val='" + speed + "'");
        JdbcUtil.executeUpdateSuccess(tddlConnection,
            "/*+TDDL: node('__META_DB__')*/update config_listener set op_version=op_version+1 where data_id like \"polardbx.inst.config.%\"");
    }

    private void disableDynamicSpeed() {
        JdbcUtil.executeUpdateSuccess(tddlConnection,
            "/*+TDDL: node('__META_DB__')*/insert into inst_config (inst_id,param_key,param_val) values ('polardbx-polardbx','GENERAL_DYNAMIC_SPEED_LIMITATION','-1') on duplicate key update param_val='-1'");
        JdbcUtil.executeUpdateSuccess(tddlConnection,
            "/*+TDDL: node('__META_DB__')*/update config_listener set op_version=op_version+1 where data_id like \"polardbx.inst.config.%\"");
    }

    @Test
    @Ignore("fix by ???")
    public void dynamicSpeedTest() {
        final String tddlCreateTable = MessageFormat.format(SINGLE_PK_TMPL, PRIMARY_TABLE_NAME, primaryShardingDef);

        JdbcUtil.executeUpdateSuccess(tddlConnection, tddlCreateTable);

        // Generate and insert 1000 rows.
        for (int i = 0; i < 1000; ++i) {
            final String insert = MessageFormat
                .format("insert into {0}(c1, c2) values({1}, {2})", PRIMARY_TABLE_NAME, Long.toString(i),
                    Long.toString(i));
            JdbcUtil.executeUpdateSuccess(tddlConnection, insert);
        }

        try {
            enableDynamicSpeed(100000);

            final String sqlCreateGsi = MessageFormat.format(CREATE_GSI_TMPL, INDEX_NAME, PRIMARY_TABLE_NAME);
            final String SLOW_HINT =
                "/*+TDDL:CMD_EXTRA(GSI_BACKFILL_BATCH_SIZE=10, GSI_BACKFILL_SPEED_LIMITATION=10, GSI_BACKFILL_PARALLELISM=4, GSI_CHECK_BATCH_SIZE=10, GSI_CHECK_SPEED_LIMITATION=10, GSI_CHECK_PARALLELISM=4)*/";
            // Hint speed set to 10qps.

            final long startTime = System.currentTimeMillis();
            JdbcUtil.executeUpdateSuccess(tddlConnection, SLOW_HINT + sqlCreateGsi);
            final long endTime = System.currentTimeMillis();

            Assert.assertTrue(endTime - startTime < 100 * 1000,
                "Dynamic speed error. time: " + (endTime - startTime) + "ms");
        } finally {
            disableDynamicSpeed();
        }
    }

    private void gsiIntegrityCheck(String primary, String index, boolean compareWithMysql) {
        final String tddlSqlTmpl = "select id, c1, c2 from {0} order by id";
        final String tddlPrimarySql = MessageFormat.format(tddlSqlTmpl, primary);
        final String tddlIndexSql = MessageFormat.format(tddlSqlTmpl, index);

        final String mysqlSqlTmpl = "select c1, c2 from {0} order by c1, c2";
        final String mysqlPrimary = MessageFormat.format(mysqlSqlTmpl, primary);
        final String mysqlIndex = MessageFormat.format(mysqlSqlTmpl, index);

        gsiIntegrityCheck(tddlPrimarySql, tddlIndexSql, compareWithMysql, mysqlPrimary, mysqlIndex);
    }

    private void gsiIntegrityCheck1(String primary, String index, boolean compareWithMysql) {
        final String tddlSqlTmpl = "select id, id1, c1, c2 from {0} order by id, id1";
        final String tddlPrimarySql = MessageFormat.format(tddlSqlTmpl, primary);
        final String tddlIndexSql = MessageFormat.format(tddlSqlTmpl, index);

        final String mysqlSqlTmpl = "select c1, c2 from {0} order by c1, c2";
        final String mysqlPrimary = MessageFormat.format(mysqlSqlTmpl, primary);
        final String mysqlIndex = MessageFormat.format(mysqlSqlTmpl, index);

        gsiIntegrityCheck(tddlPrimarySql, tddlIndexSql, compareWithMysql, mysqlPrimary, mysqlIndex);
    }

    private void gsiIntegrityCheck2(String primary, String index, boolean compareWithMysql) {
        final String tddlSqlTmpl = "select id, c1, c2, c3 from {0} order by id";
        final String tddlPrimarySql = MessageFormat.format(tddlSqlTmpl, primary);
        final String tddlIndexSql = MessageFormat.format(tddlSqlTmpl, index);

        final String mysqlSqlTmpl = "select c1, c2, c3 from {0} order by c1, c2";
        final String mysqlPrimary = MessageFormat.format(mysqlSqlTmpl, primary);
        final String mysqlIndex = MessageFormat.format(mysqlSqlTmpl, index);

        gsiIntegrityCheck(tddlPrimarySql, tddlIndexSql, compareWithMysql, mysqlPrimary, mysqlIndex);
    }

    private void gsiIntegrityCheck(String primary, String index, boolean compareWithMysql, String mysqlPrimary,
                                   String mysqlIndex) {

        final ResultSet tddlPrimaryRs = JdbcUtil.executeQuerySuccess(tddlConnection, primary);
        final ResultSet tddlIndexRs = JdbcUtil.executeQuerySuccess(tddlConnection, index);

        resultSetContentSameAssert(tddlPrimaryRs, tddlIndexRs, false);

        if (compareWithMysql) {
            final ResultSet mysqlPrimaryRs = JdbcUtil.executeQuerySuccess(mysqlConnection, mysqlPrimary);
            final ResultSet mysqlIndexRs = JdbcUtil.executeQuerySuccess(tddlConnection, mysqlIndex);

            resultSetContentSameAssert(mysqlPrimaryRs, mysqlIndexRs, false);
        }
    }

    private Future<?> launchDmlCheckThread(String sqlInsert, String sqlUpdate, String sqlDelete,
                                           String sqlSelectPrimary, String sqlSelectGSI,
                                           AtomicBoolean stop, Supplier<Long> generateSk,
                                           Supplier<Integer> generateBatchSize) {
        return dmlPool.submit(new InsertRunner(stop, (conn) -> {
            // List<Pair< sql, error_message >>
            List<Pair<String, Exception>> failedList = new ArrayList<>();

            final ParameterContext skPc = Optional.ofNullable(generateSk.get())
                .map(skv -> new ParameterContext(ParameterMethod.setLong, new Object[] {1, skv}))
                .orElse(new ParameterContext(ParameterMethod.setNull1, new Object[] {1, null}));

            List<Map<Integer, ParameterContext>> batchParams = IntStream.range(0, generateBatchSize.get())
                .mapToObj(i -> ImmutableMap.<Integer, ParameterContext>builder()
                    .put(1, skPc)
                    .put(2,
                        new ParameterContext(ParameterMethod.setString,
                            new Object[] {2, RandomStringUtils.randomAlphabetic(20)}))
                    .build())
                .collect(Collectors.toList());

            int inserted = 0;
            try (PreparedStatement ps = conn.prepareStatement(sqlInsert)) {
                try {
                    lock.readLock().lock();
                    batchParams.forEach(params -> {
                        try {
                            ParameterMethod.setParameters(ps, params);
                            ps.addBatch();
                        } catch (SQLException e) {
                            throw GeneralUtil.nestedException(e);
                        }
                    });
                    int[] result = ps.executeBatch();
                    inserted = Optional.ofNullable(result).map(r -> Arrays.stream(r).map(v -> v / -2).sum()).orElse(0);
                } finally {
                    lock.readLock().unlock();
                }
            } catch (Exception e) {
                if (!e.getMessage().contains("Deadlock found when trying to get lock") &&
                    !e.getMessage().contains("Lock wait timeout exceeded")) {
                    throw GeneralUtil.nestedException(e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                }
            }

            try {
                lock.writeLock().lock();
                selectContentSameAssert(sqlSelectPrimary, sqlSelectGSI, null, conn, conn);
            } finally {
                lock.writeLock().unlock();
            }

            try (Statement stmt = conn.createStatement()) {
                try {
                    lock.readLock().lock();
                    stmt.execute(sqlUpdate);
                } finally {
                    lock.readLock().unlock();
                }
            } catch (Exception e) {
                if (!e.getMessage().contains("Deadlock found when trying to get lock") &&
                    !e.getMessage().contains("Lock wait timeout exceeded")) {
                    throw GeneralUtil.nestedException(e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                }
            }

            try {
                lock.writeLock().lock();
                selectContentSameAssert(sqlSelectPrimary, sqlSelectGSI, null, conn, conn);
            } finally {
                lock.writeLock().unlock();
            }

            try (Statement stmt = conn.createStatement()) {
                try {
                    lock.readLock().lock();
                    stmt.execute(sqlDelete);
                } finally {
                    lock.readLock().unlock();
                }
            } catch (Exception e) {
                if (!e.getMessage().contains("Deadlock found when trying to get lock") &&
                    !e.getMessage().contains("Lock wait timeout exceeded")) {
                    throw GeneralUtil.nestedException(e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignore) {
                }
            }

            try {
                lock.writeLock().lock();
                selectContentSameAssert(sqlSelectPrimary, sqlSelectGSI, null, conn, conn);
            } finally {
                lock.writeLock().unlock();
            }

            System.out.println(Thread.currentThread().getName() + " run DML and check.");

            return inserted;
        }, throwException, 100));
    }

    private Future<?> launchInsertSelectThread(String sqlInsertSelect, AtomicBoolean stop) {
        return dmlPool.submit(new InsertRunner(stop, (conn) -> {
            // List<Pair< sql, error_message >>
            List<Pair<String, Exception>> failedList = new ArrayList<>();

            try {
                return gsiExecuteUpdate(conn, mysqlConnection, sqlInsertSelect, failedList, true, true);

            } catch (SQLSyntaxErrorException e) {
                throw GeneralUtil.nestedException(e);
            }
        }));
    }

    private Future<?> launchInsertThread(String sqlInsert, AtomicBoolean stop, Supplier<Long> generateSk,
                                         Supplier<Integer> generateBatchSize) {
        return launchInsertThread(sqlInsert, stop, generateSk, generateBatchSize, throwException);
    }

    private Future<?> launchInsertThread(String sqlInsert, AtomicBoolean stop, Supplier<Long> generateSk,
                                         Supplier<Integer> generateBatchSize, Consumer<Exception> errHandler) {
        return dmlPool.submit(new InsertRunner(stop, (conn) -> {
            // List<Pair< sql, error_message >>
            List<Pair<String, Exception>> failedList = new ArrayList<>();

            final ParameterContext skPc = Optional.ofNullable(generateSk.get())
                .map(skv -> new ParameterContext(ParameterMethod.setLong, new Object[] {1, skv}))
                .orElse(new ParameterContext(ParameterMethod.setNull1, new Object[] {1, null}));

            List<Map<Integer, ParameterContext>> batchParams = IntStream.range(0, generateBatchSize.get())
                .mapToObj(i -> ImmutableMap.<Integer, ParameterContext>builder()
                    .put(1, skPc)
                    .put(2,
                        new ParameterContext(ParameterMethod.setString,
                            new Object[] {2, RandomStringUtils.randomAlphabetic(20)}))
                    .build())
                .collect(Collectors.toList());

            try {
                int[] result = gsiBatchUpdate(conn, mysqlConnection, sqlInsert, batchParams, failedList, true, true);
                return Optional.ofNullable(result).map(r -> Arrays.stream(r).map(v -> v / -2).sum()).orElse(0);
            } catch (SQLSyntaxErrorException e) {
                throw GeneralUtil.nestedException(e);
            }
        }, errHandler));
    }

    private <S, T> Future<?> launchInsertThread1(String sqlInsert, AtomicBoolean stop, Supplier<Pair<S, T>> pkGen,
                                                 Supplier<Long> generateSk,
                                                 Supplier<Integer> generateBatchSize, Consumer<Exception> errHandler,
                                                 boolean compareWithMySql) {
        return dmlPool.submit(new InsertRunner(stop, (conn) -> {
            // List<Pair< sql, error_message >>
            final List<Pair<String, Exception>> failedList = new ArrayList<>();

            List<Map<Integer, ParameterContext>> batchParams = IntStream.range(0, generateBatchSize.get())
                .mapToObj(i -> {
                    final Pair<S, T> pkPair = pkGen.get();
                    return ImmutableMap.<Integer, ParameterContext>builder()
                        .put(1,
                            Optional.ofNullable(pkPair.left)
                                .map(skv -> new ParameterContext(ParameterMethod.setObject1,
                                    new Object[] {1, pkPair.left}))
                                .orElse(new ParameterContext(ParameterMethod.setNull1, new Object[] {1, null})))
                        .put(2,
                            Optional.ofNullable(pkPair.right)
                                .map(skv -> new ParameterContext(ParameterMethod.setObject1,
                                    new Object[] {2, pkPair.right}))
                                .orElse(new ParameterContext(ParameterMethod.setNull1, new Object[] {2, null})))
                        .put(3,
                            Optional.ofNullable(generateSk.get())
                                .map(skv -> new ParameterContext(ParameterMethod.setLong, new Object[] {3, skv}))
                                .orElse(new ParameterContext(ParameterMethod.setNull1, new Object[] {3, null})))
                        .put(4,
                            new ParameterContext(ParameterMethod.setString,
                                new Object[] {4, RandomStringUtils.randomAlphabetic(20)}))
                        .build();
                })
                .collect(Collectors.toList());

            try {
                int[] result =
                    gsiBatchUpdate(conn, mysqlConnection, sqlInsert, batchParams, failedList, true, compareWithMySql);
                return Optional.ofNullable(result).map(r -> Arrays.stream(r).map(v -> v / -2).sum()).orElse(0);
            } catch (SQLSyntaxErrorException e) {
                throw GeneralUtil.nestedException(e);
            }
        }, errHandler));
    }

    private Future<?> launchInsertThread2(String sqlInsert, AtomicBoolean stop, Supplier<String> generatePk,
                                          Supplier<Long> generateSk, Supplier<Integer> generateBatchSize,
                                          Consumer<Exception> errHandler) {
        return dmlPool.submit(new InsertRunner(stop, (conn) -> {
            // List<Pair< sql, error_message >>
            List<Pair<String, Exception>> failedList = new ArrayList<>();

            List<Map<Integer, ParameterContext>> batchParams = IntStream.range(0, generateBatchSize.get())
                .mapToObj(i -> ImmutableMap.<Integer, ParameterContext>builder()
                    .put(1, new ParameterContext(ParameterMethod.setString, new Object[] {1, generatePk.get()}))
                    .put(2,
                        Optional.ofNullable(generateSk.get())
                            .map(skv -> new ParameterContext(ParameterMethod.setLong, new Object[] {2, skv}))
                            .orElse(new ParameterContext(ParameterMethod.setNull1, new Object[] {2, null})))
                    .put(3,
                        new ParameterContext(ParameterMethod.setString,
                            new Object[] {3, RandomStringUtils.randomAlphabetic(20)}))
                    .build())
                .collect(Collectors.toList());

            try {
                int[] result = gsiBatchUpdate(conn, mysqlConnection, sqlInsert, batchParams, failedList, true, true);
                return Optional.ofNullable(result).map(r -> Arrays.stream(r).map(v -> v / -2).sum()).orElse(0);
            } catch (SQLSyntaxErrorException e) {
                throw GeneralUtil.nestedException(e);
            }
        }, errHandler));
    }

    private class InsertRunner implements Runnable {

        private final AtomicBoolean stop;
        private final Function<Connection, Integer> call;
        private final Consumer<Exception> errHandler;
        private final int maxSeconds;

        public InsertRunner(AtomicBoolean stop, Function<Connection, Integer> call) {
            this(stop, call, throwException, 10); // Default insert for 10s.
        }

        public InsertRunner(AtomicBoolean stop, Function<Connection, Integer> call, Consumer<Exception> errHandler) {
            this(stop, call, errHandler, 10);
        }

        public InsertRunner(AtomicBoolean stop, Function<Connection, Integer> call, Consumer<Exception> errHandler,
                            int maxSeconds) {
            this.stop = stop;
            this.call = call;
            this.errHandler = errHandler;
            this.maxSeconds = maxSeconds;
        }

        @Override
        public void run() {
            final long startTime = System.currentTimeMillis();
            int count = 0;
            do {
                try (Connection conn = ConnectionManager.getInstance().newPolarDBXConnection()) {
                    JdbcUtil.useDb(conn, tddlDatabase1);
                    count += call.apply(conn);
                } catch (Exception e) {
                    errHandler.accept(e);
                }

                if (System.currentTimeMillis() - startTime > maxSeconds * 1000) {
                    break; // 10s timeout, because we check after create GSI(which makes create GSI far more slower.).
                }
            } while (!stop.get());

            System.out.println(Thread.currentThread().getName() + " quit after " + count + " records inserted");

        }

    }
}
