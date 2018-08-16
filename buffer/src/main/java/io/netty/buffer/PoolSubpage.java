/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

/**
 * inote 负责内存分配的类PoolChunk，它最小的分配单位为page, 而默认的page size为8K。在实际的应用中，会存在很多小块内存的分配，
 * 如果小块内存也占用一个page明显很浪费，针对这种情况，可以将8K的page拆成更小的块，这已经超出chunk的管理范围了，这个时候就出现了PoolSubpage,
 * 其实PoolSubpage做的事情和PoolChunk做的事情类似，只是PoolSubpage管理的是更小的一段内存。
 * PoolSubpage将chunk中的一个page再次划分，分成相同大小的N份,这里暂且叫Element，通过对每一个Element的标记与清理标记来进行内存的分配与释放。
 *
 * @param <T>
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;                 // 当前page在chunk中的id
    private final int runOffset;                    // 当前page在chunk.memory的偏移量
    private final int pageSize;                     // page大小
    private final long[] bitmap;                    // 这个bitmap的实现和BitSet相同，通过对每一个二进制位的标记来修改一段内存的占用状态

    PoolSubpage<T> prev;                            // 前一个节点，这里要配合PoolArena看，后面再说
    PoolSubpage<T> next;

    boolean doNotDestroy;                           // 表示该page在使用中，不能被清除
    int elemSize;                                   // 该page切分后每一段的大小,默认256
    private int maxNumElems;                        // 该page包含的段数量
    private int bitmapLength;                       // bitmap需要用到的长度
    private int nextAvail;                          // 下一个可用的位置
    private int numAvail;                           // 可用的段数量

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * Special constructor that creates a linked list head
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        // inote 这里为什么是16,64两个数字呢，elemSize是经过normCapacity处理的数字，最小值为16；
        // inote 所以一个page最多可能被分成pageSize/16段内存，而一个long可以表示64个内存段的状态；
        // inote 因此最多需要pageSize/16/64个元素就能保证所有段的状态都可以管理
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(elemSize);
    }

    /**
     * inote 这个方法有两种情况下会调用
     * 1、类初始化时
     * 2、整个subpage被回收后重新分配
     *
     * @param elemSize
     */
    void init(int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;   //inote 一共可用的Element（段）的数量
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;               //inote bitmapLength=maxNumElems/64 因bitmap每一个位都是一个64位的long型，可以表示64个段的状态。默认值的情况下，pageSize/ElementSize>>>6  即8K/256=32 >>>6 =0
            if ((maxNumElems & 63) != 0) {                  //inote 说明存在maxNumElems对64求余大于0，需要将长度加1.
                bitmapLength++;
            }

            for (int i = 0; i < bitmapLength; i++) {        //inote 用来表示段状态的值全部需要被清零
                bitmap[i] = 0;
            }
        }

        PoolSubpage<T> head = chunk.arena.findSubpagePoolHead(elemSize);
        synchronized (head) {
            //inote chunk在分配page时，如果是8K以下的段则交给subpage管理，然而chunk并没有将subpage暴露给外部，subpage只好自谋生路，
            //inote 在初始化或重新分配时将自己加入到chunk.arena的pool中，通过arena进行后续的管理（包括复用subpage上的其他element，arena目前还没讲到，后面会再提到）
            addToPool(head);
        }
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * inote 分配一个可用的element并标记
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        /**
         * Synchronize on the head of the SubpagePool stored in the {@link PoolArena. This is needed as we synchronize
         * on it when calling {@link PoolArena#allocate(PoolThreadCache, int, int)} und try to allocate out of the
         * {@link PoolSubpage} pool for a given size.
         */
        PoolSubpage<T> head = chunk.arena.findSubpagePoolHead(elemSize);
        synchronized (head) {
            if (numAvail == 0 || !doNotDestroy) {//inote 没有可用的内存或者已经被销毁
                return -1;
            }
            //inote 找到当前page中分配的段的index
            final int bitmapIdx = getNextAvail();
            //inote 算出对应index的标志位在数组中的位置q
            int q = bitmapIdx >>> 6;
            //inote 将>=64的那一部分二进制抹掉得到一个小于64的数
            int r = bitmapIdx & 63;
            assert (bitmap[q] >>> r & 1) == 0;
            //inote 对应位置值设置为1表示当前element已经被分配， 这几句看起来很郁闷，转换成我们常见的BitSet，其实就是bitSet.set(q, true)
            bitmap[q] |= 1L << r;
            //inote 如果当前page没有可用的内存则从arena的pool中移除
            if (--numAvail == 0) {
                removeFromPool();
            }

            return toHandle(bitmapIdx);
        }
    }

    /**
     * @return {@code true} if this subpage is in use.
     * {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     * inote 释放指定element
     */
    boolean free(int bitmapIdx) {

        if (elemSize == 0) {
            return true;
        }

        /**
         * Synchronize on the head of the SubpagePool stored in the {@link PoolArena. This is needed as we synchronize
         * on it when calling {@link PoolArena#allocate(PoolThreadCache, int, int)} und try to allocate out of the
         * {@link PoolSubpage} pool for a given size.
         */
        PoolSubpage<T> head = chunk.arena.findSubpagePoolHead(elemSize);

        synchronized (head) {
            int q = bitmapIdx >>> 6;
            int r = bitmapIdx & 63;
            assert (bitmap[q] >>> r & 1) != 0;
            bitmap[q] ^= 1L << r;
            //inote 将这个index设置为可用, 下次分配时会直接分配这个位置的内存
            setNextAvail(bitmapIdx);
            //inote numAvail=0说明之前已经从arena的pool中移除了，现在变回可用，则再次交给arena管理
            if (numAvail++ == 0) {
                addToPool(head);
                return true;
            }

            if (numAvail != maxNumElems) {
                return true;
            } else {
                // Subpage not in use (numAvail == maxNumElems)
                if (prev == next) {
                    // Do not remove if this subpage is the only one left in the pool.
                    return true;
                }

                // Remove this subpage from the pool if there are other subpages left in the pool.
                doNotDestroy = false;
                removeFromPool();
                return false;
            }
        }
    }

    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        // nextAvail>=0时，表示明确的知道这个element未被分配，此时直接返回就可以了
        // >=0 有两种情况：1、刚初始化；2、有element被释放且还未被分配
        // 每次分配完成nextAvail就被置为-1，因为这个时候除非计算一次，否则无法知道下一个可用位置在哪
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j++) {
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return String.valueOf('(') + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        return maxNumElems;
    }

    @Override
    public int numAvailable() {
        return numAvail;
    }

    @Override
    public int elementSize() {
        return elemSize;
    }

    @Override
    public int pageSize() {
        return pageSize;
    }
}
