#!/usr/bin/env python3
"""Simulates, in front of a DallyControl server, the two things corporate networks do that break the agent.

  tls   TLS inspection: answers on https with a certificate issued by a made-up firewall authority
        ("FortiGate CA simulada") that no device trusts, and relays the traffic to the server.
  nows  WebSocket blocked: relays plain HTTP but answers 403 to any Upgrade request.

Used by scripts/conexion-e2e.py to check that the agent reports the exact cause and the console shows it, and to
try scripts/prueba-red.sh against a network that fails. Test tool only: never put it in front of real devices.

  scripts/red-simulada.py tls  --listen 8443 --upstream localhost:8088
  scripts/red-simulada.py nows --listen 8122 --upstream localhost:8088
"""
import argparse
import os
import socket
import ssl
import subprocess
import tempfile
import threading

ISSUER = "/C=CO/O=Fortinet (simulado)/CN=FortiGate CA simulada"
FORBIDDEN = (b"HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\nContent-Length: 34\r\nConnection: close\r\n\r\n"
             b"WebSocket bloqueado por la red.\r\n\r\n")


def make_cert(directory):
    """A leaf for localhost/10.0.2.2 signed by the made-up authority. Returns (cert chain file, key file)."""
    def run(*a):
        subprocess.run(a, check=True, capture_output=True)
    ca_key, ca, key, csr, crt, ext, chain = (os.path.join(directory, n) for n in
                                             ("ca.key", "ca.crt", "leaf.key", "leaf.csr", "leaf.crt", "ext.cnf", "chain.crt"))
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-keyout", ca_key, "-out", ca,
        "-subj", ISSUER)
    run("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", csr, "-subj", "/CN=10.0.2.2")
    with open(ext, "w") as f:
        f.write("subjectAltName=IP:10.0.2.2,IP:127.0.0.1,DNS:localhost\n")
    run("openssl", "x509", "-req", "-in", csr, "-CA", ca, "-CAkey", ca_key, "-CAcreateserial", "-days", "2",
        "-out", crt, "-extfile", ext)
    with open(chain, "w") as out:
        out.write(open(crt).read() + open(ca).read())
    return chain, key


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def read_head(conn):
    data = b""
    while b"\r\n\r\n" not in data and len(data) < 65536:
        chunk = conn.recv(4096)
        if not chunk:
            break
        data += chunk
    return data


def handle(conn, mode, upstream, context):
    try:
        if context is not None:
            conn = context.wrap_socket(conn, server_side=True)
        conn.settimeout(120)
        head = read_head(conn)
        if not head:
            return
        if mode == "nows" and b"upgrade: websocket" in head.lower():
            conn.sendall(FORBIDDEN)
            return
        up = socket.create_connection(upstream, timeout=30)
        up.settimeout(None)
        conn.settimeout(None)
        if mode == "nows":
            # One request per connection, so every later request is inspected too.
            head = head.replace(b"\r\n\r\n", b"\r\nConnection: close\r\n\r\n", 1) if b"connection:" not in head.lower() else head
        up.sendall(head)
        threading.Thread(target=pipe, args=(up, conn), daemon=True).start()
        pipe(conn, up)
    except (OSError, ssl.SSLError):
        pass  # a client that refuses our certificate hangs up during the handshake: that is the point
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main():
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["tls", "nows"])
    p.add_argument("--listen", type=int, required=True)
    p.add_argument("--upstream", default="localhost:8088")
    a = p.parse_args()
    host, port = a.upstream.rsplit(":", 1)
    context = None
    if a.mode == "tls":
        chain, key = make_cert(tempfile.mkdtemp(prefix="red-simulada-"))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(chain, key)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", a.listen))
    srv.listen(64)
    print(f"red simulada ({a.mode}) en 127.0.0.1:{a.listen} -> {a.upstream}", flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handle, args=(conn, a.mode, (host, int(port)), context), daemon=True).start()


if __name__ == "__main__":
    main()
