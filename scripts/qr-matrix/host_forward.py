#!/usr/bin/env python3
"""Expose a loopback port of this machine on its LAN address, for emulators only.

Android 17 blocks apps without the local-network permission from the subnet the phone is attached to (local network
protection). On an emulator that subnet is 10.0.2.0/24, which holds 10.0.2.2 - the address every emulator uses for
"the host". The platform's own downloader does not hold that permission, so on an Android 17 emulator the agent
download of a QR enrollment from http://10.0.2.2:<port> never connects. A phone enrolling against a server on the
internet (or on another subnet) is not affected.

The emulator reaches the host's LAN address through its NAT, and that address is outside the emulator's own subnet:

  host_forward.py 8133 8131        # <LAN address>:8133 -> 127.0.0.1:8131 ; prints the LAN address

Only connections that come from this machine itself are accepted (the emulator's NAT connects from the host), so the
dev stack is not opened to the rest of the network.
"""
import socket
import sys
import threading


def lan_address():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))   # no packet is sent: this only picks the outgoing interface
        return s.getsockname()[0]
    finally:
        s.close()


def pump(a, b):
    try:
        while True:
            data = a.recv(65536)
            if not data:
                break
            b.sendall(data)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            s.close()


def main():
    listen_port, target_port = int(sys.argv[1]), int(sys.argv[2])
    own = lan_address()
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((own, listen_port))
    server.listen(32)
    print(f"{own}:{listen_port} -> 127.0.0.1:{target_port}", flush=True)
    while True:
        client, peer = server.accept()
        if peer[0] not in (own, "127.0.0.1"):
            client.close()
            continue
        try:
            upstream = socket.create_connection(("127.0.0.1", target_port), timeout=10)
            upstream.settimeout(None)
        except OSError:
            client.close()
            continue
        threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pump, args=(upstream, client), daemon=True).start()


if __name__ == "__main__":
    main()
