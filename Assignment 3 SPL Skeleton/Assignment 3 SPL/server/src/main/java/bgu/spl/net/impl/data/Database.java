
package bgu.spl.net.impl.data;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

public class Database {
    private final ConcurrentHashMap<String, User> userMap;
    private final ConcurrentHashMap<Integer, User> connectionsIdMap;
    private final String sqlHost;
    private final int sqlPort;

    private Database() {
        userMap = new ConcurrentHashMap<>();
        connectionsIdMap = new ConcurrentHashMap<>();
        this.sqlHost = "127.0.0.1";
        this.sqlPort = 7778;
    }

    public static Database getInstance() {
        return Instance.instance;
    }

    private String executeSQL(String sql) {
        try (Socket socket = new Socket(sqlHost, sqlPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.print(sql + '\0');
            out.flush();

            StringBuilder response = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1 && ch != '\0') {
                response.append((char) ch);
            }

            return response.toString();

        } catch (Exception e) {
            System.err.println("SQL Error: " + e.getMessage());
            return "ERROR:" + e.getMessage();
        }
    }

    private String escapeSql(String str) {
        if (str == null) return "";
        return str.replace("'", "''");
    }

    public void addUser(User user) {
        userMap.putIfAbsent(user.name, user);
        connectionsIdMap.putIfAbsent(user.getConnectionId(), user);
    }

    public LoginStatus login(int connectionId, String username, String password) {
        if (connectionsIdMap.containsKey(connectionId)) {
            return LoginStatus.CLIENT_ALREADY_CONNECTED;
        }
        if (addNewUserCase(connectionId, username, password)) {
            String sql = String.format(
                "INSERT INTO users (username, password) VALUES ('%s', '%s')",
                escapeSql(username), escapeSql(password)
            );
            executeSQL(sql);
            logLogin(username);
            return LoginStatus.ADDED_NEW_USER;
        } else {
            LoginStatus status = userExistsCase(connectionId, username, password);
            if (status == LoginStatus.LOGGED_IN_SUCCESSFULLY) {
                logLogin(username);
            }
            return status;
        }
    }

    private void logLogin(String username) {
        String sql = String.format(
            "INSERT INTO logins (username) VALUES ('%s')",
            escapeSql(username)
        );
        executeSQL(sql);
    }

    private LoginStatus userExistsCase(int connectionId, String username, String password) {
        User user = userMap.get(username);
        synchronized (user) {
            if (user.isLoggedIn()) {
                return LoginStatus.ALREADY_LOGGED_IN;
            } else if (!user.password.equals(password)) {
                return LoginStatus.WRONG_PASSWORD;
            } else {
                user.login();
                user.setConnectionId(connectionId);
                connectionsIdMap.put(connectionId, user);
                return LoginStatus.LOGGED_IN_SUCCESSFULLY;
            }
        }
    }

    private boolean addNewUserCase(int connectionId, String username, String password) {
        if (!userMap.containsKey(username)) {
            synchronized (userMap) {
                if (!userMap.containsKey(username)) {
                    User user = new User(connectionId, username, password);
                    user.login();
                    addUser(user);
                    return true;
                }
            }
        }
        return false;
    }

    public void logout(int connectionsId) {
        User user = connectionsIdMap.get(connectionsId);
        if (user != null) {
            String sql = String.format(
                "UPDATE logins SET logout_ts=datetime('now') " +
                "WHERE username='%s' AND logout_ts IS NULL " +
                "ORDER BY login_ts DESC LIMIT 1",
                escapeSql(user.name)
            );
            executeSQL(sql);

            user.logout();
            connectionsIdMap.remove(connectionsId);
        }
    }

    public void trackFileUpload(String username, String filename, String gameChannel) {
        String sql = String.format(
            "INSERT INTO reported_files (username, filename, channel) " +
            "VALUES ('%s', '%s', '%s')",
            escapeSql(username), escapeSql(filename), escapeSql(gameChannel)
        );
        executeSQL(sql);
    }

    public void printReport() {
        System.out.println(repeat("=", 80));
        System.out.println("SERVER REPORT - Generated at: " + java.time.LocalDateTime.now());
        System.out.println(repeat("=", 80));

        System.out.println("\n1. REGISTERED USERS:");
        System.out.println(repeat("-", 80));
        String usersSQL = "SELECT username, created_at FROM users ORDER BY created_at";
        System.out.println(executeSQL(usersSQL));

        System.out.println("\n2. LOGIN HISTORY:");
        System.out.println(repeat("-", 80));
        String loginSQL = "SELECT username, login_ts, logout_ts FROM logins ORDER BY username, login_ts DESC";
        System.out.println(executeSQL(loginSQL));

        System.out.println("\n3. FILE UPLOADS:");
        System.out.println(repeat("-", 80));
        String filesSQL = "SELECT username, filename, ts, channel FROM reported_files ORDER BY username, ts DESC";
        System.out.println(executeSQL(filesSQL));

        System.out.println(repeat("=", 80));
    }

    private String repeat(String str, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    private static class Instance {
        static Database instance = new Database();
    }
}
