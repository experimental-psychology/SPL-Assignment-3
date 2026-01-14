package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.MessageEncoderDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * STOMP frames are terminated by the null character '\0'.
 */
public class StompMessageEncoderDecoder implements MessageEncoderDecoder<String> {

    private byte[] bytes = new byte[1 << 10]; // 1KB initial buffer
    private int len = 0;

    @Override
    public String decodeNextByte(byte nextByte) {
        // frame ends on '\0'  :contentReference[oaicite:7]{index=7}
        if (nextByte == '\0') {
            return popString();
        }
        pushByte(nextByte);
        return null;
    }

    @Override
    public byte[] encode(String message) {
        if (message == null) message = "";
        // ensure terminator
        if (!message.endsWith("\0")) message += "\0";
        return message.getBytes(StandardCharsets.UTF_8);
    }

    private void pushByte(byte nextByte) {
        if (len >= bytes.length) {
            bytes = Arrays.copyOf(bytes, len * 2);
        }
        bytes[len++] = nextByte;
    }

    private String popString() {
        String result = new String(bytes, 0, len, StandardCharsets.UTF_8);
        len = 0;
        return result;
    }
}
