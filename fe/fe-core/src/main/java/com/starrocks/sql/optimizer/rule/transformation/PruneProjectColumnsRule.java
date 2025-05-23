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
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.Type;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PruneProjectColumnsRule extends TransformationRule {

    public PruneProjectColumnsRule() {
        super(RuleType.TF_PRUNE_PROJECT_COLUMNS, Pattern.create(OperatorType.LOGICAL_PROJECT).
                addChildren(Pattern.create(OperatorType.PATTERN_LEAF, OperatorType.PATTERN_MULTI_LEAF)));
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalProjectOperator projectOperator = (LogicalProjectOperator) input.getOp();

        ColumnRefSet requiredInputColumns = new ColumnRefSet();
        ColumnRefSet requiredOutputColumns = context.getTaskContext().getRequiredColumns();

        Map<ColumnRefOperator, ScalarOperator> newMap = Maps.newHashMap();
        projectOperator.getColumnRefMap().forEach(((columnRefOperator, operator) -> {
            if (requiredOutputColumns.contains(columnRefOperator)) {
                requiredInputColumns.union(operator.getUsedColumns());
                newMap.put(columnRefOperator, operator);
            }
            if (operator instanceof CallOperator) {
                CallOperator callOperator = operator.cast();
                if (FunctionSet.ASSERT_TRUE.equals(callOperator.getFnName())) {
                    requiredInputColumns.union(operator.getUsedColumns());
                    newMap.put(columnRefOperator, operator);
                }
            }
        }));

        if (newMap.isEmpty()) {
            ColumnRefOperator constCol = context.getColumnRefFactory()
                    .create("auto_fill_col", Type.TINYINT, false);
            newMap.put(constCol, ConstantOperator.createTinyInt((byte) 1));
        } else if (newMap.equals(projectOperator.getColumnRefMap()) && context.getOptimizerOptions().isShortCircuit()) {
            // Change the requiredOutputColumns in context
            requiredOutputColumns.union(requiredInputColumns);
            // make sure this rule only executed once
            return Collections.emptyList();
        }

        // Change the requiredOutputColumns in context
        requiredOutputColumns.union(requiredInputColumns);

        return Lists.newArrayList(OptExpression.create(
                LogicalProjectOperator.builder().withOperator(projectOperator).setColumnRefMap(newMap).build(),
                input.getInputs()));
    }
}
