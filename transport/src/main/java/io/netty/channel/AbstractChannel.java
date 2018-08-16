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

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.OneTimeTask;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ThreadLocalRandom;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    static final ClosedChannelException CLOSED_CHANNEL_EXCEPTION = new ClosedChannelException();
    static final NotYetConnectedException NOT_YET_CONNECTED_EXCEPTION = new NotYetConnectedException();

    static {
        CLOSED_CHANNEL_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
        NOT_YET_CONNECTED_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    private MessageSizeEstimator.Handle estimatorHandle;

    private final Channel parent;
    private final long hashCode = ThreadLocalRandom.current().nextLong();
    private final Unsafe unsafe;
    private final DefaultChannelPipeline pipeline;
    private final ChannelFuture succeededFuture = new SucceededChannelFuture(this, null);
    private final VoidChannelPromise voidPromise = new VoidChannelPromise(this, true);
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);
    private final CloseFuture closeFuture = new CloseFuture(this);

    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;
    private volatile EventLoop eventLoop;
    private volatile boolean registered;

    /**
     * Cache for the string representation of this channel
     */
    private boolean strValActive;
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent) {
        this.parent = parent;
        unsafe = newUnsafe();//inote NioSocketChannel中newUnsafe()调用AbstractNioByteChannel的实现，实例化一个NioByteUnsafe（注：在NioServerSocketChannel中实例化的是NioMessageUnsafe）
        pipeline = new DefaultChannelPipeline(this);//inote 将channel设置为非阻塞，然后为channel创建管道。
    }

    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * Reset the stored remoteAddress
     */
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);//inote 由于connect是一个Outbound事件，所以按照tail到head的顺序执行所有的outBound处理器。目前共有Head->EchoClientHandler->tail三个处理器，而只有Head是outbound处理器
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        pipeline.read();//inote Read是一个Outbound事件，因此findContextOutbound()会按照tail->head的顺序执行所有的Outbound处理器，目前有三个处理器：tail->ServerBootstrapAcceptor->head，但只有head是outbound处理器
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return new DefaultChannelPromise(this);
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return new DefaultChannelProgressivePromise(this);
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return succeededFuture;
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return new FailedChannelFuture(this, null, cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return (int) hashCode;
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        long ret = hashCode - o.hashCode();
        if (ret > 0) {
            return 1;
        }
        if (ret < 0) {
            return -1;
        }

        ret = System.identityHashCode(this) - System.identityHashCode(o);
        if (ret != 0) {
            return (int) ret;
        }

        // Jackpot! - different objects with same hashes
        throw new Error();
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode()} ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            SocketAddress srcAddr;
            SocketAddress dstAddr;
            if (parent == null) {
                srcAddr = localAddr;
                dstAddr = remoteAddr;
            } else {
                srcAddr = remoteAddr;
                dstAddr = localAddr;
            }
            strVal = String.format("[id: 0x%08x, %s %s %s]", (int) hashCode, srcAddr, active ? "=>" : ":>", dstAddr);
        } else if (localAddr != null) {
            strVal = String.format("[id: 0x%08x, %s]", (int) hashCode, localAddr);
        } else {
            strVal = String.format("[id: 0x%08x]", (int) hashCode);
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return voidPromise;
    }

    final MessageSizeEstimator.Handle estimatorHandle() {
        if (estimatorHandle == null) {
            estimatorHandle = config().getMessageSizeEstimator().newHandle();
        }
        return estimatorHandle;
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     */
    protected abstract class AbstractUnsafe implements Unsafe {

        private ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        private boolean inFlush0;
        /**
         * true if the channel has never been registered, false otherwise
         */
        private boolean neverRegistered = true;

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        @Override
        public final void register(EventLoop eventLoop, final ChannelPromise promise) {
            if (eventLoop == null) {
                throw new NullPointerException("eventLoop");
            }
            if (isRegistered()) {
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }
            if (!isCompatible(eventLoop)) {
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }

            AbstractChannel.this.eventLoop = eventLoop;//inote 设置eventLoop属性

            if (eventLoop.inEventLoop()) {
                register0(promise);
            } else {
                try {
                    eventLoop.execute(new OneTimeTask() {//inote 将4.0.30中thread的初始化方法挪到此处了
                        @Override
                        public void run() {
                            register0(promise);
                        }
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    closeFuture.setClosed();
                    safeSetFailure(promise, t);
                }
            }
        }

        private void register0(ChannelPromise promise) {
            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }
                boolean firstRegistration = neverRegistered;
                doRegister();//inote AbstractNioChannel.doRegister实现channel与selector的绑定,注册时将自身作为Attachment
                neverRegistered = false;
                registered = true;
                safeSetSuccess(promise);

                /**
                 * inote 设置为seccess之后，会回调之前main函数所在的线程中的doBind方法中为ChannelPromise添加的listner，
                 * 触发doBind0方法，即AbstractBootstrap的代码：
                 */
                /**
                 * inote 客户端doConnect时，ChannelRegistered是一个Inbound事件，因此会按照head->tail的顺序执行所有的inbound处理器，
                 * 目前有三个处理器：head-> ChannelInitializer ->tail，ChannelInitializer和tail都是inbound处理器，
                 * 所以看一下ChannelInitializer的invokeChannelRegistered方法
                 */
                //inote 客户端处理时，触发ChannelInitializer的initChannel，其功能就是将EchoClientHandler加入到管道中,并将自己（ChannelInitializer）从管道中删除，此时的处理器链表为：Head->EchoClientHandler->tail
                pipeline.fireChannelRegistered();//inote ChannelRegistered是一个Inbound事件，因此会按照head->tail的顺序执行所有的inbound处理器，目前有三个处理器：head-> ChannelInitializer ->tail->，ChannelInitializer和tail都是inbound处理器
                // Only fire a channelActive if the channel has never been registered. This prevents firing
                // multiple channel actives if the channel is deregistered and re-registered.
                //inote 客户端触发时， 由于此时还没有执行connect操作，所以isActive返回false，不会执行pipeline.fireChannelActive()，此时NioEventLoop中只剩下doConnect0任务了
                if (firstRegistration && isActive()) {
                    pipeline.fireChannelActive();//inote 由于对于监听套接字还没有执行bind操作，所以isActive返回false，不会执行pipeline.fireChannelActive()该行代码。执行完此代码后，register0任务就执行完了，boss线程中的任务队列中仅剩下bind任务。
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                    localAddress instanceof InetSocketAddress &&
                    !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                    !PlatformDependent.isWindows() && !PlatformDependent.isRoot()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                                "is not bound to a wildcard address; binding to a non-wildcard " +
                                "address (" + localAddress + ") anyway as requested.");
            }

            boolean wasActive = isActive();
            try {
                doBind(localAddress);//inote call NioServerSocketChannel.doBind() 完成了对IP和端口的绑定。注意：此处的backlog（最大完成连接队列数）的默认值为3072。
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }
            //inote isActive方法会返回true，然而channelActive是一个Inbound事件，所以不能由outbound操作直接触发（具体原因看上面代码的注释），需要将channelActive任务加入到boss线程的任务队列中，此时boss线程的任务队列已经执行完了bind任务，接着再执行channelActive任务
            if (!wasActive && isActive()) {
                invokeLater(new OneTimeTask() {
                    @Override
                    public void run() {
                        pipeline.fireChannelActive();//inote ChannelActive是一个inbound事件，因此会按照head->tail的顺序执行Inbound处理器，目前有三个处理器：head-> ServerBootstrapAcceptor->tail， ServerBootstrapAcceptor和tail都是inbound处理器
                    }
                });
            }

            safeSetSuccess(promise);
        }

        @Override
        public final void disconnect(final ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                doDisconnect();
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (wasActive && !isActive()) {
                invokeLater(new OneTimeTask() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            safeSetSuccess(promise);
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public final void close(final ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (outboundBuffer == null) {
                // Only needed if no VoidChannelPromise.
                if (!(promise instanceof VoidChannelPromise)) {
                    // This means close() was called before so we just register a listener and return
                    closeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }

            if (closeFuture.isDone()) {
                // Closed already.
                safeSetSuccess(promise);
                return;
            }

            final boolean wasActive = isActive();
            final ChannelOutboundBuffer buffer = outboundBuffer;
            outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
            Executor closeExecutor = closeExecutor();
            if (closeExecutor != null) {
                closeExecutor.execute(new OneTimeTask() {
                    @Override
                    public void run() {
                        try {
                            // Execute the close.
                            doClose0(promise);
                        } finally {
                            // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(new OneTimeTask() {
                                @Override
                                public void run() {
                                    // Fail all the queued messages
                                    buffer.failFlushed(CLOSED_CHANNEL_EXCEPTION, false);
                                    buffer.close(CLOSED_CHANNEL_EXCEPTION);
                                    fireChannelInactiveAndDeregister(wasActive);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise);
                } finally {
                    // Fail all the queued messages.
                    buffer.failFlushed(CLOSED_CHANNEL_EXCEPTION, false);
                    buffer.close(CLOSED_CHANNEL_EXCEPTION);
                }
                if (inFlush0) {
                    invokeLater(new OneTimeTask() {
                        @Override
                        public void run() {
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        private void doClose0(ChannelPromise promise) {
            try {
                doClose();
                closeFuture.setClosed();
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            if (wasActive && !isActive()) {
                invokeLater(new OneTimeTask() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            deregister(voidPromise());
        }

        @Override
        public final void closeForcibly() {
            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override
        public final void deregister(final ChannelPromise promise) {
            if (!promise.setUncancellable()) {
                return;
            }

            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            try {
                doDeregister();
            } catch (Throwable t) {
                logger.warn("Unexpected exception occurred while deregistering a channel.", t);
            } finally {
                if (registered) {
                    registered = false;
                    invokeLater(new OneTimeTask() {
                        @Override
                        public void run() {
                            pipeline.fireChannelUnregistered();
                        }
                    });
                    safeSetSuccess(promise);
                } else {
                    // Some transports like local and AIO does not allow the deregistration of
                    // an open channel.  Their doDeregister() calls close().  Consequently,
                    // close() calls deregister() again - no need to fire channelUnregistered.
                    safeSetSuccess(promise);
                }
            }
        }

        @Override
        public final void beginRead() {
            if (!isActive()) {
                return;
            }

            try {
                /**
                 * inote call function AbstractNioChannel.doBeginRead(),这个方法总是设置selector的interestOps的值，
                 * 增加read或者accept位，那么NioEventLoop会异步的处理这些read和accept请求。
                 */
                doBeginRead();
            } catch (final Exception e) {
                invokeLater(new OneTimeTask() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        @Override
        public final void write(Object msg, ChannelPromise promise) {
            //inote outboundBuffer是AbstractUnsafe使用的一种数据结构ChannelOutboundBuffer，用来存储待发送的消息。该数据结构在实例化AbstractUnsafe的同时被初始化
            //idoubt ChannelOutboundBuffer的数据结构 查看netty4.x源码解析-P72，4.0.1版本中使用了Recycler来获取outbuffer，当前版本没采用这种方式，为什么/
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                // If the outboundBuffer is null we know the channel was closed and so
                // need to fail the future right away. If it is not null the handling of the rest
                // will be done in flush0()
                // See https://github.com/netty/netty/issues/2362
                safeSetFailure(promise, CLOSED_CHANNEL_EXCEPTION);
                // release message now to prevent resource-leak
                ReferenceCountUtil.release(msg);
                return;
            }

            int size;
            try {
                msg = filterOutboundMessage(msg);
                size = estimatorHandle().size(msg);
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                ReferenceCountUtil.release(msg);
                return;
            }
            /**
             * idoubt 在4.0.01的下面的方法中，会调用这个方法：incrementPendingOutboundBytes，它会更新totalPendingSize，将其累加本次msg的大小。
             * 如果新的totalPendingSize超过了channel的高水位线writeBufferHighWaterMark（默认值为64 * 1024），
             * 则触发ChannelWritabilityChanged事件。（注意：如果网络很繁忙，套接字的发送缓冲区空间 不够，
             * 导致Msg不能及时从buffer中flush出去，那么不断的对channel执行write操作，会使得对数组不断地进行两倍扩容，最终导致OOM。
             * 所以最好在自己的Inbound处理器里捕获ChannelWritabilityChanged事件，然后调用channel的isWritable方法，
             * 根据结果来决定是否继续执行write操作）
             */
            outboundBuffer.addMessage(msg, size, promise);//inote 需好好分析研究此代码，关乎内存的
        }

        @Override
        public final void flush() {
            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                return;
            }
            //inote buffer中所有已有的Messages设置为flushed，并可以处理这些数据了
            outboundBuffer.addFlush();
            flush0();
        }

        protected void flush0() {
            if (inFlush0) {
                // Avoid re-entrance
                return;
            }

            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return;
            }

            inFlush0 = true;

            // Mark all pending write requests as failure if the channel is inactive.
            if (!isActive()) {
                try {
                    if (isOpen()) {
                        outboundBuffer.failFlushed(NOT_YET_CONNECTED_EXCEPTION, true);
                    } else {
                        // Do not trigger channelWritabilityChanged because the channel is closed already.
                        outboundBuffer.failFlushed(CLOSED_CHANNEL_EXCEPTION, false);
                    }
                } finally {
                    inFlush0 = false;
                }
                return;
            }

            try {
                doWrite(outboundBuffer);//inote NioSocketChannel
            } catch (Throwable t) {
                boolean close = t instanceof IOException && config().isAutoClose();
                // We do not want to trigger channelWritabilityChanged event if the channel is going to be closed.
                outboundBuffer.failFlushed(t, !close);
                if (close) {
                    close(voidPromise());
                }
            } finally {
                inFlush0 = false;
            }
        }

        @Override
        public final ChannelPromise voidPromise() {
            return unsafeVoidPromise;
        }

        protected final boolean ensureOpen(ChannelPromise promise) {
            if (isOpen()) {
                return true;
            }

            safeSetFailure(promise, CLOSED_CHANNEL_EXCEPTION);
            return false;
        }

        /**
         * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {//inote call io.netty.channel.DefaultChannelPromise.trySuccess()
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        private void invokeLater(Runnable task) {//inote 这个方法 的注释需要理解，为什么不能再OutBound中直接触发InBound事件
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //      -> channel.unsafe.close()
                //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                Throwable newT = new ConnectException(cause.getMessage() + ": " + remoteAddress);
                newT.setStackTrace(cause.getStackTrace());
                cause = newT;
            } else if (cause instanceof NoRouteToHostException) {
                Throwable newT = new NoRouteToHostException(cause.getMessage() + ": " + remoteAddress);
                newT.setStackTrace(cause.getStackTrace());
                cause = newT;
            } else if (cause instanceof SocketException) {
                Throwable newT = new SocketException(cause.getMessage() + ": " + remoteAddress);
                newT.setStackTrace(cause.getStackTrace());
                cause = newT;
            }

            return cause;
        }

        /**
         * @return {@link Executor} to execute {@link #doClose()} or {@code null} if it should be done in the
         * {@link EventLoop}.
         * +
         */
        protected Executor closeExecutor() {
            return null;
        }
    }

    /**
     * Return {@code true} if the given {@link EventLoop} is compatible with this instance.
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register process.
     * <p/>
     * Sub-classes may override this method
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     * <p/>
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }
}
