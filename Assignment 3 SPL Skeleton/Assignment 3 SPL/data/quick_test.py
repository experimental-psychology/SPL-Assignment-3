#!/usr/bin/env python3
"""
Quick test to see if server is accepting connections
"""
import socket
import time

print("Attempting to connect to server on port 7777...")
try:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(3)
    
    result = sock.connect_ex(('127.0.0.1', 7777))
    
    if result == 0:
        print("✓ Port 7777 is OPEN")
        
        # Send CONNECT
        connect_frame = "CONNECT\naccept-version:1.2\nhost:stomp.cs.bgu.ac.il\nlogin:testuser\npasscode:testpass\n\n\x00"
        sock.sendall(connect_frame.encode())
        print("✓ Sent CONNECT frame")
        
        # Receive response
        data = sock.recv(1024)
        print(f"✓ Received response: {len(data)} bytes")
        print(f"Response content:\n{data.decode('utf-8', errors='replace')}")
        
    else:
        print(f"✗ Port 7777 is CLOSED (error code: {result})")
    
    sock.close()
    
except Exception as e:
    print(f"✗ Error: {e}")
    import traceback
    traceback.print_exc()