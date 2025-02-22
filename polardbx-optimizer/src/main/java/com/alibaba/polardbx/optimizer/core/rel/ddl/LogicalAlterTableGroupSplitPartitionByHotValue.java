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

package com.alibaba.polardbx.optimizer.core.rel.ddl;

import com.alibaba.polardbx.common.exception.TddlRuntimeException;
import com.alibaba.polardbx.common.exception.code.ErrorCode;
import com.alibaba.polardbx.druid.sql.SQLUtils;
import com.alibaba.polardbx.common.utils.CaseInsensitive;
import com.alibaba.polardbx.gms.tablegroup.PartitionGroupRecord;
import com.alibaba.polardbx.gms.tablegroup.TableGroupConfig;
import com.alibaba.polardbx.gms.tablegroup.TableGroupLocation;
import com.alibaba.polardbx.gms.topology.GroupDetailInfoExRecord;
import com.alibaba.polardbx.gms.util.PartitionNameUtil;
import com.alibaba.polardbx.optimizer.OptimizerContext;
import com.alibaba.polardbx.optimizer.config.table.ComplexTaskMetaManager;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.AlterTableGroupSplitPartitionByHotValuePreparedData;
import com.alibaba.polardbx.optimizer.partition.PartitionBoundVal;
import com.alibaba.polardbx.optimizer.partition.PartitionInfo;
import com.alibaba.polardbx.optimizer.partition.PartitionInfoUtil;
import com.alibaba.polardbx.optimizer.partition.PartitionSpec;
import com.alibaba.polardbx.optimizer.partition.PartitionStrategy;
import com.alibaba.polardbx.optimizer.partition.datatype.function.PartitionIntFunction;
import com.alibaba.polardbx.optimizer.partition.pruning.PartFieldAccessType;
import com.alibaba.polardbx.optimizer.partition.pruning.PartitionPrunerUtils;
import com.alibaba.polardbx.optimizer.partition.pruning.PartitionTupleRouteInfoBuilder;
import com.alibaba.polardbx.optimizer.partition.pruning.SearchDatumInfo;
import org.apache.calcite.rel.core.DDL;
import org.apache.calcite.rel.ddl.AlterTableGroupSplitPartitionByHotValue;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAlterTableGroup;
import org.apache.calcite.sql.SqlAlterTableGroupSplitPartitionByHotValue;
import org.apache.calcite.sql.SqlAlterTableSplitPartitionByHotValue;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class LogicalAlterTableGroupSplitPartitionByHotValue extends BaseDdlOperation {

    protected AlterTableGroupSplitPartitionByHotValuePreparedData preparedData;

    public LogicalAlterTableGroupSplitPartitionByHotValue(DDL ddl) {
        super(ddl);
    }

    private static class SplitPointContext {
        public PartitionInfo partInfo;
        public List<Long[]> splitPoints = new ArrayList<>();
        public int[] insertPos = {1, 1};
        public int flag;

        public SplitPointContext() {
        }
    }

    public void preparedData(ExecutionContext executionContext) {
        AlterTableGroupSplitPartitionByHotValue alterTableGroupSplitPartitionByHotValue =
            (AlterTableGroupSplitPartitionByHotValue) relDdl;
        String tableGroupName = alterTableGroupSplitPartitionByHotValue.getTableGroupName();
        SqlAlterTableGroup sqlAlterTableGroup = (SqlAlterTableGroup) alterTableGroupSplitPartitionByHotValue.getAst();
        assert sqlAlterTableGroup.getAlters().size() == 1;

        assert sqlAlterTableGroup.getAlters().get(0) instanceof SqlAlterTableGroupSplitPartitionByHotValue;
        SqlAlterTableGroupSplitPartitionByHotValue sqlAlterTableGroupSplitPartitionByHotValue =
            (SqlAlterTableGroupSplitPartitionByHotValue) sqlAlterTableGroup.getAlters().get(0);

        TableGroupConfig tableGroupConfig = OptimizerContext.getContext(schemaName).getTableGroupInfoManager()
            .getTableGroupConfigByName(tableGroupName);

        String firstTblNameInGroup = tableGroupConfig.getAllTables().get(0).getLogTbRec().tableName;
        Map<String, List<Long[]>> splitPointInfos = new TreeMap<>(CaseInsensitive.CASE_INSENSITIVE_ORDER);
        Map<String, SplitPointContext> splitPointCtxMap = new TreeMap<>(CaseInsensitive.CASE_INSENSITIVE_ORDER);

        List<PartitionInfo> allLogPartInfoList = new ArrayList<>();
        for (int i = 0; i < tableGroupConfig.getAllTables().size(); i++) {
            String tbNameInGrp = tableGroupConfig.getAllTables().get(i).getLogTbRec().tableName;
            PartitionInfo partInfo =
                OptimizerContext.getContext(schemaName).getPartitionInfoManager().getPartitionInfo(tbNameInGrp);
            allLogPartInfoList.add(partInfo);
        }

        for (int i = 0; i < allLogPartInfoList.size(); i++) {
            SplitPointContext splitPointCtx = new SplitPointContext();
            PartitionInfo partInfo = allLogPartInfoList.get(i);
            String tbNameInGrp = partInfo.getTableName();

            List<Long[]> splitPoints = new ArrayList<>();
            int[] insertPos = {1, 1};

            /**
             * flag =
             * 2: both first new part and last new part are hot value(means all new parts are hot value)
             * -1: only first new part not include hot value
             * 1: only the last new part not include hot value
             * 0: neither of first new part and last new part is hot value
             */
            int flag = normalizeSqlSplitPartitionByHotValue(sqlAlterTableGroupSplitPartitionByHotValue, partInfo,
                alterTableGroupSplitPartitionByHotValue.getPartBoundExprInfo(),
                executionContext, splitPoints, insertPos);

            splitPointCtx.partInfo = partInfo;
            splitPointCtx.splitPoints = splitPoints;
            splitPointCtx.insertPos = insertPos;
            splitPointCtx.flag = flag;
            splitPointInfos.put(tbNameInGrp, splitPoints);
            splitPointCtxMap.put(tbNameInGrp, splitPointCtx);
        }

//        String tableInCurrentGroup = tableGroupConfig.getAllTables().get(0).getLogTbRec().tableName;
//        PartitionInfo partitionInfo =
//            OptimizerContext.getContext(schemaName).getPartitionInfoManager().getPartitionInfo(tableInCurrentGroup);
//        List<Long[]> splitPoints = new ArrayList<>();
//        int[] insertPos = {1, 1};
//
//        /**
//         * flag =
//         * 2: both first new part and last new part are hot value(means all new parts are hot value)
//         * -1: only first new part not include hot value
//         * 1: only the last new part not include hot value
//         * 0: neither of first new part and last new part is hot value
//         */
//        int flag = normalizeSqlSplitPartitionByHotValue(sqlAlterTableGroupSplitPartitionByHotValue, partitionInfo,
//            alterTableGroupSplitPartitionByHotValue.getPartBoundExprInfo(),
//            executionContext, splitPoints, insertPos);

        String hotKeyPartNamePrefix = StringUtils.EMPTY;
        if (sqlAlterTableGroupSplitPartitionByHotValue.getHotKeyPartitionName() != null) {
            hotKeyPartNamePrefix = SQLUtils.normalizeNoTrim(sqlAlterTableGroupSplitPartitionByHotValue.getHotKeyPartitionName().toString());
        }

        SplitPointContext firstTbInGrpSplitPointCtx = splitPointCtxMap.get(firstTblNameInGroup);
        PartitionInfo firstTblPartInfo = firstTbInGrpSplitPointCtx.partInfo;
        List<String> oldPartitions = new ArrayList<>();
        Set<String> oldPartitionNameSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<GroupDetailInfoExRecord> targetGroupDetailInfoExRecords =
            TableGroupLocation.getOrderedGroupList(schemaName);

        int[] insertPos = firstTbInGrpSplitPointCtx.insertPos;
        List<Long[]> splitPoints = firstTbInGrpSplitPointCtx.splitPoints;
        int flag = firstTbInGrpSplitPointCtx.flag;

        int i = insertPos[1];
        do {
            String oldPartitionName = firstTblPartInfo.getPartitionBy().getNthPartition(i).getName();
            oldPartitions.add(oldPartitionName);
            oldPartitionNameSet.add(oldPartitionName);
            i--;
        } while (i > insertPos[0]);

        List<String> newPartitionNames =
            generateNewPartitionNames(tableGroupConfig, oldPartitionNameSet, hotKeyPartNamePrefix, splitPoints.size(),
                flag);
        preparedData = new AlterTableGroupSplitPartitionByHotValuePreparedData();

        preparedData.setSchemaName(schemaName);
        preparedData.setWithHint(targetTablesHintCache != null);

        Collections.reverse(oldPartitions);
        preparedData.setOldPartitionNames(oldPartitions);
        preparedData.setNewPartitionNames(newPartitionNames);
        preparedData.setTableGroupName(tableGroupName);
        preparedData.setInsertPos(insertPos);
        preparedData.setSplitPointInfos(splitPointInfos);
        preparedData.setTargetGroupDetailInfoExRecords(targetGroupDetailInfoExRecords);
        preparedData.prepareInvisiblePartitionGroup();
        preparedData.setTaskType(ComplexTaskMetaManager.ComplexTaskType.SPLIT_HOT_VALUE);
        preparedData.setHotKeyPartitionName(hotKeyPartNamePrefix);
    }

    public List<String> generateNewPartitionNames(TableGroupConfig tableGroupConfig, Set<String> oldPartitionNames,
                                                  String hotKeyPartNamePrefix,
                                                  int splitSize, int flag) {
        List<String> newPartitionNames = null;
        Set<String> newPartitionNameSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (hotKeyPartNamePrefix.isEmpty() || PartitionNameUtil.isDefaultPartNamePattern(hotKeyPartNamePrefix)) {
            newPartitionNames =
                PartitionNameUtil.autoGeneratePartitionNames(tableGroupConfig, splitSize + 1);
        } else {
            if (flag == 2) {
                newPartitionNames = PartitionNameUtil
                    .autoGeneratePartitionNamesWithUserDefPrefix(hotKeyPartNamePrefix, splitSize + 1);
            } else if (flag == -1) {
                newPartitionNames =
                    PartitionNameUtil.autoGeneratePartitionNames(tableGroupConfig, 1);
                newPartitionNames.addAll(PartitionNameUtil
                    .autoGeneratePartitionNamesWithUserDefPrefix(hotKeyPartNamePrefix, splitSize));
            } else if (flag == 1) {
                newPartitionNames = PartitionNameUtil
                    .autoGeneratePartitionNamesWithUserDefPrefix(hotKeyPartNamePrefix, splitSize + 1);
                newPartitionNames.addAll(PartitionNameUtil.autoGeneratePartitionNames(tableGroupConfig, 1));
            } else if (flag == 0) {
                newPartitionNames = new ArrayList<>();
                List<String> boundPartNames = PartitionNameUtil.autoGeneratePartitionNames(tableGroupConfig, 2);
                newPartitionNames.add(boundPartNames.get(0));
                newPartitionNames.addAll(PartitionNameUtil
                    .autoGeneratePartitionNamesWithUserDefPrefix(hotKeyPartNamePrefix, splitSize + 1 - 2));
                newPartitionNames.add(boundPartNames.get(1));
            } else {
                assert false;
            }
        }
        newPartitionNames.forEach(o -> newPartitionNameSet.add(o));

        for (PartitionGroupRecord record : tableGroupConfig.getPartitionGroupRecords()) {
            if (newPartitionNames.contains(record.partition_name) && !oldPartitionNames
                .contains(record.partition_name)) {
                throw new TddlRuntimeException(ErrorCode.ERR_PARTITION_MANAGEMENT,
                    String.format("duplicate partition name:[%s]", record.partition_name));
            }
        }
        return newPartitionNames;
    }

    protected int normalizeSqlSplitPartitionByHotValue(
        SqlAlterTableSplitPartitionByHotValue sqlAlterTableSplitPartitionByHotValue,
        PartitionInfo partitionInfo,
        Map<SqlNode, RexNode> partBoundExprInfo,
        ExecutionContext executionContext,
        List<Long[]> splitPoints,
        int[] insertPos) {

        List<SqlNode> hotKeys = sqlAlterTableSplitPartitionByHotValue.getHotKeys();
        List<RexNode> hotKeysRexNode = new ArrayList<>();
        for (SqlNode hotKey : hotKeys) {
            RexNode rexNode = partBoundExprInfo.get(hotKey);
            hotKeysRexNode.add(rexNode);
        }

        String schemaName = partitionInfo.getTableSchema();
        String tblName = partitionInfo.getTableName();
        int partColCnt = partitionInfo.getPartitionColumns().size();
        PartitionStrategy strategy = partitionInfo.getPartitionBy().getStrategy();
        int hotKeyValColCnt = hotKeys.size();
        SqlNumericLiteral splitPartCntLiteral =
            (SqlNumericLiteral) sqlAlterTableSplitPartitionByHotValue.getPartitions();
        int splitPartCnt = splitPartCntLiteral.intValue(true);
        if (!(strategy == PartitionStrategy.KEY || strategy == PartitionStrategy.RANGE_COLUMNS)) {
            throw new TddlRuntimeException(ErrorCode.ERR_PARTITION_MANAGEMENT,
                "only support for key/range column partition to split partition by hot value");
        }

        if (hotKeyValColCnt > partColCnt) {
            throw new TddlRuntimeException(ErrorCode.ERR_PARTITION_MANAGEMENT,
                "the column count of hot key should less than partition columns");
        } else {
            if (hotKeyValColCnt == partColCnt && splitPartCnt != 1) {
                throw new TddlRuntimeException(ErrorCode.ERR_PARTITION_MANAGEMENT,
                    String.format(
                        "only one split partition is allowed when the column count of hot key is the same as the full partition columns of table[%s.%s]",
                        schemaName, tblName));
            }
        }

        return getSplitPointsForHotValue(hotKeysRexNode, sqlAlterTableSplitPartitionByHotValue.getPartitions(),
            partitionInfo, executionContext, splitPoints, insertPos);

    }

    public AlterTableGroupSplitPartitionByHotValuePreparedData getPreparedData() {
        return preparedData;
    }

    public static LogicalAlterTableGroupSplitPartitionByHotValue create(DDL ddl) {
        return new LogicalAlterTableGroupSplitPartitionByHotValue(ddl);
    }


    /*原来(最大值max, 最小值:0)：
    p1:a1,max,max
    p2:a2,max,max
    p3:a3,max,max


    第1次热点分裂后(ah是热点):
    p1:a1,max,max
    p2:a2,max,max
    p_ah_low_bnd:ah-1,max,max
    p_ah_1:ah,max/4,max
    p_ah_2:ah,max/2,max
    p_ah_3:ah,3max/4,max
    p_ah_4:ah+1,max,max
    p3:a3,max,max

    p2与p_ah_low_bnd恰好相等，则合并
    p_ah_4与p3恰好相等，则合并
    如果ah=0，则p_ah_low_bnd：0，max-1，max
    ah不可能等于max，因为我们计算的MAX(hashCode)=max-1
    p_ah_4一定不大于p3*/
    /**
     * <pre>
     * case1: one-col, split 1 partition by hot val hashcode of ah:
     * p1:a1
     * p2:a2
     * p3:a3
     * =》after splitting
     * p1:a1
     * p2:a2
     * p_ah_low_bnd:ah-1
     * p_ah_1: ah+1,
     * { [ah-1, ah+1) including target range [ah,ah] ) }
     * p3:a3
     *
     * case2_1: two-col, split 1 partition by prefix one col hot val hashcode  of ah :
     * p1:a1,max
     * p2:a2,max
     * p3:a3,max
     * =》 after splitting
     * p1:a1,max
     * p2:a2,max
     * p_ah_low_bnd:ah-1,max
     * p_ah_1: ah+1,max
     *  { [(ah-1,max), (ah+1,max)) including target range [(ah,min),(ah,max)] ) }
     * p3:a3,max
     *
     * case2_2: two-col, split 4 partition by prefix 1 col hot val hashcode  of ah :
     * p1:a1,max
     * p2:a2,max
     * p3:a3,max
     * =》 after splitting
     * p1:a1,max
     * p2:a2,max
     * p_ah_low_bnd:ah-1,max
     * p_ah_1: ah,max/4
     * p_ah_2: ah,2*max/4
     * p_ah_3: ah,3*max/4
     * p_ah_4: ah+1,max
     *      { [(ah-1,max), (ah+1,max)) including target range [(ah,min),(ah,max)] ) }
     * p3:a3,max
     *
     * case2_3: two-col, split 1 partition by prefix 2 col hot val hashcode  of (ah,ah2) :
     * p1:a1,max
     * p2:a2,max
     * p3:a3,max
     * =》 after splitting
     * p1:a1,max
     * p2:a2,max
     * p_ah_ah2_low_bnd:ah,ah2-1
     * p_ah_ah2_1: ah,ah2+1
     * { [(ah,ah2-1), (ah,ah2+1)) including target range [(ah,ah2),(ah,ah2)] ) }
     * p3:a3,max
     *
     * case3_1: three-col, split 4 partition by prefix 2 col hot val hashcode  of (ah,ah2) :
     * p1:a1,max,max
     * p2:a2,max,max
     * p3:a3,max,max
     * =》 after splitting
     * p1:a1,max,max
     * p2:a2,max,max
     * p_ah_ah2_low_bnd:ah,ah2-1,max
     * p_ah_ah2_1: ah,ah2,max/4
     * p_ah_ah2_2: ah,ah2,2*max/4
     * p_ah_ah2_3: ah,ah2,3*max/4
     * p_ah_ah2_4: ah,ah2+1,max
     *  { [(ah,ah2-1,max), (ah,ah2+1,max)) including target range [(ah,ah2,min),(ah,ah2,max)] ) }
     * p3:a3,max,max
     *
     * case3_2: three-col, split 1 partition by prefix 2 col hot val hashcode  of (ah,ah2) :
     * p1:a1,max,max
     * p2:a2,max,max
     * p3:a3,max,max
     * =》 after splitting
     * p1:a1,max,max
     * p2:a2,max,max
     * p_ah_ah2_low_bnd:ah,ah2-1,max
     * p_ah_ah2_1: ah,ah2+1,max
     *  { [(ah,ah2-1,max), (ah,ah2+1,max)) including target range [(ah,ah2,min),(ah,ah2,max)] ) }
     * p3:a3,max,max
     *
     * case3_3: three-col, split 1 partition by prefix 3 col hot val hashcode  of (ah,ah2,ah3) :
     * p1:a1,max,max
     * p2:a2,max,max
     * p3:a3,max,max
     * =》 after splitting
     * p1:a1,max,max
     * p2:a2,max,max
     * p_ah_ah2_ah3_low_bnd:ah,ah2,ah3-1
     * p_ah_ah2_ah3_1: ah,ah2,ah3+1
     *  { [(ah-1,ah2,ah3-1), (ah,ah2,ah3+1)) including target range [(ah,ah2,ah3),(ah,ah2,ah3)] ) }
     * p3:a3,max,max
     *
     * case3_4: three-col, split 1 partition by prefix 1 col hot val hashcode  of (ah) :
     * p1:a1,max,max
     * p2:a2,max,max
     * p3:a3,max,max
     * =》 after splitting
     * p1:a1,max,max
     * p2:a2,max,max
     * p_ah_low_bnd:ah-1,max,max
     * p_ah_1: ah+1,max,max
     *  { [(ah-1,max,max), (a+1,max,max)) including target range [(ah,min,min),(ah,max,max)] ) }
     * p3:a3,max,max
     *
     * case3_5: three-col, split 4 partition by prefix 1 col hot val hashcode  of (ah) :
     * p1:a1,max,max
     * p2:a2,max,max
     * p3:a3,max,max
     * =》 after splitting
     * p1:a1,max,max
     * p2:a2,max,max
     * p_ah_low_bnd:ah-1,max,max
     * p_ah_1: ah,max/4,max
     * p_ah_2: ah,2*max/4,max
     * p_ah_3: ah,3*max/4,max
     * p_ah_4: ah+1,max,max
     *  { [(ah-1,max,max), (a+1,max,max)) including target range [(ah,min,min),(ah,max,max)] ) }
     * p3:a3,max,max
     *
     * ...
     *
     *
     * </pre>
     */
    private int getSplitPointsForHotValue(List<RexNode> hotKeys,
                                          SqlNode partitions,
                                          PartitionInfo partitionInfo,
                                          ExecutionContext ec,
                                          List<Long[]> outputFinalSplitPoints,
                                          int[] outputInsertPos) {

        List<RelDataType> relDataTypes = partitionInfo.getPartitionBy().getPartitionExprTypeList();
        PartitionIntFunction partIntFunc = partitionInfo.getPartitionBy().getPartIntFunc();
        PartitionStrategy strategy = partitionInfo.getPartitionBy().getStrategy();
        List<PartitionBoundVal> oneBndVal = new ArrayList<>();
        int partColCnt = partitionInfo.getPartitionColumns().size();
        int hotKeyValColCnt = hotKeys.size();
        int splitIntoParts = ((SqlNumericLiteral) partitions).intValue(true);

        /**
         * Generate hash code for each col value of a hot val
         */
        for (int i = 0; i < hotKeyValColCnt; i++) {
            RexNode oneBndExpr = hotKeys.get(i);
            RelDataType bndValDt = relDataTypes.get(i);
            PartitionInfoUtil.validateBoundValueExpr(oneBndExpr, bndValDt, partIntFunc, strategy);
            PartitionBoundVal bndVal =
                PartitionPrunerUtils
                    .getBoundValByRexExpr(oneBndExpr, bndValDt, PartFieldAccessType.DDL_EXECUTION, ec);
            oneBndVal.add(bndVal);
        }
        SearchDatumInfo datum = new SearchDatumInfo(oneBndVal);
        Long[] hotValHashCodeArr = partitionInfo.getPartitionBy().getHasher().calcHashCodeForKeyStrategy(datum);

        /**
         * Compute the delta range for each new split partitions of hot value
         */
        long rngDelta = PartitionInfoUtil.getHashSpaceMaxValue();
        if (splitIntoParts > 0) {
            rngDelta = 2 * (PartitionInfoUtil.getHashSpaceMaxValue() / splitIntoParts);
        }

        /**
         * Generate hash code for the lower bound of all the new split partitions of hot value
         */
        Long[] lowerBndHashCodeArr = new Long[partColCnt];
        int lastHotKeyValColIdx = hotKeyValColCnt - 1;
        for (int i = 0; i < hotKeyValColCnt - 1; i++) {
            lowerBndHashCodeArr[i] = hotValHashCodeArr[i];
        }
        long lastColHashValOfHotVal = hotValHashCodeArr[lastHotKeyValColIdx];
        // the value of hashCode will be the range [Long.min+1, Long.max-1],
        // so lastColHashValOfHotVal-1 will not be low over stack
        long lastColHashValOfLowerBndVal = lastColHashValOfHotVal - 1;
        lowerBndHashCodeArr[lastHotKeyValColIdx] = lastColHashValOfLowerBndVal;
        for (int i = hotKeyValColCnt; i < partColCnt; i++) {
            lowerBndHashCodeArr[i] = PartitionInfoUtil.getHashSpaceMaxValue();
        }

        /**
         * Generate hash code for the upper bound (just bound value of the last partition)
         * of all the new split partitions of hot value
         */
        Long[] upperBndHashCodeArr = new Long[partColCnt];
        for (int i = 0; i < hotKeyValColCnt - 1; i++) {
            upperBndHashCodeArr[i] = hotValHashCodeArr[i];
        }
        // the value of hashCode will be the range [Long.min+1, Long.max-1],
        // so lastColHashValOfHotVal-1 will not be up over stack
        long lastColHashValOfUpperBndVal = lastColHashValOfHotVal + 1;
        upperBndHashCodeArr[lastHotKeyValColIdx] = lastColHashValOfUpperBndVal;
        for (int i = hotKeyValColCnt; i < partColCnt; i++) {
            upperBndHashCodeArr[i] = PartitionInfoUtil.getHashSpaceMaxValue();
        }

        /**
         * Generate bound value for each partition to be split
         */
        List<Long[]> splitPoints = new ArrayList<>();
        splitPoints.add(lowerBndHashCodeArr);
        int nextColIdxOfHotVal = hotKeyValColCnt;
        for (int i = 0; i < splitIntoParts - 1; i++) {
            Long[] newPartBndValue = new Long[partColCnt];
            for (int k = 0; k < hotKeyValColCnt; k++) {
                newPartBndValue[k] = hotValHashCodeArr[k];
            }
            if (nextColIdxOfHotVal < partColCnt ) {
                newPartBndValue[nextColIdxOfHotVal] = PartitionInfoUtil.getHashSpaceMinValue() +  (i + 1) * rngDelta;
            }
            for (int k = nextColIdxOfHotVal + 1; k < partColCnt; k++) {
                newPartBndValue[k] = PartitionInfoUtil.getHashSpaceMaxValue();
            }
            splitPoints.add(newPartBndValue);
        }
        splitPoints.add(upperBndHashCodeArr);
        return generateFinalSplitPoints(partitionInfo, splitPoints, ec, outputFinalSplitPoints, outputInsertPos);
    }

    private int getSplitPointsForHotValue2(List<RexNode> hotKeys,
                                          SqlNode partitions,
                                          PartitionInfo partitionInfo,
                                          ExecutionContext ec,
                                          List<Long[]> finalSplitPoints,
                                          int[] insertPos) {
        List<RelDataType> relDataTypes = partitionInfo.getPartitionBy().getPartitionExprTypeList();
        PartitionIntFunction partIntFunc = partitionInfo.getPartitionBy().getPartIntFunc();
        PartitionStrategy strategy = partitionInfo.getPartitionBy().getStrategy();
        List<PartitionBoundVal> oneBndVal = new ArrayList<>();
        int partColCnt = partitionInfo.getPartitionColumns().size();
        int hotKeyValColCnt = hotKeys.size();
        int splitIntoParts = ((SqlNumericLiteral) partitions).intValue(true);
        for (int i = 0; i < hotKeyValColCnt; i++) {
            RexNode oneBndExpr = hotKeys.get(i);
            RelDataType bndValDt = relDataTypes.get(i);
            PartitionInfoUtil.validateBoundValueExpr(oneBndExpr, bndValDt, partIntFunc, strategy);
            PartitionBoundVal bndVal =
                PartitionPrunerUtils
                    .getBoundValByRexExpr(oneBndExpr, bndValDt, PartFieldAccessType.DDL_EXECUTION, ec);
            oneBndVal.add(bndVal);
        }

        SearchDatumInfo datum = new SearchDatumInfo(oneBndVal);
        Long[] hashVals = partitionInfo.getPartitionBy().getHasher().calcHashCodeForKeyStrategy(datum);
        List<Long[]> splitPoints = new ArrayList<>();
        long valInterval = 2 * (PartitionInfoUtil.getHashSpaceMaxValue() / splitIntoParts);
        boolean isLowerBound = false;
        for (int i = 0; i < splitIntoParts + 1; i++) {
            Long[] partHashRange = new Long[partColCnt];
            for (int j = 0; j < partColCnt; j++) {
                if (i == 0) {
                    if (j + 1 == hotKeyValColCnt) {
                        if (hashVals[j] > PartitionInfoUtil.getHashSpaceMinValue()) {
                            partHashRange[j] = hashVals[j] - 1;
                        } else {
                            partHashRange[j] = hashVals[j];
                            isLowerBound = true;
                        }
                    } else if (j + 1 > hotKeyValColCnt) {
                        if (!isLowerBound) {
                            partHashRange[j] = PartitionInfoUtil.getHashSpaceMaxValue();
                        } else {
                            partHashRange[j] = PartitionInfoUtil.getHashSpaceMaxValue() - 1;
                            isLowerBound = false;
                        }
                    } else {
                        partHashRange[j] = hashVals[j];
                    }
                } else if (i < splitIntoParts) {
                    if (j == hotKeyValColCnt) {
                        partHashRange[j] = PartitionInfoUtil.getHashSpaceMinValue() + valInterval * i;
                    } else if (j > hotKeyValColCnt) {
                        partHashRange[j] = PartitionInfoUtil.getHashSpaceMaxValue();
                    } else {
                        partHashRange[j] = hashVals[j];
                    }
                } else {
                    if (j == hotKeyValColCnt - 1) {
                        partHashRange[j] = hashVals[j] + 1;
                    } else if (j > hotKeyValColCnt - 1) {
                        partHashRange[j] = PartitionInfoUtil.getHashSpaceMinValue();
                    } else {
                        partHashRange[j] = hashVals[j];
                    }
                }
            }

            splitPoints.add(partHashRange);
        }

        return generateFinalSplitPoints(partitionInfo, splitPoints, ec, finalSplitPoints, insertPos);
    }

    /**
     * @return 2: both first new part and last new part are hot value(means all new parts are hot value)
     * -1: only first new part not include hot value
     * 1: only the last new part not include hot value
     * 0: neither of first new part and last new part is hot value
     */
    private int generateFinalSplitPoints(PartitionInfo partitionInfo, List<Long[]> splitPoints,
                                         ExecutionContext ec, List<Long[]> finalSplitPoints, int[] insertPos) {
        boolean firstPartIsNotHotValue = false;
        boolean lastPartIsNotHotValue = false;
        for (int i = 0; i < splitPoints.size(); i++) {
            SearchDatumInfo searchDatumInfo = SearchDatumInfo.createFromHashCodes(splitPoints.get(i));
            PartitionSpec partitionSpec =
                PartitionTupleRouteInfoBuilder.getPartitionSpecByHashCode(splitPoints.get(i), partitionInfo, ec);
            if (partitionSpec == null) {
                throw new TddlRuntimeException(ErrorCode.ERR_PARTITION_MANAGEMENT,
                    "can't find the correct split point");
            }
            if (i == 0 || i == splitPoints.size() - 1) {
                if (partitionSpec.getPosition() != 1) {
                    PartitionSpec prePartSpec =
                        partitionInfo.getPartitionBy().getNthPartition(partitionSpec.getPosition().intValue() - 1);
                    if (prePartSpec.getBoundSpaceComparator()
                        .compare(prePartSpec.getBoundSpec().getSingleDatum(), searchDatumInfo) != 0) {
                        finalSplitPoints.add(splitPoints.get(i));
                        if (i == 0) {
                            insertPos[0] = partitionSpec.getPosition().intValue() - 1;
                        } else {
                            insertPos[1] = partitionSpec.getPosition().intValue();
                        }
                    } else {//else the split point is equal to the prePartSpec, merge it
                        if (i == 0) {
                            insertPos[0] = partitionSpec.getPosition().intValue() - 1;
                            firstPartIsNotHotValue = true;
                        } else {
                            insertPos[1] = partitionSpec.getPosition().intValue() - 1;
                            lastPartIsNotHotValue = true;
                        }
                    }

                } else {
                    finalSplitPoints.add(splitPoints.get(i));
                    insertPos[0] = partitionSpec.getPosition().intValue();
                }
            } else {
                finalSplitPoints.add(splitPoints.get(i));
            }
        }
        if (firstPartIsNotHotValue && lastPartIsNotHotValue) {
            return 2;
        } else if (firstPartIsNotHotValue) {
            return -1;
        } else if (lastPartIsNotHotValue) {
            return 1;
        } else {
            return 0;
        }
    }

}
