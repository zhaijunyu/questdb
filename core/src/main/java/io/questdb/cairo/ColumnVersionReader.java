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

import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryCMR;
import io.questdb.cairo.vm.api.MemoryR;
import io.questdb.cairo.vm.api.MemoryW;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.str.LPSZ;

import java.io.Closeable;

public class ColumnVersionReader implements Closeable, Mutable {
    public static final int OFFSET_VERSION_64 = 0;
    public static final int OFFSET_OFFSET_A_64 = OFFSET_VERSION_64 + 8;
    public static final int OFFSET_SIZE_A_64 = OFFSET_OFFSET_A_64 + 8;
    public static final int OFFSET_OFFSET_B_64 = OFFSET_SIZE_A_64 + 8;
    public static final int OFFSET_SIZE_B_64 = OFFSET_OFFSET_B_64 + 8;
    public static final int HEADER_SIZE = OFFSET_SIZE_B_64 + 8;
    public static final int BLOCK_SIZE = 4;
    public static final int BLOCK_SIZE_BYTES = BLOCK_SIZE * Long.BYTES;
    public static final int BLOCK_SIZE_MSB = Numbers.msb(BLOCK_SIZE);
    public static final long COL_TOP_DEFAULT_PARTITION = Long.MIN_VALUE;

    private final static Log LOG = LogFactory.getLog(ColumnVersionReader.class);
    private final LongList cachedList = new LongList();
    private MemoryCMR mem;
    private boolean ownMem;
    private long version;

    @Override
    public void clear() {
        if (ownMem) {
            mem.close();
        }
    }

    @Override
    public void close() {
        clear();
    }

    public void dumpTo(MemoryW mem) {
        mem.putLong(OFFSET_VERSION_64, version);
        boolean areaA = (version & 1L) == 0L;
        final long offset = HEADER_SIZE;
        mem.putLong(areaA ? OFFSET_OFFSET_A_64 : OFFSET_OFFSET_B_64, offset);
        final long size = (long) (cachedList.size() / BLOCK_SIZE) * BLOCK_SIZE_BYTES;
        mem.putLong(areaA ? OFFSET_SIZE_A_64 : OFFSET_SIZE_B_64, size);

        int i = 0;
        long p = offset;
        long lim = offset + size;

        while (p < lim) {
            mem.putLong(p, cachedList.getQuick(i));
            mem.putLong(p + Long.BYTES, cachedList.getQuick(i + 1));
            mem.putLong(p + 2 * Long.BYTES, cachedList.getQuick(i + 2));
            mem.putLong(p + 3 * Long.BYTES, cachedList.getQuick(i + 3));
            i += BLOCK_SIZE;
            p += BLOCK_SIZE_BYTES;
        }
    }

    public LongList getCachedList() {
        return cachedList;
    }

    public long getColumnNameTxn(long partitionTimestamp, int columnIndex) {
        int versionRecordIndex = getRecordIndex(partitionTimestamp, columnIndex);
        return versionRecordIndex > -1 ? cachedList.getQuick(versionRecordIndex + 2) : getDefaultColumnNameTxn(columnIndex);
    }

    public long getColumnNameTxnByIndex(int versionRecordIndex) {
        return versionRecordIndex > -1 ? cachedList.getQuick(versionRecordIndex + 2) : -1L;
    }

    public long getColumnTop(long partitionTimestamp, int columnIndex) {
        int index = getRecordIndex(partitionTimestamp, columnIndex);
        return getColumnTopByIndex(index);
    }

    public long getColumnTopByIndex(int versionRecordIndex) {
        return versionRecordIndex > -1 ? cachedList.getQuick(versionRecordIndex + 3) : 0L;
    }

    public long getColumnTopPartitionTimestamp(int columnIndex) {
        int index = getRecordIndex(COL_TOP_DEFAULT_PARTITION, columnIndex);
        return index > -1 ? getColumnTopByIndex(index) : Long.MIN_VALUE;
    }

    public long getDefaultColumnNameTxn(int columnIndex) {
        int index = getRecordIndex(COL_TOP_DEFAULT_PARTITION, columnIndex);
        return index > -1 ? getColumnNameTxnByIndex(index) : -1L;
    }

    public int getRecordIndex(long partitionTimestamp, int columnIndex) {
        int index = cachedList.binarySearchBlock(BLOCK_SIZE_MSB, partitionTimestamp, BinarySearch.SCAN_UP);
        if (index > -1) {
            final int sz = cachedList.size();
            for (; index < sz && cachedList.getQuick(index) == partitionTimestamp; index += BLOCK_SIZE) {
                final long thisIndex = cachedList.getQuick(index + 1);

                if (thisIndex == columnIndex) {
                    return index;
                }

                if (thisIndex > columnIndex) {
                    break;
                }
            }
        }
        return -1;
    }

    public long getVersion() {
        return version;
    }

    public ColumnVersionReader ofRO(FilesFacade ff, LPSZ fileName) {
        version = -1;
        if (this.mem == null || !ownMem) {
            this.mem = Vm.getCMRInstance();
        }
        this.mem.of(ff, fileName, 0, HEADER_SIZE, MemoryTag.MMAP_TABLE_READER);
        ownMem = true;
        return this;
    }

    public void readSafe(MicrosecondClock microsecondClock, long spinLockTimeoutUs) {
        final long tick = microsecondClock.getTicks();
        while (true) {
            long version = unsafeGetVersion();
            if (version == this.version) {
                return;
            }
            Unsafe.getUnsafe().loadFence();

            final long offset;
            final long size;

            final boolean areaA = (version & 1L) == 0;
            if (areaA) {
                offset = mem.getLong(OFFSET_OFFSET_A_64);
                size = mem.getLong(OFFSET_SIZE_A_64);
            } else {
                offset = mem.getLong(OFFSET_OFFSET_B_64);
                size = mem.getLong(OFFSET_SIZE_B_64);
            }

            Unsafe.getUnsafe().loadFence();
            if (version == unsafeGetVersion()) {
                mem.resize(offset + size);
                readUnsafe(offset, size, cachedList, mem);

                Unsafe.getUnsafe().loadFence();
                if (version == unsafeGetVersion()) {
                    this.version = version;
                    LOG.debug().$("read clean version ").$(version).$(", offset ").$(offset).$(", size ").$(size).$();
                    return;
                }
            }

            if (microsecondClock.getTicks() - tick > spinLockTimeoutUs) {
                LOG.error().$("Column Version read timeout [timeout=").$(spinLockTimeoutUs).utf8("μs]").$();
                throw CairoException.instance(0).put("Column Version read timeout");
            }
            Os.pause();
            LOG.debug().$("read dirty version ").$(version).$(", retrying").$();
        }
    }

    private static void readUnsafe(long offset, long areaSize, LongList cachedList, MemoryR mem) {
        long lim = offset + areaSize;
        mem.extend(lim);
        int i = 0;
        long p = offset;

        assert areaSize % BLOCK_SIZE_BYTES == 0;

        cachedList.setPos((int) ((areaSize / BLOCK_SIZE_BYTES) * BLOCK_SIZE));

        while (p < lim) {
            cachedList.setQuick(i, mem.getLong(p));
            cachedList.setQuick(i + 1, mem.getLong(p + Long.BYTES));
            cachedList.setQuick(i + 2, mem.getLong(p + 2 * Long.BYTES));
            cachedList.setQuick(i + 3, mem.getLong(p + 3 * Long.BYTES));
            i += BLOCK_SIZE;
            p += BLOCK_SIZE_BYTES;
        }
    }

    void ofRO(MemoryCMR mem) {
        if (this.mem != null && ownMem) {
            this.mem.close();
        }
        this.mem = mem;
        ownMem = false;
        version = -1;
    }

    long readUnsafe() {
        long version = mem.getLong(OFFSET_VERSION_64);

        boolean areaA = (version & 1L) == 0L;
        long offset = areaA ? mem.getLong(OFFSET_OFFSET_A_64) : mem.getLong(OFFSET_OFFSET_B_64);
        long size = areaA ? mem.getLong(OFFSET_SIZE_A_64) : mem.getLong(OFFSET_SIZE_B_64);
        mem.resize(offset + size);
        readUnsafe(offset, size, cachedList, mem);
        return version;
    }

    private long unsafeGetVersion() {
        return mem.getLong(OFFSET_VERSION_64);
    }
}
