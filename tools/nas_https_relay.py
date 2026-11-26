#!/usr/bin/env python3
"""Relay TCP 8443 on this machine to the QNAP NAS HTTPS port 443.

The Rogers/Xfinity gateway's app only forwards one port number and requires a
destination device from its household list. The NAS is wired and therefore does
not appear in that list, so forward 8443 to this desktop instead and run this
relay. The app's NAS API URL is https://<public-host>:8443, which then reaches
QTS normally.
"""

from __future__ import annotations

import socket
import socketserver
import threading

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 8443
NAS_HOST = "10.0.0.15"
NAS_PORT = 443


def pipe(source: socket.socket, destination: socket.socket) -> None:
    try:
        while True:
            data = source.recv(64 * 1024)
            if not data:
                break
            destination.sendall(data)
    except OSError:
        pass
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except OSError:
            pass


class RelayHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        try:
            upstream = socket.create_connection((NAS_HOST, NAS_PORT), timeout=5)
        except OSError as error:
            print(f"upstream connect failed: {error}", flush=True)
            return

        to_nas = threading.Thread(target=pipe, args=(self.request, upstream), daemon=True)
        to_client = threading.Thread(target=pipe, args=(upstream, self.request), daemon=True)
        to_nas.start()
        to_client.start()
        to_nas.join()
        to_client.join()
        upstream.close()


class RelayServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with RelayServer((LISTEN_HOST, LISTEN_PORT), RelayHandler) as server:
        print(f"relay listening on {LISTEN_HOST}:{LISTEN_PORT} -> {NAS_HOST}:{NAS_PORT}", flush=True)
        server.serve_forever()
