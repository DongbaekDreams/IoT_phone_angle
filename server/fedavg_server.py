#!/usr/bin/env python3
"""LAN FedAvg server for CSC 8223 phone-pose models (stdlib only).

State lives in plain files under the repo (federation/ and results/), using the same layout
as the GitHub transport. After a session you can `git add federation results && git commit`.

Endpoints:
  GET  /health      liveness
  GET  /status      {latestGlobalRound, pendingRound, pendingClients}
  GET  /global      latest global model, or {"empty": true}
  GET  /baselines   every client's local-only baseline
  POST /update      a client's round update (weights only)
  POST /aggregate   FedAvg the pending round (idempotent)
  POST /baseline    a client's local-only baseline
  POST /results     evaluation metrics
  POST /reset       archive federation/ to federation.old-<timestamp>/ and start at round 0

Run:
  python server/fedavg_server.py [--port 8080] [--root <repo root>]
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import fedlib

LOCK = threading.Lock()
ROOT = Path(__file__).resolve().parent.parent


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def _send(self, code: int, payload: Any = None) -> None:
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8") or "{}")

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        try:
            with LOCK:
                if path == "/health":
                    self._send(200, {"ok": True})
                elif path == "/status":
                    self._send(200, fedlib.status(ROOT))
                elif path == "/global":
                    latest = fedlib.latest_round(ROOT)
                    data = fedlib.read_json(fedlib.global_path(ROOT, latest)) if latest else None
                    self._send(200, data if data else {"empty": True})
                elif path == "/baselines":
                    self._send(200, {"baselines": fedlib.list_baselines(ROOT)})
                elif path == "/bindings":
                    self._send(200, {"bindings": fedlib.list_bindings(ROOT)})
                else:
                    self._send(404, {"error": "not found"})
        except fedlib.FedError as exc:
            self._send(exc.status, {"error": str(exc)})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        try:
            data = self._read_json()
            with LOCK:
                if path == "/update":
                    self._send(200, fedlib.submit_update(ROOT, data))
                elif path == "/aggregate":
                    self._send(200, fedlib.aggregate_round(ROOT))
                elif path == "/baseline":
                    fedlib.submit_baseline(ROOT, data)
                    self._send(200, {"message": "baseline stored"})
                elif path == "/results":
                    fedlib.submit_result(ROOT, data)
                    self._send(200, {"message": "results stored"})
                elif path == "/binding":
                    fedlib.submit_binding(ROOT, data)
                    self._send(200, {"message": "binding stored"})
                elif path == "/reset":
                    src = ROOT / "federation"
                    if src.exists():
                        src.rename(ROOT / f"federation.old-{int(time.time())}")
                    self._send(200, {"message": "federation archived; round is 0"})
                else:
                    self._send(404, {"error": "not found"})
        except fedlib.FedError as exc:
            self._send(exc.status, {"error": str(exc)})
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            self._send(400, {"error": f"bad payload: {exc}"})


def main() -> None:
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--root", type=Path, default=ROOT, help="repo root that holds federation/ and results/")
    args = parser.parse_args()
    ROOT = args.root.resolve()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"FedAvg server on http://{args.host}:{args.port}  (data: {ROOT})")
    print("Phones on the same Wi-Fi should use http://<this-PC-LAN-IP>:%d" % args.port)
    server.serve_forever()


if __name__ == "__main__":
    main()
