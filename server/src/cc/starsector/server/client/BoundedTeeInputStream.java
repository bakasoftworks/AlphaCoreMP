package cc.starsector.server.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

final class BoundedTeeInputStream extends InputStream {
    private final InputStream delegate;
    private final OutputStream tee;
    private final long expectedBytes;
    private long bytesRead;

    BoundedTeeInputStream(InputStream delegate, OutputStream tee, long expectedBytes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tee = Objects.requireNonNull(tee, "tee");
        this.expectedBytes = expectedBytes;
    }

    @Override
    public int read() throws IOException {
        if (bytesRead == expectedBytes) {
            return -1;
        }
        int value = delegate.read();
        if (value < 0) {
            return -1;
        }
        countBytes(1);
        tee.write(value);
        return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        long remaining = expectedBytes - bytesRead;
        if (remaining == 0L) {
            return -1;
        }
        int boundedLen = len;
        if (remaining > 0L && remaining < boundedLen) {
            boundedLen = (int) Math.min((long) Integer.MAX_VALUE, remaining);
        }
        int read = delegate.read(b, off, boundedLen);
        if (read < 0) {
            return -1;
        }
        countBytes(read);
        tee.write(b, off, read);
        return read;
    }

    private void countBytes(int read) throws IOException {
        bytesRead += read;
        if (bytesRead > expectedBytes) {
            throw new IOException("headless snapshot client byte bound exceeded");
        }
    }

    long bytesRead() {
        return bytesRead;
    }
}
