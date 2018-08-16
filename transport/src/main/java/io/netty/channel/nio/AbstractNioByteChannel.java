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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {

    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
                    StringUtil.simpleClassName(FileRegion.class) + ')';

    private Runnable flushTask;

    /**
     * Create a new instance
     *
     * @param parent the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch     the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {
        private RecvByteBufAllocator.Handle allocHandle;

        private void closeOnRead(ChannelPipeline pipeline) {
            SelectionKey key = selectionKey();
            setInputShutdown();
            if (isOpen()) {
                if (Boolean.TRUE.equals(config().getOption(ChannelOption.ALLOW_HALF_CLOSURE))) {
                    key.interestOps(key.interestOps() & ~readInterestOp);
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            }
        }

        private void handleReadException(ChannelPipeline pipeline,
                                         ByteBuf byteBuf, Throwable cause, boolean close) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    setReadPending(false);
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        public final void read() {
            final ChannelConfig config = config();
            if (!config.isAutoRead() && !isReadPending()) {
                // ChannelConfig.setAutoRead(false) was called in the meantime
                removeReadOp();
                return;
            }

            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            final int maxMessagesPerRead = config.getMaxMessagesPerRead();
            RecvByteBufAllocator.Handle allocHandle = this.allocHandle;
            if (allocHandle == null) {
                /*inote 首先分析this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle()
                 config.getRecvByteBufAllocator()返回RecvByteBufAllocator，可以取名为接受缓存分配器。
                 该分配器用于为channel分配receive buffers以存储随后读取的字节。默认返回的分配器类型是自适应缓存分配器AdaptiveRecvByteBufAllocator，
                 它能根据前一次实际读取的字节数量，自适应调整当前缓存分配的大小，以防止缓存分配过多或过少。
                */
                this.allocHandle = allocHandle = config.getRecvByteBufAllocator().newHandle();//inote AdaptiveRecvByteBufAllocator
            }

            ByteBuf byteBuf = null;
            int messages = 0;
            boolean close = false;
            try {
                int totalReadAmount = 0;
                boolean readPendingReset = false;
                do {
                    /**
                     * inote 1. 先调用自适应接受缓存分配器中的handleImpl的allocate方法，分配大小为nextReceiveBufferSize的缓存
                     */
                    byteBuf = allocHandle.allocate(allocator);//inote AdaptiveRecvByteBufAllocator.HandleImpl.allocate()
                    int writable = byteBuf.writableBytes();
                    int localReadAmount = doReadBytes(byteBuf);//inote 2. 然后通过read系统调用，将数据从channel中读取到上一步分配的接受缓存中。
                    if (localReadAmount <= 0) {
                        // not was read, release the buffer
                        //inote a）如果返回0，则表示没有读取到数据，则退出循环
                        byteBuf.release();
                        byteBuf = null;
                        //inote b）如果返回-1，表示对端已经关闭连接，则退出循环
                        close = localReadAmount < 0;
                        break;
                    }
                    if (!readPendingReset) {
                        readPendingReset = true;
                        setReadPending(false);
                    }
                    //inote c）否则，表示读到了数据，则触发ChannelRead事件（inbound处理器可以通过实现channelRead方法对本次读取到的消息进行处理）
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;

                    if (totalReadAmount >= Integer.MAX_VALUE - localReadAmount) {
                        // Avoid overflow.
                        totalReadAmount = Integer.MAX_VALUE;
                        break;
                    }

                    totalReadAmount += localReadAmount;

                    // stop reading
                    if (!config.isAutoRead()) {
                        break;
                    }

                    if (localReadAmount < writable) {
                        // Read less than what the buffer can hold,
                        // which might mean we drained the recv buffer completely.
                        break;
                    }
                } while (++messages < maxMessagesPerRead);
                //inote d) 触发ChannelReadComplete事件（inbound处理器可以通过实现channelReadComplete方法对该事件进行响应）
                pipeline.fireChannelReadComplete();
                //inote e) 接着调用handleImpl的record方法，根据本次读取的字节数，自适应调整下次待分配的缓存大小。然后退出循环
                allocHandle.record(totalReadAmount);//inote 设定分配的内存大小
                //idoubt 2.	关于close、shutdown以及半关闭等，留待以后搞清楚后再进行分析。
                if (close) {
                    closeOnRead(pipeline);
                    close = false;
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!config.isAutoRead() && !isReadPending()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = -1;

        for (; ; ) {
            Object msg = in.current();
            //inote 如果当前msg为空，即buffer[flushed]存储的msg为空，则说明所有msg已经发送完毕，所以需要清除selectionKey中的OP_WRITE位。
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                break;
            }

            if (msg instanceof ByteBuf) {
                /**
                 * inote 首先调用buf.readableBytes()判断buf中是否有可读的消息，即writerIndex – readerIndex>0。
                 * 如果结果为0，则执行in.remove方法；否则，采用类似于自旋锁的逻辑对buf执行write操作。
                 */
                ByteBuf buf = (ByteBuf) msg;
                int readableBytes = buf.readableBytes();
                if (readableBytes == 0) {
                    in.remove();
                    continue;
                }

                boolean setOpWrite = false;
                boolean done = false;
                long flushedAmount = 0;
                if (writeSpinCount == -1) {
                    writeSpinCount = config().getWriteSpinCount();
                }
                //inote 《Netty源码分析.doc》文档P85解释了writeSpinCount的作用，如下：
                /**
                 * inote 由于是非阻塞IO，所以最终写到发送缓冲区中的字节数writtenBytes可能会小于buf中期望写出的字节数expectedWrittenBytes。
                 * 如果此时不再写，而是依赖selector的异步通知，则会导致buf里剩下的数据不能及时写出去（因为必须等到selector的下一次循环，
                 * 即必须将本次循环中通知的未处理完的所有事件处理完后，以及剩下的task执行完后，然后再执行一次select，才能处理到这个channel的write事件；
                 * 在这个过程中，还有可能会发生线程的上下文切换。这样，就会导致msg写到ChannelOutBoundBuffer后，会经历较大的延迟才能将消息
                 * flush到套接字的发送缓冲区。Netty采用类似于自旋锁的逻辑，在一个循环内，多次调用write。这样就有可能将buf中的所有数据在一次flush调用中写完。
                 * 循环的次数值为writeSpinCount，其默认值为16。
                 * 但是如果一次write调用返回0，则说明发送缓冲区已经完全没有空间了，如果还继续调用write，而系统调用开销是比较大的，所以是一种浪费，
                 * 此种情况可以退出循环，设置selectionKey的OP_WRITE位，以依赖selector的异步通知。
                 */
                for (int i = writeSpinCount - 1; i >= 0; i--) {
                    int localFlushedAmount = doWriteBytes(buf);//inote NioSocketChannel.doWriteBytes()
                    if (localFlushedAmount == 0) {
                        setOpWrite = true;//inote 这里设置为true，说明当前已经读完了所有的数据，需要设置op_write标志位，
                        break;
                    }

                    flushedAmount += localFlushedAmount;
                    if (!buf.isReadable()) {
                        done = true;
                        break;
                    }
                }
                //idoubt 这里是干啥的呢
                in.progress(flushedAmount);

                if (done) {
                    //inote 如果msg已全部写完毕，则执行in.remove()方法进行清理
                    in.remove();//inote 用到了低水位线的配置
                } else {
                    /**
                     * inote 如果在自旋期间多次调用write后，数据还是没有写完，而每次write调用的返回又不是0，说明每次的write确实写出去了一些字节，
                     * 这种情况也不能立即退出flush再依赖selector的异步通知，因为有可能是自旋锁的循环次数设置小了导致buf的数据没有发送完，
                     * 但实际发送缓冲区还是有空间的。因此将剩下数据的写作为一个异步任务放到当前线程的任务队列中，等待调度执行。
                     * 这样当本次循环中选择的剩下的所有事件处理完后，就可以执行这个任务了，而不用等到由下次的selector唤醒。
                     */
                    incompleteWrite(setOpWrite);
                    break;
                }
            } else if (msg instanceof FileRegion) {
                FileRegion region = (FileRegion) msg;
                boolean done = region.transfered() >= region.count();
                boolean setOpWrite = false;

                if (!done) {
                    long flushedAmount = 0;
                    if (writeSpinCount == -1) {
                        writeSpinCount = config().getWriteSpinCount();
                    }

                    for (int i = writeSpinCount - 1; i >= 0; i--) {
                        long localFlushedAmount = doWriteFileRegion(region);
                        if (localFlushedAmount == 0) {
                            setOpWrite = true;
                            break;
                        }

                        flushedAmount += localFlushedAmount;
                        if (region.transfered() >= region.count()) {
                            done = true;
                            break;
                        }
                    }

                    in.progress(flushedAmount);
                }

                if (done) {
                    in.remove();
                } else {
                    incompleteWrite(setOpWrite);
                    break;
                }
            } else {
                // Should not reach here.
                throw new Error();
            }
        }
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (buf.isDirect()) {
                return msg;
            }

            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }
    //inote 在新的线程中继续调用fulsh方法
    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            setOpWrite();
        } else {
            // Schedule flush again later so other tasks can be picked up in the meantime
            Runnable flushTask = this.flushTask;
            if (flushTask == null) {
                flushTask = this.flushTask = new Runnable() {
                    @Override
                    public void run() {
                        flush();
                    }
                };
            }
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     *
     * @param buf the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
