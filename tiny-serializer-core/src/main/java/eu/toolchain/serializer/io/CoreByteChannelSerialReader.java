package eu.toolchain.serializer.io;

import eu.toolchain.serializer.Serializer;
import eu.toolchain.serializer.SharedPool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

public class CoreByteChannelSerialReader extends AbstractSerialReader {
    public static final int SKIP_SIZE = 1024;

    final ByteBuffer one = ByteBuffer.allocate(1);
    final ReadableByteChannel channel;

    public CoreByteChannelSerialReader(
        final SharedPool pool, final Serializer<Integer> scopeSize,
        final ReadableByteChannel channel
    ) {
        super(pool, scopeSize);
        this.channel = channel;
    }

    @Override
    public byte read() throws IOException {
        channel.read(one);
        one.flip();
        final byte b = one.get();
        one.flip();
        return b;
    }

    @Override
    public void read(byte[] bytes, int offset, int length) throws IOException {
        channel.read(ByteBuffer.wrap(bytes, offset, length));
    }

    @Override
    public void skip(final int length) throws IOException {
        final ByteBuffer skip = ByteBuffer.allocate(SKIP_SIZE);

        int skipped = 0;

        while (skipped < length) {
            final ByteBuffer skipper = skip.asReadOnlyBuffer();
            final int current = Math.min(length - skipped, SKIP_SIZE);
            skipper.limit(current);
            channel.read(skipper);
            skipped += current;
        }
    }
}
