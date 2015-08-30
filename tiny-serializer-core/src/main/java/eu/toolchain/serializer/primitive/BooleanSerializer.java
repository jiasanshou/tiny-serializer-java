package eu.toolchain.serializer.primitive;

import java.io.IOException;

import eu.toolchain.serializer.SerialReader;
import eu.toolchain.serializer.SerialWriter;
import eu.toolchain.serializer.Serializer;

public class BooleanSerializer implements Serializer<Boolean> {
    private static final byte TRUE = 0x1;
    private static final byte FALSE = 0x0;

    @Override
    public void serialize(SerialWriter buffer, Boolean value) throws IOException {
        buffer.write(value ? TRUE : FALSE);
    }

    @Override
    public Boolean deserialize(SerialReader buffer) throws IOException {
        return buffer.read() == 0x1;
    }
}