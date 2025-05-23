// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.HintNode;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.catalog.ArrayType;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.TableFunction;
import com.starrocks.catalog.Type;
import com.starrocks.common.Pair;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.optimizer.JoinHelper;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalTableFunctionOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalValuesOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.ArrayOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CaseWhenOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.IsNullPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ReplaceColumnRefRewriter;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriter;
import com.starrocks.sql.optimizer.rule.Rule;
import com.starrocks.sql.optimizer.rule.RuleType;
import com.starrocks.sql.optimizer.statistics.ColumnStatistic;
import com.starrocks.sql.optimizer.statistics.Statistics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/***
 *  SQL : select * from t0 join[skew|t0.v1(1,2,3)] t1 on t0.v1 = t1.v4;
 *
 *     Join[skew|t0.v1(skewValueList)](v1 = v4)              Join (v1 = v4 and rand_col1 = rand_cal2)
 *      /                         \            =>             /                                   \
 *     t0                         t1                    Project(v1, rand_col1)             Project(v4, rand_col2)
 *                                                          |                                       |
 *                                                       t0                              left join(v1 = unnest)
 *                                                                                       /               \
 *                                                                                     t1          generate_serials
 *                                                                                              (0~skewJoinRandRange)
 *                                                                                                       |
 *                                                                                          unnest(Array[skewValueList])
 *
 *
 *
 *  rand_col1 : case when v1 is NULL then round(rand() * skewJoinRandRange
 *              when v1 in (1,2,3) then round(rand() * skewJoinRandRange) else 0 end)
 *  rand_col2 : case when generate_serials is NOT NULL generate_serials else 0 end
 *  skewJoinRandRange is a session variable, default value is 1000
 *  skewValueList is a list of skew values, need to be set by user, e.g. (1,2,3) is a list of skew values
 */

public class SkewJoinOptimizeRule extends TransformationRule {
    private static final Logger LOG = LogManager.getLogger(SkewJoinOptimizeRule.class);

    private static final String RAND_COL = "rand_col";

    public SkewJoinOptimizeRule() {
        super(RuleType.TF_SKEW_JOIN_OPTIMIZE_RULE,
                Pattern.create(OperatorType.LOGICAL_JOIN, OperatorType.PATTERN_LEAF, OperatorType.PATTERN_LEAF));
    }

    @Override
    public List<Rule> successorRules() {
        // skew join generate new join and on predicate, need to push down join on expression to child project again
        return Lists.newArrayList(new PushDownJoinOnExpressionToChildProject());
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        // respect the join hint
        if (((LogicalJoinOperator) input.getOp()).getJoinHint().equals(HintNode.HINT_JOIN_SKEW)) {
            return true;
        }

        if (!context.getSessionVariable().isEnableStatsToOptimizeSkewJoin()) {
            return false;
        }
        LogicalJoinOperator joinOperator = (LogicalJoinOperator) input.getOp();
        JoinOperator joinType = joinOperator.getJoinType();
        if (joinType != JoinOperator.INNER_JOIN && joinType != JoinOperator.LEFT_OUTER_JOIN) {
            // only support inner join and left join
            return false;
        }

        ColumnRefSet leftOutputColumns = input.inputAt(0).getOutputColumns();
        ColumnRefSet rightOutputColumns = input.inputAt(1).getOutputColumns();

        List<BinaryPredicateOperator> equalConjs = JoinHelper.
                getEqualsPredicate(leftOutputColumns, rightOutputColumns,
                        Utils.extractConjuncts(joinOperator.getOnPredicate()));
        if (equalConjs.isEmpty()) {
            return false;
        }
        Statistics leftChildStats = input.inputAt(0).getStatistics();
        if (leftChildStats == null) {
            return false;
        }
        double leftRowCount = leftChildStats.getOutputRowCount();

        for (BinaryPredicateOperator equalConj : equalConjs) {
            if (!equalConj.getChild(0).isColumnRef() || !equalConj.getChild(1).isColumnRef()) {
                // only support column equal column
                continue;
            }
            ColumnRefOperator leftColumn = (ColumnRefOperator) equalConj.getChild(0);
            ColumnRefOperator rightColumn = (ColumnRefOperator) equalConj.getChild(1);
            ColumnRefOperator skewJoinColumn = null;
            // choose the skew join column, it could be left or right column of the predicate
            if (leftOutputColumns.contains(leftColumn.getId())) {
                skewJoinColumn = leftColumn;
            } else {
                skewJoinColumn = rightColumn;
            }
            if (!leftChildStats.getColumnStatistics().containsKey(skewJoinColumn)) {
                continue;
            }
            ColumnStatistic leftColumnStats = leftChildStats.getColumnStatistic(skewJoinColumn);
            if (leftColumnStats == null || leftColumnStats.getHistogram() == null) {
                // only support column with histogram stats
                continue;
            }
            // sort the MCV by value
            List<Pair<String, Long>> leftChildMCV = Lists.newArrayList();
            int useMCVCount = Math.min(context.getSessionVariable().getSkewJoinOptimizeUseMCVCount(),
                    leftColumnStats.getHistogram().getMCV().size());
            leftColumnStats.getHistogram().getMCV().entrySet().stream().
                    sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).limit(useMCVCount).
                    forEach(entry -> {
                        leftChildMCV.add(Pair.create(entry.getKey(), entry.getValue()));
                    });
            if (isDataSkew(leftChildMCV, leftRowCount, context.getSessionVariable())) {
                joinOperator.setSkewColumn(skewJoinColumn);
                joinOperator.setSkewValues(leftChildMCV.stream().map(pair -> ConstantOperator.createVarchar(pair.first)).
                        collect(Collectors.toList()));
                return true;
            }
        }
        return false;
    }

    private boolean isDataSkew(List<Pair<String, Long>> mcvList, double rowCount, SessionVariable sessionVariable) {
        if (rowCount < 1) {
            return false;
        }
        long mcvRowCount = mcvList.stream().mapToLong(pair -> pair.second).sum();
        return ((double) mcvRowCount / rowCount) > sessionVariable.getSkewJoinDataSkewThreshold();
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalJoinOperator oldJoinOperator = (LogicalJoinOperator) input.getOp();
        ColumnRefSet leftOutputColumns = input.inputAt(0).getOutputColumns();
        ColumnRefSet rightOutputColumns = input.inputAt(1).getOutputColumns();

        ScalarOperator skewColumn = oldJoinOperator.getSkewColumn();
        ScalarOperator rightSkewColumn = null;

        List<BinaryPredicateOperator> equalConjs = JoinHelper.
                getEqualsPredicate(leftOutputColumns, rightOutputColumns,
                        Utils.extractConjuncts(oldJoinOperator.getOnPredicate()));
        for (BinaryPredicateOperator equalConj : equalConjs) {
            ScalarOperator child0 = equalConj.getChild(0);
            ScalarOperator child1 = equalConj.getChild(1);
            // skew column may be left or right column of the equal predicate
            if (skewColumn.equals(child0)) {
                rightSkewColumn = child1;
                break;
            } else if (skewColumn.equals(child1)) {
                rightSkewColumn = child0;
                break;
            } else {
                // find the skew column in the left/right child project map
                if (input.inputAt(0).getOp() instanceof LogicalProjectOperator) {
                    Map<ColumnRefOperator, ScalarOperator> projectMap = ((LogicalProjectOperator) input.inputAt(0).
                            getOp()).getColumnRefMap();
                    ReplaceColumnRefRewriter rewriter = new ReplaceColumnRefRewriter(projectMap);
                    ScalarOperator rewriteChild0 = rewriter.rewrite(child0);
                    ScalarOperator rewriteChild1 = rewriter.rewrite(child1);
                    if (skewColumn.equals(rewriteChild0)) {
                        skewColumn = rewriteChild0;
                        rightSkewColumn = child1;
                        break;
                    } else if (rewriteChild0.isCast() && skewColumn.equals(rewriteChild0.getChild(0))) {
                        skewColumn = rewriteChild0.getChild(0);
                        rightSkewColumn = child1;
                    } else if (skewColumn.equals(rewriteChild1)) {
                        skewColumn = rewriteChild1;
                        rightSkewColumn = child0;
                        break;
                    } else if (rewriteChild1.isCast() && skewColumn.equals(rewriteChild1.getChild(0))) {
                        skewColumn = rewriteChild1.getChild(0);
                        rightSkewColumn = child0;
                    }
                }
            }
        }
        // when use hint, we should check the skew column, and throw exception if not found
        if (rightSkewColumn == null && oldJoinOperator.getJoinHint().equals(HintNode.HINT_JOIN_SKEW)) {
            throw new StarRocksConnectorException("Can't find skew column");
        } else if (rightSkewColumn == null) {
            return Lists.newArrayList();
        }

        // 1. add salt for left child
        OptExpression newLeftChild = addSaltForLeftChild(input.inputAt(0), skewColumn,
                oldJoinOperator.getSkewValues(), context);
        // 2. add salt for right child
        OptExpression newRightChild = addSaltForRightChild(oldJoinOperator, input.inputAt(1), rightSkewColumn, context);

        Map<ColumnRefOperator, ScalarOperator> leftProjectMap = ((LogicalProjectOperator) newLeftChild.getOp()).
                getColumnRefMap();
        ColumnRefOperator leftRandColumn = null;
        for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : leftProjectMap.entrySet()) {
            if (entry.getKey().getName().equals(RAND_COL)) {
                leftRandColumn = entry.getKey();
                break;
            }
        }
        Map<ColumnRefOperator, ScalarOperator> rightProjectMap = ((LogicalProjectOperator) newRightChild.getOp()).
                getColumnRefMap();
        ColumnRefOperator rightRandColumn = null;
        for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : rightProjectMap.entrySet()) {
            if (entry.getKey().getName().equals(RAND_COL)) {
                rightRandColumn = entry.getKey();
                break;
            }
        }

        ScalarOperator randColPredicate = BinaryPredicateOperator.eq(leftRandColumn, rightRandColumn);
        ScalarOperator oldJoinOnPredicate = oldJoinOperator.getOnPredicate();
        ScalarOperator andPredicateOperator = CompoundPredicateOperator.and(randColPredicate, oldJoinOnPredicate);

        ScalarOperatorRewriter scalarOperatorRewriter = new ScalarOperatorRewriter();
        andPredicateOperator = scalarOperatorRewriter.rewrite(andPredicateOperator,
                ScalarOperatorRewriter.DEFAULT_REWRITE_RULES);

        LogicalJoinOperator.Builder joinBuilder = LogicalJoinOperator.builder();
        LogicalJoinOperator newJoinOperator = joinBuilder.withOperator(oldJoinOperator)
                .setOnPredicate(andPredicateOperator)
                .setJoinHint(HintNode.HINT_JOIN_SKEW)
                .build();

        OptExpression joinExpression = OptExpression.create(newJoinOperator, newLeftChild, newRightChild);
        return Lists.newArrayList(joinExpression);
    }

    private OptExpression addSaltForLeftChild(OptExpression input, ScalarOperator skewColumn,
                                              List<ScalarOperator> skewValues,
                                              OptimizerContext context) {
        ColumnRefSet columnRefSet = input.getOutputColumns();
        // case when skew is null then round(rand() * 100) when skew in (skew values) then round(rand() * 100) else 0 end
        Function randFn = Expr.getBuiltinFunction(FunctionSet.RAND, new Type[] {},
                Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
        CallOperator randFnOperator = new CallOperator(FunctionSet.RAND, randFn.getReturnType(), Lists.newArrayList(),
                randFn);

        Function multiplyFn =
                Expr.getBuiltinFunction(FunctionSet.MULTIPLY, new Type[] {randFn.getReturnType(), Type.INT},
                        Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
        int randRange = context.getSessionVariable().getSkewJoinRandRange();
        CallOperator multiplyFnOperator = new CallOperator(FunctionSet.MULTIPLY, randFn.getReturnType(),
                Lists.newArrayList(randFnOperator, ConstantOperator.createDouble(randRange)), multiplyFn);

        Function roundFn = Expr.getBuiltinFunction(FunctionSet.ROUND, new Type[] {multiplyFn.getReturnType()},
                Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
        CallOperator roundFnOperator = new CallOperator(FunctionSet.ROUND, roundFn.getReturnType(),
                Lists.newArrayList(multiplyFnOperator), roundFn);

        IsNullPredicateOperator isNullPredicateOperator = new IsNullPredicateOperator(skewColumn);

        List<ScalarOperator> inPredicateArgs = Lists.newArrayList();
        inPredicateArgs.add(skewColumn);
        skewValues.remove(ConstantOperator.createNull(ScalarType.NULL));
        inPredicateArgs.addAll(skewValues);
        InPredicateOperator inPredicateOperator = new InPredicateOperator(false, inPredicateArgs);

        List<ScalarOperator> when = Lists.newArrayList();
        when.add(isNullPredicateOperator);
        when.add(roundFnOperator);
        when.add(inPredicateOperator);
        when.add(roundFnOperator);
        ScalarOperator caseWhenOperator = new CaseWhenOperator(roundFnOperator.getType(), null,
                ConstantOperator.createBigint(0), when);
        ScalarOperatorRewriter rewriter = new ScalarOperatorRewriter();
        caseWhenOperator = rewriter.rewrite(caseWhenOperator, ScalarOperatorRewriter.DEFAULT_TYPE_CAST_RULE);

        Map<ColumnRefOperator, ScalarOperator> projectMaps = columnRefSet.getStream()
                .map(columnRefId -> context.getColumnRefFactory().getColumnRef(columnRefId))
                .collect(Collectors.toMap(
                        java.util.function.Function.identity(), java.util.function.Function.identity()));
        projectMaps.put(context.getColumnRefFactory().create(RAND_COL, caseWhenOperator.getType(), true),
                caseWhenOperator);

        LogicalProjectOperator projectOperator = new LogicalProjectOperator(projectMaps);
        return OptExpression.create(projectOperator, input);
    }

    private OptExpression createSkewValueSaltTable(List<ScalarOperator> skewValues, OptimizerContext context) {
        ColumnRefFactory columnRefFactory = context.getColumnRefFactory();
        // create empty value node with project
        List<ColumnRefOperator> valuesOutputColumns = Lists.newArrayList();
        valuesOutputColumns.add(columnRefFactory.create("", Type.NULL, true));
        List<List<ScalarOperator>> values = new ArrayList<>();
        List<ScalarOperator> valuesRow = Lists.newArrayList();
        valuesRow.add(ConstantOperator.createNull(Type.NULL));
        values.add(valuesRow);

        LogicalValuesOperator valuesOperator = new LogicalValuesOperator(valuesOutputColumns, values);

        Map<ColumnRefOperator, ScalarOperator> valueProjectMap = Maps.newHashMap();
        // use skew value to generate array
        List<Type> skewTypes = skewValues.stream().map(ScalarOperator::getType).collect(Collectors.toList());
        ArrayType arrayType = new ArrayType(Type.getCommonType(
                skewTypes.toArray(new Type[0]), 0, skewTypes.size()));
        ArrayOperator arrayOperator = new ArrayOperator(arrayType, true, skewValues);

        valueProjectMap.put(
                columnRefFactory.create(arrayOperator, arrayOperator.getType(), true), arrayOperator);

        OptExpression skewValuesOpt = OptExpression.create(new LogicalProjectOperator(valueProjectMap),
                OptExpression.create(valuesOperator));

        // create table function node, unnest the skew value array
        TableFunction unnestFn = (TableFunction) Expr.getBuiltinFunction("unnest", new Type[] {arrayType},
                Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
        List<ColumnRefOperator> unnestOutputColumns = Lists.newArrayList();
        ColumnRefOperator unnestColumnOperator = columnRefFactory.create("unnest",
                unnestFn.getTableFnReturnTypes().get(0), true);
        unnestOutputColumns.add(unnestColumnOperator);

        List<Pair<ColumnRefOperator, ScalarOperator>> unnestChildProjectMap = Lists.newArrayList();
        for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : valueProjectMap.entrySet()) {
            unnestChildProjectMap.add(Pair.create(entry.getKey(), entry.getValue()));
        }
        LogicalTableFunctionOperator unnestOperator = new LogicalTableFunctionOperator(unnestOutputColumns,
                unnestFn, unnestChildProjectMap);
        OptExpression unnestOpt = OptExpression.create(unnestOperator, skewValuesOpt);

        Map<ColumnRefOperator, ScalarOperator> unnestProjectMap = unnestOperator.getOutputColRefs().stream().
                collect(Collectors.toMap(java.util.function.Function.identity(),
                        java.util.function.Function.identity()));
        int skewRandRange = context.getSessionVariable().getSkewJoinRandRange();

        Map<ColumnRefOperator, ScalarOperator> generateSeriesChildProjectMap = Maps.newHashMap();
        generateSeriesChildProjectMap.put(columnRefFactory.create("0", Type.BIGINT, false),
                ConstantOperator.createBigint(0));
        generateSeriesChildProjectMap.put(columnRefFactory.create(String.valueOf(skewRandRange), Type.BIGINT, false),
                ConstantOperator.createBigint(skewRandRange));
        unnestProjectMap.putAll(generateSeriesChildProjectMap);
        OptExpression unnestProjectOpt = OptExpression.create(new LogicalProjectOperator(unnestProjectMap),
                unnestOpt);

        // create table function, generate series using unnest output
        TableFunction generateSeriesFn = (TableFunction) Expr.getBuiltinFunction("generate_series",
                new Type[] {ScalarType.BIGINT, ScalarType.BIGINT}, Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
        List<ColumnRefOperator> generateSeriesOutputColumns = Lists.newArrayList();
        generateSeriesOutputColumns.add(columnRefFactory.create("generate_serials", ScalarType.BIGINT, true));
        List<Pair<ColumnRefOperator, ScalarOperator>> generateSeriesChildProjectPairs = Lists.newArrayList();
        for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : generateSeriesChildProjectMap.entrySet()) {
            generateSeriesChildProjectPairs.add(Pair.create(entry.getKey(), entry.getValue()));
        }
        List<ColumnRefOperator> generateSeriesOuterColRefs = Lists.newArrayList();
        generateSeriesOuterColRefs.add(unnestColumnOperator);

        LogicalTableFunctionOperator generateSeriesOperator = new LogicalTableFunctionOperator(
                generateSeriesOutputColumns, generateSeriesFn, generateSeriesChildProjectPairs,
                generateSeriesOuterColRefs);
        OptExpression generateSeriesOpt = OptExpression.create(generateSeriesOperator, unnestProjectOpt);

        Map<ColumnRefOperator, ScalarOperator> generateSeriesProjectMap = generateSeriesOperator.getOutputColRefs().
                stream().collect(Collectors.toMap(
                        java.util.function.Function.identity(), java.util.function.Function.identity()));
        return OptExpression.create(new LogicalProjectOperator(generateSeriesProjectMap),
                generateSeriesOpt);
    }

    private OptExpression addSaltForRightChild(LogicalJoinOperator oldJoinOperator, OptExpression input,
                                               ScalarOperator rightSkewColumn, OptimizerContext context) {
        List<ScalarOperator> skewValues = oldJoinOperator.getSkewValues();
        OptExpression skewValueSaltOpt = createSkewValueSaltTable(skewValues, context);
        Map<ColumnRefOperator, ScalarOperator> skewValueSaltProjects =
                ((LogicalProjectOperator) skewValueSaltOpt.getOp()).getColumnRefMap();
        ColumnRefOperator unnestColumn = null;
        ColumnRefOperator generateSeriesColumn = null;
        for (Map.Entry<ColumnRefOperator, ScalarOperator> entry : skewValueSaltProjects.entrySet()) {
            if (entry.getKey().getName().equals("unnest")) {
                unnestColumn = entry.getKey();
            } else if (entry.getKey().getName().equals("generate_serials")) {
                generateSeriesColumn = entry.getKey();
            }
        }

        // right table join with skew value salt table
        ScalarOperator onPredicate = BinaryPredicateOperator.eq(rightSkewColumn, unnestColumn);
        ScalarOperatorRewriter rewriter = new ScalarOperatorRewriter();
        onPredicate = rewriter.rewrite(onPredicate, ScalarOperatorRewriter.DEFAULT_REWRITE_RULES);

        LogicalJoinOperator.Builder joinBuilder = new LogicalJoinOperator.Builder();
        joinBuilder.setJoinType(JoinOperator.LEFT_OUTER_JOIN)
                .setOnPredicate(onPredicate)
                .setJoinHint(HintNode.HINT_JOIN_BROADCAST);
        LogicalJoinOperator joinOperator = joinBuilder.build();
        OptExpression joinOptExpression = OptExpression.create(joinOperator, input, skewValueSaltOpt);
        Map<ColumnRefOperator, ScalarOperator> joinProjectMap = input.getOutputColumns().getStream().
                map(columnRefId -> context.getColumnRefFactory().getColumnRef(columnRefId))
                .collect(Collectors.toMap(
                        java.util.function.Function.identity(), java.util.function.Function.identity()));

        Function ifFn = Expr.getBuiltinFunction(FunctionSet.IF,
                new Type[] {Type.BOOLEAN, generateSeriesColumn.getType(), Type.BIGINT},
                Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);

        List<ScalarOperator> args = Lists.newArrayList();
        args.add(new IsNullPredicateOperator(true, generateSeriesColumn));
        args.add(generateSeriesColumn);
        args.add(ConstantOperator.createBigint(0));

        joinProjectMap.put(context.getColumnRefFactory().create(RAND_COL, generateSeriesColumn.getType(),
                true), new CallOperator(FunctionSet.IF, generateSeriesColumn.getType(), args, ifFn));

        return OptExpression.create(new LogicalProjectOperator(joinProjectMap), joinOptExpression);
    }
}
