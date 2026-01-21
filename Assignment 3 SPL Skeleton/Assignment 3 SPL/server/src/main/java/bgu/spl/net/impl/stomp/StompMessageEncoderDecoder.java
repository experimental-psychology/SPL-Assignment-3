package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class StompMessageEncoderDecoder implements MessageEncoderDecoder<String> {

    private byte[] buffer = new byte[1 << 10];
    private int bufferLength = 0;

    @Override
    public String decodeNextByte(byte nextByte) {
        if (nextByte == '\0') {
            String result = popString();
            System.out.println("[DECODER] Got complete message: " + result.replace("\n", "\\n"));
            return result;
        }
        pushByte(nextByte);
        return null;
    }

    @Override
    public byte[] encode(String message) {
        if (message == null) message = "";
        if (!message.endsWith("\0")) message += "\0";
        return message.getBytes(StandardCharsets.UTF_8);
    }

    private void pushByte(byte nextByte) {
        ensureCapacity();
        buffer[bufferLength++] = nextByte;
    }

    private String popString() {
        String result = new String(buffer, 0, bufferLength, StandardCharsets.UTF_8);
        bufferLength = 0;
        return result;
    }

    private void ensureCapacity() {
        if (bufferLength >= buffer.length) {
            buffer = Arrays.copyOf(buffer, bufferLength * 2);
        }
    }
}
