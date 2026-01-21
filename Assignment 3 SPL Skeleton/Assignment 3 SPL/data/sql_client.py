#!/usr/bin/env python3
import socket
import time
import sys

def send_frame(sock, frame):
    """Send a STOMP frame"""
    print(f"\n=== SENDING ===\n{frame}\n===============")
    sock.sendall(frame.encode('utf-8'))
    time.sleep(0.1)

def receive_frame(sock):
    """Receive a STOMP frame"""
    data = b''
    sock.settimeout(2.0)
    try:
        # First read with timeout
        chunk = sock.recv(4096)
        if chunk:
            data += chunk
            # Keep reading until we get the null terminator
            while b'\x00' not in data:
                sock.settimeout(0.5)  # Shorter timeout for subsequent reads
                try:
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    data += chunk
                except socket.timeout:
                    break
    except socket.timeout:
        print("[TIMEOUT waiting for response]")
    
    response = data.decode('utf-8', errors='replace')
    print(f"\n=== RECEIVED ===\n{response}\n================")
    return response

def main():
    if len(sys.argv) < 4:
        print("Usage: python test_client.py <username> <password> <client_id>")
        sys.exit(1)
    
    username = sys.argv[1]
    password = sys.argv[2]
    client_id = sys.argv[3]
    
    print(f"\n{'='*50}")
    print(f"Starting Test Client [{client_id}] - User: {username}")
    print(f"{'='*50}")
    
    # Connect to server
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect(('localhost', 7777))
    print(f"Connected to server on localhost:7777")
    
    # CONNECT
    connect_frame = f"""CONNECT
accept-version:1.2
host:stomp.cs.bgu.ac.il
login:{username}
passcode:{password}

\x00"""
    send_frame(sock, connect_frame)
    receive_frame(sock)  # Should receive CONNECTED
    
    # SUBSCRIBE to usa_mexico
    subscribe_frame = f"""SUBSCRIBE
destination:/usa_mexico
id:{client_id}1
receipt:sub{client_id}

\x00"""
    send_frame(sock, subscribe_frame)
    receive_frame(sock)  # Should receive RECEIPT
    
    print(f"\n[{client_id}] Subscribed to /usa_mexico")
    print(f"[{client_id}] Now listening for messages... Press Ctrl+C to send a test message")
    
    # Keep receiving messages
    try:
        while True:
            response = receive_frame(sock)
            if 'MESSAGE' in response:
                print(f"[{client_id}] Got a message from the channel!")
    except KeyboardInterrupt:
        print(f"\n[{client_id}] Sending test message...")
        
        # SEND a message
        send_frame_msg = f"""SEND
destination:/usa_mexico
receipt:msg{client_id}

user: {username}
team a: usa
team b: mexico
event name: goal
time: 300
general game updates:
team a updates:
  goals: 1
team b updates:
description:
USA scores!
\x00"""
        send_frame(sock, send_frame_msg)
        receive_frame(sock)  # RECEIPT
        
        print(f"[{client_id}] Message sent! Continuing to listen...")
        
        try:
            while True:
                receive_frame(sock)
        except KeyboardInterrupt:
            pass
    
    # DISCONNECT
    print(f"\n[{client_id}] Disconnecting...")
    disconnect_frame = f"""DISCONNECT
receipt:dis{client_id}

\x00"""
    send_frame(sock, disconnect_frame)
    receive_frame(sock)  # Should receive RECEIPT
    
    sock.close()
    print(f"[{client_id}] Disconnected!")

if __name__ == '__main__':
    main()