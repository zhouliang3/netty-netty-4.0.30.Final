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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * inote 多个PoolChunkList也会形成一个list，方便内存的管理。最终由PoolArena对这一系列类进行管理，PoolArena本身是一个抽象类，其子类为HeapArena和DirectArena，
 * 对应堆内存(heap buffer)和堆外内存(direct buffer)，除了操作的内存(byte[]和ByteBuffer)不同外两个类完全一致
 * <p/>
 * <p>
 * inote PoolSubpage在初始化以后会交由arena来管理，那么他究竟是怎么管理的呢。之前我们提到所有内存分配的size都会经过normalizeCapacity进行处理，
 * 而这个方法的处理方式是，当需求的size>=512时，size成倍增长，及512->1024->2048->4096->8192->...，而需求size<512则是从16开始，每次加16字节，
 * 这样从[512,8192)有四个不同值，而从[16,512)有32个不同值。这样tinySubpagePools的大小就是32，而tinySubpagePool的大小则是4，并且从index=0 -> max,
 * 按照从小到大的顺序缓存subpage的数据。如tinySubpagePool[0]上是用来分配大小为16的内存，tinySubpagePool[1]分配大小为32的内存，
 * smallSubpagePools[0]分配大小为512的内存。这样如果需要分配小内存时，只需要计算出index,到对应的index的pool查找缓存的数据即可。
 * 需要注意的是subpage从首次分配到释放的过程中，只会负责一个固定size的内存分配，如subpage初始化的时候是分配size=512的内存，
 * 则该subpage剩下的所有内存也是用来分配size=512的内存，直到其被完全释放后从arena中去掉。如果下次该subpage又重新被分配，
 * 则按照此次的大小size0分配到固定的位置，并且该subpage剩下的所有内存用来分配size=size0的内存
 * </p>
 * <p>
 * inote netty将内存分为tiny(0，512)、small[512，8K)、normal【8K,16M]、huge[16M，)这四种类型，使用tcp进行通信时，初始的内存大小默认为1k,
 * 并会在64-64k之间动态调整（以上为默认参数，见AdaptiveRecvByteBufAllocator），在实际使用中小内存的分配会更多，因此这里将常用的小内存(subpage)前置。
 * </p>
 *
 * @param <T>
 */
abstract class PoolArena<T> implements PoolArenaMetric {

    enum SizeClass {
        Tiny,
        Small,
        Normal
    }

    static final int numTinySubpagePools = 512 >>> 4;

    final PooledByteBufAllocator parent;
    //inote 下面这几个参数用来控制PoolChunk的总内存大小、page大小等
    private final int maxOrder;
    final int pageSize;
    final int pageShifts;
    final int chunkSize;
    final int subpageOverflowMask;
    final int numSmallSubpagePools;
    private final PoolSubpage<T>[] tinySubpagePools;
    private final PoolSubpage<T>[] smallSubpagePools;
    //inote 下面几个chunklist又组成了一个link list的链表
    /**
     * inote 这几个PoolChunkList为什么这么命名，其实是按照内存的使用率来取名的，如qInit代表一个chunk最开始分配后会进入它，随着其使用率增大会逐渐从q000到q100，
     * 而随着内存释放，使用率减小，它又会慢慢的从q100到q00,最终这个chunk上的所有内存释放后，整个chunk被回收。
     */
    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsTiny;
    private long allocationsSmall;
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        subpageOverflowMask = ~(pageSize - 1);
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        q100 = new PoolChunkList<T>(null, 100, Integer.MAX_VALUE);
        q075 = new PoolChunkList<T>(q100, 75, 100);
        q050 = new PoolChunkList<T>(q075, 50, 100);
        q025 = new PoolChunkList<T>(q050, 25, 75);
        q000 = new PoolChunkList<T>(q025, 1, 50);
        qInit = new PoolChunkList<T>(q000, Integer.MIN_VALUE, 25);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        //inote q000没有前置节点，则当一个chunk进入q000后，如果其内存被完全释放，则不再保留在内存中，其分配的内存被完全回收
        q000.prevList(null);
        //inote qInit前置节点为自己，且minUsage=Integer.MIN_VALUE，意味着一个初分配的chunk，在最开始的内存分配过程中(内存使用率<25%)
        //inote 即使完全回收也不会被释放，这样始终保留在内存中，后面的分配就无需新建chunk,减小了分配的时间
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);//inote 这个方法就涉及了ByteBuf的Recycle的相关知识
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    /**
     * <p>
     *     inote 除了大内存的分配，都是先尝试从cache中分配，如果无法完成分配则再走其他流程。这个cache有何作用？ 它是利用ThreadLocal的特性，
     *     去除锁竞争，提高内存分配的效率（后面会单独讲）。 对于小内存（小于8K)的分配，则是先尝试从对应大小的PoolSubpage中分配，
     *     如果分配不到再通过allocateNormal分配，如一个size=16的请求，会尝试从tinySubpagePools[0]中尝试，
     *     而size=1024则会从smallSubpagePools[1]中尝试，以此类推。这里需要注意的是，
     *     在tinySubpagePools和smallSubpagePools每个位置上只有一个PoolSubpage，但PoolSubpage本身有next和prev两个属性，
     *     所以其实这里代表的是一个link list而不是单个PoolSubpage。
     *     但是在从cache的PoolSubpage中分配内存时，只做了一次尝试，即尝试从head.next中取，那这个link list还有什么用呢？
     *     其实前面在PoolSubpage的分析中讲到，一个subpage如果所有内存都已经分配，则会从这个link list中移除，
     *     并且在有部分内存释放时再加入，在内存完全释放时彻底从link list中移除。因此可以保证如果link list中有数据节点，
     *     则第一个节点以及后面的所有节点都有可分配的内存，因此不需要分配多次。需要注意PoolSubpage在将自身从link list中彻底移除时有一个策略
     *     ，即如果link list中没有其他节点了则不进行移除，这样arena中一旦分配了某个大小的小内存后始终存在可以分配的节点。
     * </p>
     * <p>
     *     inote 超大内存由于本身复用性并不高，因此没有做其他任何策略。不用pool而是直接分配一个大内存，用完后直接回收。
     * </p>
     * @param cache
     * @param buf
     * @param reqCapacity
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        final int normCapacity = normalizeCapacity(reqCapacity);
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            if (tiny) { // < 512
                //inote 小内存从tinySubpagePools中分配
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                //inote 略大的小内存从smallSubpagePools中分配
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolSubpage#allocate()} and
             * {@link PoolSubpage#free(int)} may modify the doubly linked list as well.
             */
            //inote subpage的分配方法不是线程安全，所以需要在实际分配时加锁
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    long handle = s.allocate();
                    assert handle >= 0;
                    s.chunk.initBufWithSubpage(buf, handle, reqCapacity);

                    if (tiny) {
                        ++allocationsTiny;
                    } else {
                        ++allocationsSmall;
                    }
                    return;
                }
            }
            allocateNormal(buf, reqCapacity, normCapacity);
            return;
        }
        if (normCapacity <= chunkSize) {
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            allocateNormal(buf, reqCapacity, normCapacity);
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }
    //inote 分配内存时先从内存占用率相对较低的chunklist中开始查找，这样查找的平均用时就会更短

    /**
     * <p>
     * inote 这里为什么不是从较低的q000开始呢，在分析PoolChunkList的时候，我们知道一个chunk随着内存的不停释放，
     * 它本身会不停的往其所在的chunk list的prev list移动，直到其完全释放后被回收。 如果这里是从q000开始尝试分配，
     * 虽然分配的速度可能更快了（因为分配成功的几率更大），但一个chunk在使用率为25%以内时有更大几率再分配，
     * 也就是一个chunk被回收的几率大大降低了。这样就带来了一个问题，我们的应用在实际运行过程中会存在一个访问高峰期，
     * 这个时候内存的占用量会是平时的几倍，因此会多分配几倍的chunk出来，而等高峰期过去以后，由于chunk被回收的几率降低，
     * 内存回收的进度就会很慢（因为没被完全释放，所以无法回收），内存就存在很大的浪费。
     * </p>
     * <p>
     * inote 为什么是从q050开始尝试分配呢，q050是内存占用50%~100%的chunk，猜测是希望能够提高整个应用的内存使用率，
     * 因为这样大部分情况下会使用q050的内存，这样在内存使用不是很多的情况下一些利用率低(<50%)的chunk慢慢就会淘汰出去，最终被回收。
     * </p>
     * <p>
     * inote 然而为什么不是从qinit中开始呢，这里的chunk利用率低，但又不会被回收，岂不是浪费？
     * q075,q100由于使用率高，分配成功的几率也会更小，因此放到最后（q100上的chunk使用率都是100%，为什么还要尝试从这里分配呢？？）。
     * 如果整个list中都无法分配，则新建一个chunk，并将其加入到qinit中。
     * </p>
     *
     * @param buf
     * @param reqCapacity
     * @param normCapacity
     */
    private synchronized void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        ++allocationsNormal;
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
                q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
                q075.allocate(buf, reqCapacity, normCapacity) || q100.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        long handle = c.allocate(normCapacity);
        assert handle > 0;
        c.initBuf(buf, handle, reqCapacity);
        qInit.add(c);
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        allocationsHuge.increment();
        buf.initUnpooled(newUnpooledChunk(reqCapacity), reqCapacity);
    }

    /**
     * <p>
     *     内存回收代码相对比较简单，主要分成3步：<br/>
         1、如果chunk不是pool的（如上面的huge方式分配的)，则直接销毁（回收）；<br/>
         2、如果分配线程和释放线程是同一个线程， 则先尝试往ThreadLocal的cache中放，此时由于用到了ThreadLocal，没有线程安全问题，所以不加锁；<br/>
         3、如果cache已满（或者其他原因导致无法添加，这里先不深入），则通过其所在的chunklist进行释放，这里的chunklist释放会涉及到对应内存块的释放，
            chunk在chunklist之间的移动和chunk的销毁，细节见PoolChunkList的分析。<br/>

     * </p>
     * @param chunk
     * @param handle
     * @param normCapacity
     * @param cache
     */
    void free(PoolChunk<T> chunk, long handle, int normCapacity, PoolThreadCache cache) {
        if (chunk.unpooled) {
            allocationsHuge.decrement();
            destroyChunk(chunk);
        } else {
            SizeClass sizeClass = sizeClass(normCapacity);
            if (cache != null && cache.add(this, chunk, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            freeChunk(chunk, handle, sizeClass);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass) {
        final boolean destroyChunk;
        synchronized (this) {
            switch (sizeClass) {
                case Normal:
                    ++deallocationsNormal;
                    break;
                case Small:
                    ++deallocationsSmall;
                    break;
                case Tiny:
                    ++deallocationsTiny;
                    break;
                default:
                    throw new Error();
            }
            destroyChunk = !chunk.parent.free(chunk, handle);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx++;
            }
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

    /**
     * @param reqCapacity
     * @return
     */
    int normalizeCapacity(int reqCapacity) {
        if (reqCapacity < 0) {
            throw new IllegalArgumentException("capacity: " + reqCapacity + " (expected: 0+)");
        }
        if (reqCapacity >= chunkSize) {
            return reqCapacity;
        }

        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled

            int normalizedCapacity = reqCapacity;
            normalizedCapacity--;
            normalizedCapacity |= normalizedCapacity >>> 1;
            normalizedCapacity |= normalizedCapacity >>> 2;
            normalizedCapacity |= normalizedCapacity >>> 4;
            normalizedCapacity |= normalizedCapacity >>> 8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }

            return normalizedCapacity;
        }

        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    /**
     * <p>
     *     inote 如果我申请的内存再使用的过程中发现不够了，需要扩张该怎么办，或者我不需要那么多内存，想要个更小的该怎么办，其实很简单，
     *     就是重新申请一个更大的内存，将之前的数据拷贝到新的内存，并释放掉之前申请的内存。
     * </p>
     * @param buf
     * @param newCapacity
     * @param freeOldMemory
     */
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        allocate(parent.threadCache(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        if (freeOldMemory) {
            free(oldChunk, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (int i = 1; i < pages.length; i++) {
            PoolSubpage<?> head = pages[i];
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (; ; ) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        return allocationsTiny + allocationsSmall + allocationsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny;
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall;
    }

    @Override
    public long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        return deallocationsTiny + deallocationsSmall + allocationsNormal + deallocationsHuge.value();
    }

    @Override
    public long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public long numActiveAllocations() {
        long val = numAllocations() - numDeallocations();
        return val >= 0 ? val : 0;
    }

    @Override
    public long numActiveTinyAllocations() {
        long val = numTinyAllocations() - numTinyDeallocations();
        return val >= 0 ? val : 0;
    }

    @Override
    public long numActiveSmallAllocations() {
        long val = numSmallAllocations() - numSmallDeallocations();
        return val >= 0 ? val : 0;
    }

    @Override
    public long numActiveNormalAllocations() {
        long val = numNormalAllocations() - numNormalDeallocations();
        return val >= 0 ? val : 0;
    }

    @Override
    public long numActiveHugeAllocations() {
        long val = numHugeAllocations() - numHugeDeallocations();
        return val >= 0 ? val : 0;
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);

    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);

    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);

    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);

    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
                .append("Chunk(s) at 0~25%:")
                .append(StringUtil.NEWLINE)
                .append(qInit)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 0~50%:")
                .append(StringUtil.NEWLINE)
                .append(q000)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 25~75%:")
                .append(StringUtil.NEWLINE)
                .append(q025)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 50~100%:")
                .append(StringUtil.NEWLINE)
                .append(q050)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 75~100%:")
                .append(StringUtil.NEWLINE)
                .append(q075)
                .append(StringUtil.NEWLINE)
                .append("Chunk(s) at 100%:")
                .append(StringUtil.NEWLINE)
                .append(q100)
                .append(StringUtil.NEWLINE)
                .append("tiny subpages:");
        for (int i = 1; i < tinySubpagePools.length; i++) {
            PoolSubpage<T> head = tinySubpagePools[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<T> s = head.next;
            for (; ; ) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        buf.append(StringUtil.NEWLINE)
                .append("small subpages:");
        for (int i = 1; i < smallSubpagePools.length; i++) {
            PoolSubpage<T> head = smallSubpagePools[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<T> s = head.next;
            for (; ; ) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, new byte[chunkSize], pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, new byte[capacity], capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        private static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<ByteBuffer>(
                    this, ByteBuffer.allocateDirect(chunkSize), pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            return new PoolChunk<ByteBuffer>(this, ByteBuffer.allocateDirect(capacity), capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            PlatformDependent.freeDirectBuffer(chunk.memory);
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
