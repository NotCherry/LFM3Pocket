package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;

public final class HistoryMediaStoreTest {
    @Test public void copyFileReplacesDestinationWithCompleteBytes() throws Exception {
        File dir = Files.createTempDirectory("history-media-copy").toFile();
        File source = new File(dir, "source.img");
        File destination = new File(dir, "destination.img");
        byte[] expected = new byte[256 * 1024 + 37];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31);
        Files.write(source.toPath(), expected);
        Files.write(destination.toPath(), new byte[] { 9, 8, 7 });

        HistoryMediaStore.copyFile(source, destination);

        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        assertFalse(new File(destination.getAbsolutePath() + ".partial").exists());
    }

    @Test public void copyFilePublishesEmptySource() throws Exception {
        File dir = Files.createTempDirectory("history-media-empty").toFile();
        File source = new File(dir, "empty.img");
        File destination = new File(dir, "destination.img");
        assertTrue(source.createNewFile());
        Files.write(destination.toPath(), new byte[] { 1, 2, 3 });

        HistoryMediaStore.copyFile(source, destination);

        assertTrue(destination.isFile());
        assertEquals(0L, destination.length());
    }

    @Test public void copyFilePreservesDestinationAndRemovesTempWhenPublishFails() throws Exception {
        File dir = Files.createTempDirectory("history-media-publish-fail").toFile();
        File source = new File(dir, "source.img");
        File destination = new File(dir, "destination.img");
        byte[] sourceBytes = new byte[]{1, 2, 3, 4, 5};
        byte[] destBytes = new byte[]{9, 9, 9};
        Files.write(source.toPath(), sourceBytes);
        Files.write(destination.toPath(), destBytes);

        try {
            HistoryMediaStore.copyFile(source, destination, (tmp, dest) -> {
                throw new IOException("boom");
            });
            fail("expected IOException");
        } catch (IOException expected) {
            // expected
        }
        assertArrayEquals(destBytes, Files.readAllBytes(destination.toPath()));
        assertFalse(new File(destination.getAbsolutePath() + ".partial").exists());
    }

    @Test public void transferAllCopiesExactBytesWhenTransferToMakesNoProgress() throws Exception {
        File dir = Files.createTempDirectory("history-media-transfer").toFile();
        File source = new File(dir, "source.img");
        File destination = new File(dir, "destination.img");
        byte[] expected = new byte[128 * 1024 + 17];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 7);
        Files.write(source.toPath(), expected);

        try (RandomAccessFile inFile = new RandomAccessFile(source, "r");
             FileChannel in = new ZeroOnceFileChannel(inFile.getChannel());
             RandomAccessFile outFile = new RandomAccessFile(destination, "rw");
             FileChannel out = outFile.getChannel()) {
            HistoryMediaStore.transferAll(in, out, expected.length);
        }
        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
    }

    @Test public void transferAllCopiesExactBytesWhenTransferToReturnsPartialChunks() throws Exception {
        File dir = Files.createTempDirectory("history-media-partial").toFile();
        File source = new File(dir, "source.img");
        File destination = new File(dir, "destination.img");
        byte[] expected = new byte[128 * 1024 + 17];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 11);
        Files.write(source.toPath(), expected);

        try (RandomAccessFile inFile = new RandomAccessFile(source, "r");
             FileChannel in = new ChunkedTransferFileChannel(inFile.getChannel(), 4096);
             RandomAccessFile outFile = new RandomAccessFile(destination, "rw");
             FileChannel out = outFile.getChannel()) {
            HistoryMediaStore.transferAll(in, out, expected.length);
        }
        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
    }

    @Test public void transferAllRejectsIncompleteCopyWhenSourceEndsEarly() throws Exception {
        File dir = Files.createTempDirectory("history-media-truncated").toFile();
        File source = new File(dir, "source.img");
        File destination = new File(dir, "destination.img");
        byte[] actual = new byte[] {1, 2, 3, 4, 5};
        Files.write(source.toPath(), actual);

        try (RandomAccessFile inFile = new RandomAccessFile(source, "r");
             FileChannel in = inFile.getChannel();
             RandomAccessFile outFile = new RandomAccessFile(destination, "rw");
             FileChannel out = outFile.getChannel()) {
            HistoryMediaStore.transferAll(in, out, actual.length + 3L);
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Could not make progress copying history media")
                    || expected.getMessage().contains("History media copy was incomplete"));
        }
    }

    private static final class ZeroOnceFileChannel extends FileChannel {
        private final FileChannel delegate;
        private boolean zeroed;

        private ZeroOnceFileChannel(FileChannel delegate) {
            this.delegate = delegate;
        }

        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return delegate.read(dsts, offset, length); }
        @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return delegate.write(srcs, offset, length); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long newPosition) throws IOException { delegate.position(newPosition); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        @Override public void force(boolean metaData) throws IOException { delegate.force(metaData); }
        @Override public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target) throws IOException {
            if (!zeroed && count > 0) { zeroed = true; return 0L; }
            return delegate.transferTo(position, count, target);
        }
        @Override public long transferFrom(java.nio.channels.ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }
        @Override public int read(ByteBuffer dst, long position) throws IOException { return delegate.read(dst, position); }
        @Override public int write(ByteBuffer src, long position) throws IOException { return delegate.write(src, position); }
        @Override public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
        @Override public java.nio.channels.FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
        @Override public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
    }

    private static final class ChunkedTransferFileChannel extends FileChannel {
        private final FileChannel delegate;
        private final int maxChunk;

        private ChunkedTransferFileChannel(FileChannel delegate, int maxChunk) {
            this.delegate = delegate;
            this.maxChunk = maxChunk;
        }

        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) throws IOException { return delegate.read(dsts, offset, length); }
        @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) throws IOException { return delegate.write(srcs, offset, length); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public FileChannel position(long newPosition) throws IOException { delegate.position(newPosition); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        @Override public void force(boolean metaData) throws IOException { delegate.force(metaData); }
        @Override public long transferTo(long position, long count, java.nio.channels.WritableByteChannel target) throws IOException {
            long limited = Math.min(count, (long) maxChunk);
            return delegate.transferTo(position, limited, target);
        }
        @Override public long transferFrom(java.nio.channels.ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }
        @Override public int read(ByteBuffer dst, long position) throws IOException { return delegate.read(dst, position); }
        @Override public int write(ByteBuffer src, long position) throws IOException { return delegate.write(src, position); }
        @Override public java.nio.MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
        @Override public java.nio.channels.FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
        @Override public java.nio.channels.FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
        @Override protected void implCloseChannel() throws IOException { delegate.close(); }
    }
}
