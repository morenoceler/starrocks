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

package com.starrocks.sql.ast;

import com.starrocks.sql.parser.NodePosition;
import com.starrocks.statistic.StatsConstants;

import java.util.List;

public class AnalyzeMultiColumnDesc extends AnalyzeTypeDesc {
    // we will support more statistics type on multi column like ndv/dependencies/mcv...
    private final List<StatsConstants.StatisticsType> statsTypes;

    public AnalyzeMultiColumnDesc(List<StatsConstants.StatisticsType> statsTypes) {
        super(NodePosition.ZERO);
        this.statsTypes = statsTypes;
    }

    public List<StatsConstants.StatisticsType> getStatsTypes() {
        return statsTypes;
    }
}
