/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.cairo.sql.DataFrameCursor;
import io.questdb.griffin.SqlExecutionContext;

public class FullBwdDataFrameCursorFactory extends AbstractDataFrameCursorFactory {
    private final FullBwdDataFrameCursor cursor = new FullBwdDataFrameCursor();

    public FullBwdDataFrameCursorFactory(CairoEngine engine, String tableName, int tableId, long tableVersion) {
        super(engine, tableName, tableId, tableVersion);
    }

    @Override
    public DataFrameCursor getCursor(SqlExecutionContext executionContext, int order) {
        if (order == ORDER_DESC || order == ORDER_ANY) {
            return cursor.of(getReader(executionContext.getCairoSecurityContext()));
        }

        throw new UnsupportedOperationException();
    }

    @Override
    public int getOrder() {
        return ORDER_DESC;
    }
}