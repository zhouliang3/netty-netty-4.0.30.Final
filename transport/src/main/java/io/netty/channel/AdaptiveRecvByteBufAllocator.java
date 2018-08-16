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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator implements RecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    static final int DEFAULT_INITIAL = 1024;
    static final int DEFAULT_MAXIMUM = 65536;
    /**
     * INDEX_INCREMENT：上次预估缓存偏小时，下次Index的递增值。默认为4；
     * INDEX_DECREMENT：上次预估缓存偏大时，下次Index的递减值。默认为1
     */
    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    /**
     * inote 为固定的静态数组，按照从小到大的顺序预先存储可以分配的缓存大小。最小的为16，然后每次累加16，直到496。
     * 然后从512开始，每次向左位移1（即放大两倍），直到int发生溢出。本机的最大值为1073741824，所以数组的size为52。
     */
    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private static final class HandleImpl implements Handle {

        private final int minIndex;
        private final int maxIndex;
        private int index;//inote Index记录nextReceiveBufferSize在数组SIZE_TABLE中的索引值
        private int nextReceiveBufferSize;//inote nextReceiveBufferSize记录下次分配缓存时应该分配的大小，即index下标在数组SIZE_TABLE中的值。
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            /**
             * inote 1. 如果是第一次分配，则该值由getSizeTableIndex方法根据initial的值（默认为1024）计算而来。
             * getSizeTableIndex采用二分查找算法计算SIZE_TABLE数组中值最接近initial的下标。
             */
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        /**
         * inote 根据上次预估的字节大小nextReceiveBufferSize分配缓存。
         * @param alloc
         * @return
         */
        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(nextReceiveBufferSize);
        }

        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        /**
         * inote 2. 如果非第一次分配，则由record 方法根据上一次实际读取到的字节数actualReadBytes自适应的调整nextReceiveBufferSize的大小
         * @param actualReadBytes the actual number of read bytes in the previous read operation
         */
        @Override
        public void record(int actualReadBytes) {
            /**
             * inote  如果actualReadBytes连续两次都小于SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]
             *  （为啥在INDEX_DECREMENT的基础上再减1？），即连续两次预估的缓存大小都偏大导致浪费了
             *  ，则更新index为Math.max(index - INDEX_DECREMENT, minIndex)
             */
            if (actualReadBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]) {
                if (decreaseNow) {
                    index = Math.max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                /**
                 * inote 如果actualReadBytes大于nextReceiveBufferSize，即上次预估的缓存大小偏小，则更新index为Math.min(index + INDEX_INCREMENT, maxIndex)
                 */
                index = Math.min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }
    }
    /**
     * MinIndex和maxIndex为最小缓存（64）和最大缓存（65536）在SIZE_TABLE中对应的下标。分别为3和38
     */
    private final int minIndex;
    private final int maxIndex;
    private final int initial;//intoe 第一次分配缓存时，由于没有上一次的实际接收到的字节数做参考，因此需要给出初始值，由Initial指定，默认值为1024

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    private AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        if (minimum <= 0) {
            throw new IllegalArgumentException("minimum: " + minimum);
        }
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }
}
