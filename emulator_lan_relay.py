"""
emulator_lan_relay.py
=====================
Bridges real-device LAN traffic into the Android Emulator.

Architecture:
  Phone  ──WiFi──►  PC:20000/20001/20002  ──relay──►  PC:21000/21001/21002  ──adb-fwd──►  Emulator:20000/20001/20002

ADB forward is pre-configured:
  adb forward tcp:21000 tcp:20000
  adb forward tcp:21001 tcp:20001
  adb forward tcp:21002 tcp:20002

This avoids port-conflict with the emulator's own redir system.
"""

import socket
import threading
import sys
import time
import subprocess

DEST_IP = '127.0.0.1'

PORT_MAP = [
    # (listen_port_on_PC, adb_forward_port, proto)
    (20000, 21000, 'tcp'),   # Control
    (20001, 21001, 'udp'),   # Video
    (20002, 21002, 'udp'),   # Audio
    (20003, 21003, 'tcp'),   # File Transfer
]

def setup_adb_forward():
    print("[Setup] Configuring ADB port forwarding...")
    try:
        for (_, adb_port, _) in PORT_MAP:
            inner_port = adb_port - 1000   # original emulator port
            adb_path = r"D:\Androidsdk\platform-tools\adb.exe"
            cmd = f'"{adb_path}" -s emulator-5554 forward tcp:{adb_port} tcp:{inner_port}'
            result = subprocess.run(cmd, capture_output=True, text=True, shell=True)
            if result.returncode == 0:
                print(f"  [OK] host:{adb_port} -> emulator:{inner_port}")
            else:
                print(f"  [WARN] {cmd} => {result.stderr.strip()}")
        print("[Setup] ADB forwarding complete.\n")
    except Exception as e:
        print(f"[Setup] ADB setup error: {e}\n")

def forward_tcp(listen_port, dest_port):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(('0.0.0.0', listen_port))
    except OSError as e:
        print(f"[TCP:{listen_port}] Bind failed: {e}  — is another process using this port?")
        return
    server.listen(5)
    print(f"[TCP] 0.0.0.0:{listen_port} -> {DEST_IP}:{dest_port}")

    def handle_client(client_sock):
        remote_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            remote_sock.connect((DEST_IP, dest_port))
        except Exception as e:
            print(f"[TCP:{listen_port}] Can't reach emulator on {dest_port}: {e}")
            client_sock.close()
            return

        def pipe(src, dst, label):
            try:
                while True:
                    data = src.recv(8192)
                    if not data:
                        break
                    dst.sendall(data)
            except Exception:
                pass
            src.close()
            dst.close()

        print(f"[TCP:{listen_port}] Phone connected!")
        threading.Thread(target=pipe, args=(client_sock, remote_sock, "phone->emu"), daemon=True).start()
        threading.Thread(target=pipe, args=(remote_sock, client_sock, "emu->phone"), daemon=True).start()

    while True:
        try:
            client, addr = server.accept()
            threading.Thread(target=handle_client, args=(client,), daemon=True).start()
        except Exception:
            break

def forward_udp(listen_port, dest_port):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2 * 1024 * 1024)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 2 * 1024 * 1024)
    try:
        sock.bind(('0.0.0.0', listen_port))
    except OSError as e:
        print(f"[UDP:{listen_port}] Bind failed: {e}")
        return
    print(f"[UDP] 0.0.0.0:{listen_port} -> {DEST_IP}:{dest_port}")

    pkt_count = 0
    while True:
        try:
            data, addr = sock.recvfrom(65535)
            sock.sendto(data, (DEST_IP, dest_port))
            pkt_count += 1
            if pkt_count % 300 == 1:
                print(f"[UDP:{listen_port}] Relayed {pkt_count} packets from {addr[0]}")
        except Exception as e:
            print(f"[UDP:{listen_port}] Error: {e}")
            break

if __name__ == '__main__':
    print("=" * 50)
    print("  ShareScreen  LAN → Emulator Relay")
    print("=" * 50)
    setup_adb_forward()

    for (listen_port, adb_port, proto) in PORT_MAP:
        if proto == 'tcp':
            threading.Thread(target=forward_tcp, args=(listen_port, adb_port), daemon=True).start()
        else:
            threading.Thread(target=forward_udp, args=(listen_port, adb_port), daemon=True).start()

    print("\n[Ready] Relay is running. Phone should connect to your PC's LAN IP.")
    print("        Press Ctrl+C to stop.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("Stopped.")
        sys.exit(0)
