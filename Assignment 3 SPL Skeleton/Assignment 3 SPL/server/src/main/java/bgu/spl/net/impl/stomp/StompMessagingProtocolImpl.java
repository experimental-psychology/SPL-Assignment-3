
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
            sendErrorAndClose(frame, "User already logged in", frame.headers.get("receipt"), frame.raw);
            return;
        }

        String login = frame.headers.get("login");
        String passcode = frame.headers.get("passcode");
        if (login == null || passcode == null) {
            sendErrorAndClose(frame, "Missing login or passcode", frame.headers.get("receipt"), frame.raw);
            return;
        }

        Connections.LoginResult res = connections.tryLogin(login, passcode, connectionId);

        if (res == Connections.LoginResult.WRONG_PASSWORD) {
            sendErrorAndClose(frame, "Wrong password", frame.headers.get("receipt"), frame.raw);
            return;
        }
        if (res == Connections.LoginResult.ALREADY_LOGGED_IN) {
            sendErrorAndClose(frame, "User already logged in", frame.headers.get("receipt"), frame.raw);
            return;
        }

        loggedIn = true;
        username = login;

        // SQL logging
        Database.getInstance().login(connectionId, username, passcode);

        connections.send(connectionId, "CONNECTED\nversion:1.2\n\n");
        maybeSendReceipt(frame);
    }

    private void handleSubscribe(Frame frame) {
        String dest = frame.headers.get("destination");
        String id = frame.headers.get("id");
        if (dest == null || id == null) {
            sendErrorAndClose(frame, "Missing destination or id", frame.headers.get("receipt"), frame.raw);
            return;
        }

        if (!connections.subscribe(dest, connectionId, id)) {
            sendErrorAndClose(frame, "Subscription id already exists", frame.headers.get("receipt"), frame.raw);
            return;
        }

        maybeSendReceipt(frame);
    }

    private void handleUnsubscribe(Frame frame) {
        String id = frame.headers.get("id");
        if (id == null) {
            sendErrorAndClose(frame, "Missing id", frame.headers.get("receipt"), frame.raw);
            return;
        }

        if (connections.unsubscribe(id, connectionId) == null) {
            sendErrorAndClose(frame, "Subscription ID not found", frame.headers.get("receipt"), frame.raw);
            return;
        }

        maybeSendReceipt(frame);
    }

    private void handleSend(Frame frame) {
        String dest = frame.headers.get("destination");
        if (dest == null) {
            sendErrorAndClose(frame, "Missing destination", frame.headers.get("receipt"), frame.raw);
            return;
        }

        boolean isSubscribed = connections.isSubscribed(connectionId, dest);
        if (!isSubscribed) {
            sendErrorAndClose(frame, "User not subscribed to topic", frame.headers.get("receipt"), frame.raw);
            return;
        }

        String body = frame.body == null ? "" : frame.body;
        int msgId = connections.nextMessageId();
        Map<Integer, String> subs = connections.getChannelSubscribersSnapshot(dest);

        for (Map.Entry<Integer, String> e : subs.entrySet()) {
            String msg =
                    "MESSAGE\n" +
                    "destination:" + dest + "\n" +
                    "subscription:" + e.getValue() + "\n" +
                    "message-id:" + msgId + "\n\n" +
                    body;
            connections.send(e.getKey(), msg);
        }

        // Log file report to SQL
        Database.getInstance().trackFileUpload(username, "unknown-file", dest);

        maybeSendReceipt(frame);
    }

    private void handleDisconnect(Frame frame) {
        String receipt = frame.headers.get("receipt");
        if (receipt == null) {
            sendErrorAndClose(frame, "DISCONNECT must include receipt header", null, frame.raw);
            return;
        }

        // SQL logout
        Database.getInstance().logout(connectionId);

        connections.send(connectionId, "RECEIPT\nreceipt-id:" + receipt + "\n\n");
        shouldTerminate = true;
    }

    private void maybeSendReceipt(Frame frame) {
        String r = frame.headers.get("receipt");
        if (r != null) {
            connections.send(connectionId, "RECEIPT\nreceipt-id:" + r + "\n\n");
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
