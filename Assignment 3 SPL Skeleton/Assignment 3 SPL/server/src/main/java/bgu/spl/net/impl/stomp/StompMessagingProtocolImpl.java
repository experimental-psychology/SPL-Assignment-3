
package bgu.spl.net.impl.stomp;

import bgu.spl.net.api.StompMessagingProtocol;
import bgu.spl.net.srv.Connections;
import bgu.spl.net.impl.data.Database;

import java.util.HashMap;
import java.util.Map;

public class StompMessagingProtocolImpl implements StompMessagingProtocol<String> {

    private int connectionId;
    private Connections<String> connections;
    private volatile boolean shouldTerminate = false;

    private boolean loggedIn = false;
    private String username = null;

    @Override
    public void start(int connectionId, Connections<String> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
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

        if (!loggedIn && !"CONNECT".equals(frame.command)) {
            sendErrorAndClose(frame, "Not connected", frame.headers.get("receipt"), frame.raw);
            return;
        }

        switch (frame.command) {
            case "CONNECT":     handleConnect(frame);     break;
            case "SUBSCRIBE":   handleSubscribe(frame);   break;
            case "UNSUBSCRIBE": handleUnsubscribe(frame); break;
            case "SEND":        handleSend(frame);        break;
            case "DISCONNECT":  handleDisconnect(frame);  break;
            default:
                sendErrorAndClose(frame, "Unknown command", frame.headers.get("receipt"), frame.raw);
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    private void handleConnect(Frame frame) {
        if (loggedIn) {
            sendErrorAndClose(frame, "User already logged in", receiptHeader(frame), frame.raw);
            return;
        }

        String login = header(frame, "login");
        String passcode = header(frame, "passcode");
        if (login == null || passcode == null) {
            sendErrorAndClose(frame, "Missing login or passcode", receiptHeader(frame), frame.raw);
            return;
        }

        Connections.LoginResult result = connections.tryLogin(login, passcode, connectionId);

        if (result == Connections.LoginResult.WRONG_PASSWORD) {
            sendErrorAndClose(frame, "Wrong password", receiptHeader(frame), frame.raw);
            return;
        }
        if (result == Connections.LoginResult.ALREADY_LOGGED_IN) {
            sendErrorAndClose(frame, "User already logged in", receiptHeader(frame), frame.raw);
            return;
        }

        loggedIn = true;
        username = login;

        Database.getInstance().login(connectionId, username, passcode);

        connections.send(connectionId, "CONNECTED\nversion:1.2\n\n");
        maybeSendReceipt(frame);
    }

    private void handleDisconnect(Frame frame) {
        String receipt = receiptHeader(frame);
        if (receipt == null) {
            sendErrorAndClose(frame, "DISCONNECT must include receipt header", null, frame.raw);
            return;
        }

        Database.getInstance().logout(connectionId);

        connections.send(connectionId, "RECEIPT\nreceipt-id:" + receipt + "\n\n");
        shouldTerminate = true;
    }

    private void handleSubscribe(Frame frame) {
        String destination = header(frame, "destination");
        String subscriptionId = header(frame, "id");
        if (destination == null || subscriptionId == null) {
            sendErrorAndClose(frame, "Missing destination or id", receiptHeader(frame), frame.raw);
            return;
        }

        if (!connections.subscribe(destination, connectionId, subscriptionId)) {
            sendErrorAndClose(frame, "Subscription id already exists", receiptHeader(frame), frame.raw);
            return;
        }

        maybeSendReceipt(frame);
    }

    private void handleUnsubscribe(Frame frame) {
        String subscriptionId = header(frame, "id");
        if (subscriptionId == null) {
            sendErrorAndClose(frame, "Missing id", receiptHeader(frame), frame.raw);
            return;
        }

        if (connections.unsubscribe(subscriptionId, connectionId) == null) {
            sendErrorAndClose(frame, "Subscription ID not found", receiptHeader(frame), frame.raw);
            return;
        }

        maybeSendReceipt(frame);
    }

    private void handleSend(Frame frame) {
        String destination = header(frame, "destination");
        if (destination == null) {
            sendErrorAndClose(frame, "Missing destination", receiptHeader(frame), frame.raw);
            return;
        }

        boolean subscribed = connections.isSubscribed(connectionId, destination);
        if (!subscribed) {
            sendErrorAndClose(frame, "User not subscribed to topic", receiptHeader(frame), frame.raw);
            return;
        }

        String payload = frame.body == null ? "" : frame.body;
        int messageId = connections.nextMessageId();
        Map<Integer, String> subscribers = connections.getChannelSubscribersSnapshot(destination);

        for (Map.Entry<Integer, String> entry : subscribers.entrySet()) {
            String message =
                    "MESSAGE\n" +
                    "destination:" + destination + "\n" +
                    "subscription:" + entry.getValue() + "\n" +
                    "message-id:" + messageId + "\n\n" +
                    payload;
            connections.send(entry.getKey(), message);
        }

        Database.getInstance().trackFileUpload(username, "unknown-file", destination);

        maybeSendReceipt(frame);
    }

    private void maybeSendReceipt(Frame frame) {
        String receipt = receiptHeader(frame);
        if (receipt != null) {
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + receipt + "\n\n");
        }
    }

    private void sendErrorAndClose(Frame frame, String msg, String rId, String raw) {
        StringBuilder sb = new StringBuilder("ERROR\nmessage:").append(msg).append("\n");
        if (rId != null) sb.append("receipt-id:").append(rId).append("\n");
        sb.append("\n");
        if (raw != null) sb.append("The message:\n-----\n").append(stripNull(raw)).append("\n-----\n");
        connections.send(connectionId, sb.toString());
        shouldTerminate = true;
    }

    private String header(Frame frame, String name) {
        return frame.headers.get(name);
    }

    private String receiptHeader(Frame frame) {
        return header(frame, "receipt");
    }

    private static String stripNull(String s) {
        if (s == null) return null;
        int i = s.indexOf('\0');
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static class Frame {
        String command;
        Map<String, String> headers;
        String body;
        String raw;

        static Frame parse(String msg) {
            if (msg == null) throw new IllegalArgumentException("null");
            Frame f = new Frame();
            f.raw = msg;

            String clean = stripNull(msg).replace("\r\n", "\n");
            int sep = clean.indexOf("\n\n");
            String head = (sep >= 0) ? clean.substring(0, sep) : clean;
            f.body = (sep >= 0) ? clean.substring(sep + 2) : "";

            String[] lines = head.split("\n");
            if (lines.length == 0 || lines[0].trim().isEmpty()) throw new IllegalArgumentException("empty");
            f.command = lines[0].trim();

            f.headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                int c = line.indexOf(':');
                if (c > 0) f.headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }
            return f;
        }
    }
}
