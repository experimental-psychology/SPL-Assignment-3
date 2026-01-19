#!/usr/bin/env python3
import socket
import time
import sys

HOST = "127.0.0.1"
PORT = 7777

def send_frame(sock, frame: str):
    # חשוב: לסיים כל frame ב-\x00
    if not frame.endswith("\x00"):
        frame += "\x00"
    print(f"\n=== SENDING ===\n{frame.replace(chr(0),'\\0')}\n===============")
    sock.sendall(frame.encode("utf-8"))
    time.sleep(0.05)

def receive_frame(sock, timeout=2.0) -> str:
    data = b""
    sock.settimeout(timeout)
    try:
        while b"\x00" not in data:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
    except socket.timeout:
        pass

    if not data:
        print("\n=== RECEIVED ===\n[TIMEOUT / no data]\n================")
        return ""

    # לוקחים עד הנול הראשון (כמו STOMP)
    msg = data.split(b"\x00", 1)[0].decode("utf-8", errors="replace")
    print(f"\n=== RECEIVED ===\n{msg}\n================")
    return msg

def build_connect(username, password) -> str:
    # host header לא חייב להיות stomp.cs.bgu..., אפשר להשאיר
    return (
        "CONNECT\n"
        "accept-version:1.2\n"
        "host:stomp.cs.bgu.ac.il\n"
        f"login:{username}\n"
        f"passcode:{password}\n"
        "\n"
    )

def build_subscribe(channel, sub_id, receipt_id) -> str:
    return (
        "SUBSCRIBE\n"
        f"destination:/{channel}\n"
        f"id:{sub_id}\n"
        f"receipt:{receipt_id}\n"
        "\n"
    )

def build_send_event(channel, username) -> str:
    # גוף הודעה דומה למה שה-assignment מצפה (שימי לב ל-":" ולשורות section)
    body = (
        f"user:{username}\n"
        "event name:goal\n"
        "time:300\n"
        "general game updates:\n"
        "score:1-0\n"
        "team a updates:\n"
        "goals:1\n"
        "team b updates:\n"
        "goals:0\n"
        "description:\n"
        "USA scores!\n"
    )
    return (
        "SEND\n"
        f"destination:/{channel}\n"
        "\n"
        f"{body}"
    )

def build_disconnect(receipt_id) -> str:
    return (
        "DISCONNECT\n"
        f"receipt:{receipt_id}\n"
        "\n"
    )

def main():
    if len(sys.argv) < 4:
        print("Usage: python3 stomp_test_client.py <username> <password> <client_id>")
        sys.exit(1)

    username = sys.argv[1]
    password = sys.argv[2]
    client_id = sys.argv[3]

    channel = "germany_japan"  # תשני למה שאת רוצה לבדוק

    print(f"Connecting to {HOST}:{PORT} as {username} (client_id={client_id})")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((HOST, PORT))

    # CONNECT
    send_frame(sock, build_connect(username, password))
    resp = receive_frame(sock)
    if "CONNECTED" not in resp:
        print("Did not receive CONNECTED. Exiting.")
        sock.close()
        return

    # SUBSCRIBE
    sub_id = f"{client_id}0"
    receipt_id = f"sub{client_id}"
    send_frame(sock, build_subscribe(channel, sub_id, receipt_id))
    receive_frame(sock)

    print(f"\n[{client_id}] Subscribed to /{channel}")
    print(f"[{client_id}] Listening... (Ctrl+C to SEND a test event)")

    try:
        while True:
            msg = receive_frame(sock, timeout=5.0)
            if "MESSAGE" in msg:
                print(f"[{client_id}] Got MESSAGE from /{channel}")
    except KeyboardInterrupt:
        print(f"\n[{client_id}] Sending a test SEND to /{channel} ...")
        send_frame(sock, build_send_event(channel, username))
        # לא תמיד יש RECEIPT ל-SEND אצלכם אלא אם הוספת receipt header ב-SEND
        receive_frame(sock, timeout=2.0)

        print(f"[{client_id}] Now listening again... (Ctrl+C to disconnect)")
        try:
            while True:
                receive_frame(sock, timeout=5.0)
        except KeyboardInterrupt:
            pass

    # DISCONNECT
    send_frame(sock, build_disconnect(f"dis{client_id}"))
    receive_frame(sock)

    sock.close()
    print(f"[{client_id}] Disconnected.")

if __name__ == "__main__":
    main()
