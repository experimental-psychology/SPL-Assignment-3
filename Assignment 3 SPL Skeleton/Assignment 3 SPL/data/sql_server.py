#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3
DO NOT CHANGE the server name or the basic protocol.
"""

import socket
import sys
import threading
import sqlite3

SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!

_db_lock = threading.Lock()

def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            return ""
        data += chunk
        if b"\0" in data:
            msg, _ = data.split(b"\0", 1)
            return msg.decode("utf-8", errors="replace")

def send_null_terminated(sock: socket.socket, s: str):
    sock.sendall(s.encode("utf-8") + b"\0")

def init_database():
    with sqlite3.connect(DB_FILE) as conn:
        cur = conn.cursor()
        cur.execute("""
        CREATE TABLE IF NOT EXISTS users (
            username TEXT PRIMARY KEY,
            password TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
        """)
        cur.execute("""
        CREATE TABLE IF NOT EXISTS logins (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL,
            login_ts TEXT DEFAULT CURRENT_TIMESTAMP,
            logout_ts TEXT,
            FOREIGN KEY(username) REFERENCES users(username)
        )
        """)
        cur.execute("""
        CREATE TABLE IF NOT EXISTS reported_files (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL,
            filename TEXT NOT NULL,
            channel TEXT,
            ts TEXT DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY(username) REFERENCES users(username)
        )
        """)
        conn.commit()

def is_query(sql: str) -> bool:
    s = sql.strip().upper()
    return s.startswith("SELECT") or s.startswith("PRAGMA") or s.startswith("WITH")

def query_to_tsv(cur) -> str:
    cols = [d[0] for d in cur.description] if cur.description else []
    rows = cur.fetchall()
    out = []
    out.append("\t".join(cols))
    for r in rows:
        out.append("\t".join("" if v is None else str(v) for v in r))
    return "\n".join(out)

def execute_sql(sql: str) -> str:
    # מריצים הכל תחת lock כדי שלא יהיו התנגשויות קובץ (SQLite + threads)
    with _db_lock:
        with sqlite3.connect(DB_FILE) as conn:
            cur = conn.cursor()
            if is_query(sql):
                cur.execute(sql)
                return query_to_tsv(cur)
            else:
                cur.execute(sql)
                conn.commit()
                return "done"

def handle_client(client_socket: socket.socket, addr):
    print(f"[{SERVER_NAME}] Client connected from {addr}")
    try:
        while True:
            message = recv_null_terminated(client_socket)
            if message == "":
                break

            print(f"[{SERVER_NAME}] Received:\n{message}")

            try:
                result = execute_sql(message)
            except Exception as e:
                result = f"error\t{type(e).__name__}\t{e}"

            send_null_terminated(client_socket, result)

    except Exception as e:
        print(f"[{SERVER_NAME}] Error handling client {addr}: {e}")
    finally:
        try:
            client_socket.close()
        except Exception:
            pass
        print(f"[{SERVER_NAME}] Client {addr} disconnected")

def start_server(host="127.0.0.1", port=7778):
    init_database()

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Server started on {host}:{port}")
        print(f"[{SERVER_NAME}] Waiting for connections...")

        while True:
            client_socket, addr = server_socket.accept()
            t = threading.Thread(target=handle_client, args=(client_socket, addr), daemon=True)
            t.start()

    except KeyboardInterrupt:
        print(f"\n[{SERVER_NAME}] Shutting down server...")
    finally:
        try:
            server_socket.close()
        except Exception:
            pass

if __name__ == "__main__":
    port = 7778
    if len(sys.argv) > 1:
        raw_port = sys.argv[1].strip()
        try:
            port = int(raw_port)
        except ValueError:
            print(f"Invalid port '{raw_port}', falling back to default {port}")

    start_server(port=port)
