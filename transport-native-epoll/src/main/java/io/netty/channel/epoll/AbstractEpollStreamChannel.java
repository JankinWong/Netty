/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;

import io.netty.buffer.ByteBufConvertible;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.DuplexChannel;
import io.netty.channel.unix.IovArray;
import io.netty.channel.unix.SocketWritableByteChannel;
import io.netty.channel.unix.UnixChannelUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.Executor;

import static io.netty.channel.internal.ChannelUtils.MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD;
import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

public abstract class AbstractEpollStreamChannel extends AbstractEpollChannel implements DuplexChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
                    StringUtil.simpleClassName(DefaultFileRegion.class) + ')';
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractEpollStreamChannel.class);
    private final Runnable flushTask = () -> {
        // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
        // meantime.
        ((AbstractEpollUnsafe) unsafe()).flush0();
    };

    private WritableByteChannel byteChannel;

    protected AbstractEpollStreamChannel(Channel parent, EventLoop eventLoop, int fd) {
        this(parent, eventLoop, new LinuxSocket(fd));
    }

    protected AbstractEpollStreamChannel(EventLoop eventLoop, int fd) {
        this(eventLoop, new LinuxSocket(fd));
    }

    AbstractEpollStreamChannel(EventLoop eventLoop, LinuxSocket fd) {
        this(eventLoop, fd, isSoErrorZero(fd));
    }

    AbstractEpollStreamChannel(Channel parent, EventLoop eventLoop, LinuxSocket fd) {
        super(parent, eventLoop, fd, true);
        // Add EPOLLRDHUP so we are notified once the remote peer close the connection.
        flags |= Native.EPOLLRDHUP;
    }

    AbstractEpollStreamChannel(Channel parent, EventLoop eventLoop, LinuxSocket fd, SocketAddress remote) {
        super(parent, eventLoop, fd, remote);
        // Add EPOLLRDHUP so we are notified once the remote peer close the connection.
        flags |= Native.EPOLLRDHUP;
    }

    protected AbstractEpollStreamChannel(EventLoop eventLoop, LinuxSocket fd, boolean active) {
        super(null, eventLoop, fd, active);
        // Add EPOLLRDHUP so we are notified once the remote peer close the connection.
        flags |= Native.EPOLLRDHUP;
    }

    @Override
    protected AbstractEpollUnsafe newUnsafe() {
        return new EpollStreamUnsafe();
    }

    @Override
    public abstract EpollDuplexChannelConfig config();

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param in the collection which contains objects to write.
     * @param buf the {@link ByteBuf} from which the bytes should be written
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but
     *     no data was accepted</li>
     * </ul>
     */
    private int writeBytes(ChannelOutboundBuffer in, ByteBuf buf) throws Exception {
        int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            in.remove();
            return 0;
        }

        if (buf.hasMemoryAddress() || buf.nioBufferCount() == 1) {
            return doWriteBytes(in, buf);
        } else {
            ByteBuffer[] nioBuffers = buf.nioBuffers();
            return writeBytesMultiple(in, nioBuffers, nioBuffers.length, readableBytes,
                    config().getMaxBytesPerGatheringWrite());
        }
    }

    private void adjustMaxBytesPerGatheringWrite(long attempted, long written, long oldMaxBytesPerGatheringWrite) {
        // By default we track the SO_SNDBUF when ever it is explicitly set. However some OSes may dynamically change
        // SO_SNDBUF (and other characteristics that determine how much data can be written at once) so we should try
        // make a best effort to adjust as OS behavior changes.
        if (attempted == written) {
            if (attempted << 1 > oldMaxBytesPerGatheringWrite) {
                config().setMaxBytesPerGatheringWrite(attempted << 1);
            }
        } else if (attempted > MAX_BYTES_PER_GATHERING_WRITE_ATTEMPTED_LOW_THRESHOLD && written < attempted >>> 1) {
            config().setMaxBytesPerGatheringWrite(attempted >>> 1);
        }
    }

    /**
     * Write multiple bytes via {@link IovArray}.
     * @param in the collection which contains objects to write.
     * @param array The array which contains the content to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but
     *     no data was accepted</li>
     * </ul>
     * @throws IOException If an I/O exception occurs during write.
     */
    private int writeBytesMultiple(ChannelOutboundBuffer in, IovArray array) throws IOException {
        final long expectedWrittenBytes = array.size();
        assert expectedWrittenBytes != 0;
        final int cnt = array.count();
        assert cnt != 0;

        final long localWrittenBytes = socket.writevAddresses(array.memoryAddress(0), cnt);
        if (localWrittenBytes > 0) {
            adjustMaxBytesPerGatheringWrite(expectedWrittenBytes, localWrittenBytes, array.maxBytes());
            in.removeBytes(localWrittenBytes);
            return 1;
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    /**
     * Write multiple bytes via {@link ByteBuffer} array.
     * @param in the collection which contains objects to write.
     * @param nioBuffers The buffers to write.
     * @param nioBufferCnt The number of buffers to write.
     * @param expectedWrittenBytes The number of bytes we expect to write.
     * @param maxBytesPerGatheringWrite The maximum number of bytes we should attempt to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but
     *     no data was accepted</li>
     * </ul>
     * @throws IOException If an I/O exception occurs during write.
     */
    private int writeBytesMultiple(
            ChannelOutboundBuffer in, ByteBuffer[] nioBuffers, int nioBufferCnt, long expectedWrittenBytes,
            long maxBytesPerGatheringWrite) throws IOException {
        assert expectedWrittenBytes != 0;
        if (expectedWrittenBytes > maxBytesPerGatheringWrite) {
            expectedWrittenBytes = maxBytesPerGatheringWrite;
        }

        final long localWrittenBytes = socket.writev(nioBuffers, 0, nioBufferCnt, expectedWrittenBytes);
        if (localWrittenBytes > 0) {
            adjustMaxBytesPerGatheringWrite(expectedWrittenBytes, localWrittenBytes, maxBytesPerGatheringWrite);
            in.removeBytes(localWrittenBytes);
            return 1;
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    /**
     * Write a {@link DefaultFileRegion}
     * @param in the collection which contains objects to write.
     * @param region the {@link DefaultFileRegion} from which the bytes should be written
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but
     *     no data was accepted</li>
     * </ul>
     */
    private int writeDefaultFileRegion(ChannelOutboundBuffer in, DefaultFileRegion region) throws Exception {
        final long offset = region.transferred();
        final long regionCount = region.count();
        if (offset >= regionCount) {
            in.remove();
            return 0;
        }

        final long flushedAmount = socket.sendFile(region, region.position(), offset, regionCount - offset);
        if (flushedAmount > 0) {
            in.progress(flushedAmount);
            if (region.transferred() >= regionCount) {
                in.remove();
            }
            return 1;
        } else if (flushedAmount == 0) {
            validateFileRegion(region, offset);
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    /**
     * Write a {@link FileRegion}
     * @param in the collection which contains objects to write.
     * @param region the {@link FileRegion} from which the bytes should be written
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but
     *     no data was accepted</li>
     * </ul>
     */
    private int writeFileRegion(ChannelOutboundBuffer in, FileRegion region) throws Exception {
        if (region.transferred() >= region.count()) {
            in.remove();
            return 0;
        }

        if (byteChannel == null) {
            byteChannel = new EpollSocketWritableByteChannel();
        }
        final long flushedAmount = region.transferTo(byteChannel, region.transferred());
        if (flushedAmount > 0) {
            in.progress(flushedAmount);
            if (region.transferred() >= region.count()) {
                in.remove();
            }
            return 1;
        }
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            final int msgCount = in.size();
            // Do gathering write if the outbound buffer entries start with more than one ByteBuf.
            if (msgCount > 1 && in.current() instanceof ByteBufConvertible) {
                writeSpinCount -= doWriteMultiple(in);
            } else if (msgCount == 0) {
                // Wrote all messages.
                clearFlag(Native.EPOLLOUT);
                // Return here so we not set the EPOLLOUT flag.
                return;
            } else {  // msgCount == 1
                writeSpinCount -= doWriteSingle(in);
            }

            // We do not break the loop here even if the outbound buffer was flushed completely,
            // because a user might have triggered another write and flush when we notify his or her
            // listeners.
        } while (writeSpinCount > 0);

        if (writeSpinCount == 0) {
            // It is possible that we have set EPOLLOUT, woken up by EPOLL because the socket is writable, and then use
            // our write quantum. In this case we no longer want to set the EPOLLOUT flag because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the EPOLLOUT if necessary.
            clearFlag(Native.EPOLLOUT);

            // We used our writeSpin quantum, and should try to write again later.
            eventLoop().execute(flushTask);
        } else {
            // Underlying descriptor can not accept all data currently, so set the EPOLLOUT flag to be woken up
            // when it can accept more data.
            setFlag(Native.EPOLLOUT);
        }
    }

    /**
     * Attempt to write a single object.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but
     *     no data was accepted</li>
     * </ul>
     * @throws Exception If an I/O error occurs.
     */
    protected int doWriteSingle(ChannelOutboundBuffer in) throws Exception {
        // The outbound buffer contains only one message or it contains a file region.
        Object msg = in.current();
        if (msg instanceof ByteBufConvertible) {
            return writeBytes(in, ((ByteBufConvertible) msg).asByteBuf());
        } else if (msg instanceof DefaultFileRegion) {
            return writeDefaultFileRegion(in, (DefaultFileRegion) msg);
        } else if (msg instanceof FileRegion) {
            return writeFileRegion(in, (FileRegion) msg);
        } else {
            // Should never reach here.
            throw new Error();
        }
    }

    /**
     * Attempt to write multiple {@link ByteBuf} objects.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but
     *     no data was accepted</li>
     * </ul>
     * @throws Exception If an I/O error occurs.
     */
    private int doWriteMultiple(ChannelOutboundBuffer in) throws Exception {
        final long maxBytesPerGatheringWrite = config().getMaxBytesPerGatheringWrite();
        IovArray array = registration().cleanIovArray();
        array.maxBytes(maxBytesPerGatheringWrite);
        in.forEachFlushedMessage(array);

        if (array.count() >= 1) {
            // TODO: Handle the case where cnt == 1 specially.
            return writeBytesMultiple(in, array);
        }
        // cnt == 0, which means the outbound buffer contained empty buffers only.
        in.removeBytes(0);
        return 0;
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBufConvertible) {
            ByteBuf buf = ((ByteBufConvertible) msg).asByteBuf();
            return UnixChannelUtil.isBufferCopyNeededForWrite(buf)? newDirectBuffer(buf): buf;
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        socket.shutdown(false, true);
    }

    private void shutdownInput0(Promise<Void> promise) {
        try {
            socket.shutdown(true, false);
            promise.setSuccess(null);
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
    }

    @Override
    public boolean isOutputShutdown() {
        return socket.isOutputShutdown();
    }

    @Override
    public boolean isInputShutdown() {
        return socket.isInputShutdown();
    }

    @Override
    public boolean isShutdown() {
        return socket.isShutdown();
    }

    @Override
    public Future<Void> shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public Future<Void> shutdownOutput(final Promise<Void> promise) {
        EventLoop loop = eventLoop();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(() -> ((AbstractUnsafe) unsafe()).shutdownOutput(promise));
        }

        return promise.asFuture();
    }

    @Override
    public Future<Void> shutdownInput() {
        return shutdownInput(newPromise());
    }

    @Override
    public Future<Void> shutdownInput(final Promise<Void> promise) {
        Executor closeExecutor = ((EpollStreamUnsafe) unsafe()).prepareToClose();
        if (closeExecutor != null) {
            closeExecutor.execute(() -> shutdownInput0(promise));
        } else {
            EventLoop loop = eventLoop();
            if (loop.inEventLoop()) {
                shutdownInput0(promise);
            } else {
                loop.execute(() -> shutdownInput0(promise));
            }
        }
        return promise.asFuture();
    }

    @Override
    public Future<Void> shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public Future<Void> shutdown(Promise<Void> promise) {
        Future<Void> shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(promise, shutdownOutputFuture);
        } else {
            shutdownOutputFuture.addListener(promise, this::shutdownOutputDone);
        }
        return promise.asFuture();
    }

    private void shutdownOutputDone(Promise<Void> promise, Future<?> shutdownOutputFuture) {
        Future<Void> shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(shutdownInputFuture1 ->
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture1, promise));
        }
    }

    private static void shutdownDone(Future<?> shutdownOutputFuture,
                              Future<?> shutdownInputFuture,
                              Promise<Void> promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess(null);
        }
    }

    class EpollStreamUnsafe extends AbstractEpollUnsafe {
        // Overridden here just to be able to access this method from AbstractEpollStreamChannel
        @Override
        protected Executor prepareToClose() {
            return super.prepareToClose();
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                EpollRecvByteAllocatorHandle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);

            // If oom will close the read event, release connection.
            // See https://github.com/netty/netty/issues/10434
            if (close || cause instanceof OutOfMemoryError || cause instanceof IOException) {
                shutdownInput(false);
            } else {
                readIfIsAutoRead();
            }
        }

        @Override
        EpollRecvByteAllocatorHandle newEpollHandle(RecvByteBufAllocator.ExtendedHandle handle) {
            return new EpollRecvByteAllocatorStreamingHandle(handle);
        }

        @Override
        void epollInReady() {
            final ChannelConfig config = config();
            if (shouldBreakEpollInReady(config)) {
                clearEpollIn0();
                return;
            }
            final EpollRecvByteAllocatorHandle allocHandle = recvBufAllocHandle();

            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            allocHandle.reset(config);
            epollInBefore();

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    // we use a direct buffer here as the native implementations only be able
                    // to handle direct buffers.
                    byteBuf = allocHandle.allocate(allocator);
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read, release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            readPending = false;
                        }
                        break;
                    }
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;

                    if (shouldBreakEpollInReady(config)) {
                        // We need to do this for two reasons:
                        //
                        // - If the input was shutdown in between (which may be the case when the user did it in the
                        //   fireChannelRead(...) method we should not try to read again to not produce any
                        //   miss-leading exceptions.
                        //
                        // - If the user closes the channel we need to ensure we not try to read from it again as
                        //   the filedescriptor may be re-used already by the OS if the system is handling a lot of
                        //   concurrent connections and so needs a lot of filedescriptors. If not do this we risk
                        //   reading data from a filedescriptor that belongs to another socket then the socket that
                        //   was "wrapped" by this Channel implementation.
                        break;
                    }
                } while (allocHandle.continueReading());

                allocHandle.readComplete();
                pipeline.fireChannelReadComplete();

                if (close) {
                    shutdownInput(false);
                } else {
                    readIfIsAutoRead();
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                epollInFinally(config);
            }
        }
    }

    private final class EpollSocketWritableByteChannel extends SocketWritableByteChannel {
        EpollSocketWritableByteChannel() {
            super(socket);
        }

        @Override
        protected ByteBufAllocator alloc() {
            return AbstractEpollStreamChannel.this.alloc();
        }
    }
}
