package eu.toolchain.serializer.io;

import java.io.IOException;

import eu.toolchain.serializer.SerialReader;
import eu.toolchain.serializer.Serializer;
import eu.toolchain.serializer.VarIntSerializer;
import lombok.RequiredArgsConstructor;

public abstract class AbstractSerialReader implements SerialReader {
    private static final Serializer<Integer> varint = new VarIntSerializer();

    public void skip() throws IOException {
        final int skip = varint.deserialize(this);
        skip(skip);
    }

    public SerialReader scope() throws IOException {
        final int size = varint.deserialize(this);
        return new ScopedSerialReader(this, size);
    }

    @RequiredArgsConstructor
    private static final class ScopedSerialReader extends AbstractSerialReader {
        private final SerialReader parent;
        private final int size;

        private int p = 0;

        @Override
        public byte read() throws IOException {
            ++p;
            checkScope();
            return parent.read();
        }

        @Override
        public void read(byte[] b) throws IOException {
            p += b.length;
            checkScope();
            parent.read(b);
        }

        @Override
        public void skip(int length) throws IOException {
            p += length;
            checkScope();
            parent.skip(length);
        }

        private void checkScope() throws IOException {
            if (p > size)
                throw new IOException("end of scope reached (p: " + p + ", size: " + size + ")");
        }
    }
}
