package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;

import java.util.HashMap;
import java.util.Map;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private ConnectionsImpl<String> connections;
    private volatile boolean shouldTerminate = false;

    private boolean loggedIn = false;
    private String username = null;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = (ConnectionsImpl<String>) connections;
    }

    @Override
    public void process(String message) {
        if (shouldTerminate) return;

        Frame frame;
        try {
            frame = Frame.parse(message);
        } catch (Exception e) {
            sendErrorAndClose(null, "malformed frame received", null, message);
            return;
        }

        // Only CONNECT is allowed before login
        if (!loggedIn && !"CONNECT".equals(frame.command)) {
            sendErrorAndClose(frame, "Not connected", frame.headers.get("receipt"), frame.raw);
            return;
        }

        switch (frame.command) {
            case "CONNECT":
                handleConnect(frame);
                break;
            case "SUBSCRIBE":
                handleSubscribe(frame);
                break;
            case "UNSUBSCRIBE":
                handleUnsubscribe(frame);
                break;
            case "SEND":
                handleSend(frame);
                break;
            case "DISCONNECT":
                handleDisconnect(frame);
                break;
            default:
                sendErrorAndClose(frame, "Unknown command", frame.headers.get("receipt"), frame.raw);
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    // ---------- Handlers ----------

    private void handleConnect(Frame frame) {
        if (loggedIn) {
            sendErrorAndClose(frame, "User already logged in", frame.headers.get("receipt"), frame.raw);
            return;
        }

        String login = frame.headers.get("login");
        String passcode = frame.headers.get("passcode");

        if (login == null || passcode == null) {
            sendErrorAndClose(frame, "Missing login or passcode", frame.headers.get("receipt"), frame.raw);
            return;
        }

        // New-user / wrong-password / already-logged-in logic should be in ConnectionsImpl (shared state)
        ConnectionsImpl.LoginResult res = connections.tryLogin(login, passcode, connectionId);
        if (res == ConnectionsImpl.LoginResult.WRONG_PASSWORD) {
            sendErrorAndClose(frame, "Wrong password", frame.headers.get("receipt"), frame.raw);
            return;
        }
        if (res == ConnectionsImpl.LoginResult.ALREADY_LOGGED_IN) {
            sendErrorAndClose(frame, "User already logged in", frame.headers.get("receipt"), frame.raw);
            return;
        }

        loggedIn = true;
        username = login;

        connections.send(connectionId, buildConnected());
        maybeSendReceipt(frame);
    }

    private void handleSubscribe(Frame frame) {
        String destination = frame.headers.get("destination");
        String subId = frame.headers.get("id");

        if (destination == null || subId == null) {
            sendErrorAndClose(frame, "Missing destination or id", frame.headers.get("receipt"), frame.raw);
            return;
        }

        // IMPORTANT: use the upgraded ConnectionsImpl API (store subscriptionId)
        boolean ok = connections.subscribe(destination, connectionId, subId);
        if (!ok) {
            sendErrorAndClose(frame, "Subscription id already exists", frame.headers.get("receipt"), frame.raw);
            return;
        }

        maybeSendReceipt(frame);
    }

    private void handleUnsubscribe(Frame frame) {
        String subId = frame.headers.get("id");
        if (subId == null) {
            sendErrorAndClose(frame, "Missing id", frame.headers.get("receipt"), frame.raw);
            return;
        }

        String removedChannel = connections.unsubscribe(subId, connectionId);
        if (removedChannel == null) {
            sendErrorAndClose(frame, "Subscription ID not found", frame.headers.get("receipt"), frame.raw);
            return;
        }

        maybeSendReceipt(frame);
    }

    private void handleSend(Frame frame) {
        String destination = frame.headers.get("destination");
        if (destination == null) {
            sendErrorAndClose(frame, "Missing destination", frame.headers.get("receipt"), frame.raw);
            return;
        }

        // sender must be subscribed
        if (!connections.isSubscribed(connectionId, destination)) {
            sendErrorAndClose(frame, "User not subscribed to topic", frame.headers.get("receipt"), frame.raw);
            return;
        }

        int messageId = connections.nextMessageId(); // global counter in ConnectionsImpl
        String body = frame.body == null ? "" : frame.body;

        // Per-recipient MESSAGE: subscription header differs per client
        Map<Integer, String> subscribers = connections.getChannelSubscribersSnapshot(destination);
        for (Map.Entry<Integer, String> e : subscribers.entrySet()) {
            int targetConnId = e.getKey();
            String targetSubId = e.getValue();

            String msgToSend = buildMessage(destination, targetSubId, messageId, body);
            connections.send(targetConnId, msgToSend);
        }

        maybeSendReceipt(frame);
    }

    private void handleDisconnect(Frame frame) {
        // DISCONNECT must include receipt
        String receipt = frame.headers.get("receipt");
        if (receipt == null) {
            sendErrorAndClose(frame, "DISCONNECT must include receipt", null, frame.raw);
            return;
        }

        connections.send(connectionId, buildReceipt(receipt));

        shouldTerminate = true;
        // will also cleanup subscriptions + logout via ConnectionsImpl.disconnect if you implemented it that way
        connections.disconnect(connectionId);
    }

    // ---------- Helpers ----------

    private void maybeSendReceipt(Frame frame) {
        String receipt = frame.headers.get("receipt");
        if (receipt != null) {
            connections.send(connectionId, buildReceipt(receipt));
        }
    }

    private void sendErrorAndClose(Frame frame, String msg, String receiptId, String originalFrame) {
        connections.send(connectionId, buildError(msg, receiptId, originalFrame));
        shouldTerminate = true;
        connections.disconnect(connectionId);
    }

    // NO '\0' here — Encoder adds it
    private String buildConnected() {
        return "CONNECTED\nversion:1.2\n\n";
    }

    private String buildReceipt(String receiptId) {
        return "RECEIPT\nreceipt-id:" + receiptId + "\n\n";
    }

    private String buildMessage(String destination, String subscriptionId, int messageId, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("MESSAGE\n");
        sb.append("destination:").append(destination).append("\n");
        sb.append("subscription:").append(subscriptionId).append("\n");
        sb.append("message-id:").append(messageId).append("\n");
        sb.append("\n");
        if (body != null && !body.isEmpty()) sb.append(body);
        return sb.toString();
    }

    private String buildError(String message, String receiptId, String originalFrame) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR\n");
        sb.append("message:").append(message == null ? "" : message).append("\n");
        if (receiptId != null) sb.append("receipt-id:").append(receiptId).append("\n");
        sb.append("\n");
        if (originalFrame != null && !originalFrame.isEmpty()) {
            sb.append("The message:\n-----\n");
            sb.append(stripNull(originalFrame)).append("\n-----\n");
        }
        return sb.toString();
    }

    private static String stripNull(String s) {
        if (s == null) return null;
        int i = s.indexOf('\0');
        return i >= 0 ? s.substring(0, i) : s;
    }

    // ---------- Frame parser ----------

    private static class Frame {
        String command;
        Map<String, String> headers;
        String body;
        String raw;

        static Frame parse(String msg) {
            if (msg == null) throw new IllegalArgumentException("null frame");
            Frame f = new Frame();
            f.raw = msg;

            String clean = stripNull(msg).replace("\r\n", "\n");
            int sep = clean.indexOf("\n\n");
            String head = (sep >= 0) ? clean.substring(0, sep) : clean;
            f.body = (sep >= 0) ? clean.substring(sep + 2) : "";

            String[] lines = head.split("\n");
            if (lines.length == 0 || lines[0].trim().isEmpty()) {
                throw new IllegalArgumentException("missing command");
            }
            f.command = lines[0].trim();

            f.headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                int c = line.indexOf(':');
                if (c <= 0) throw new IllegalArgumentException("bad header: " + line);
                f.headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }
            return f;
        }
    }
}
