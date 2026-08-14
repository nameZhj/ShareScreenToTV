import socket
import time

def setup_udp_redir():
    try:
        with open(r'C:\Users\1\.emulator_console_auth_token', 'r') as f:
            auth_token = f.read().strip()
            
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(('127.0.0.1', 5554))
        print(s.recv(1024).decode())
        
        s.sendall(f"auth {auth_token}\r\n".encode())
        print(s.recv(1024).decode())
        
        s.sendall(b"redir add udp:21001:20001\r\n")
        print(s.recv(1024).decode())
        
        s.sendall(b"redir add udp:21002:20002\r\n")
        print(s.recv(1024).decode())
        
        s.sendall(b"quit\r\n")
        s.close()
        print("UDP redir setup successful.")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == '__main__':
    setup_udp_redir()
