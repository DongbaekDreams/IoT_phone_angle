#!/usr/bin/env python3
"""Minimal FedAvg server for CSC 8223 phone-pose models.

Endpoints:
  GET  /health
  GET  /global          -> current global model JSON (or empty)
  POST /update          -> client model update (weights only)
  POST /aggregate       -> FedAvg pending updates -> new global
  POST /reset           -> clear pending + global

Run:
  python server/fedavg_server.py
  # listens on 0.0.0.0:8080
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse

HOST = "0.0.0.0"
PORT = 8080

pending: dict[str, dict[str, Any]] = {}
global_model: dict[str, Any] | None = None


def fedavg(updates: list[dict[str, Any]]) -> dict[str, Any]:
    if not updates:
        raise ValueError("no updates")
    total_n = sum(max(1, int(u.get("sampleCount", 1))) for u in updates)
    first = updates[0]
    classes = first["classes"]
    feature_dim = int(first["featureDim"])
    num_classes = len(classes)
    weights = [[0.0] * feature_dim for _ in range(num_classes)]
    bias = [0.0] * num_classes
    for u in updates:
        n = max(1, int(u.get("sampleCount", 1)))
        w = n / total_n
        for c in range(num_classes):
            bias[c] += w * float(u["bias"][c])
            for f in range(feature_dim):
                weights[c][f] += w * float(u["weights"][c][f])
    return {
        "featureDim": feature_dim,
        "classes": classes,
        "bias": bias,
        "weights": weights,
        "sampleCount": total_n,
        "clientId": "global",
        "sensorConfig": first.get("sensorConfig", "all"),
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def _send(self, code: int, payload: dict[str, Any] | list[Any] | None = None) -> None:
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b"{}"
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            self._send(
                200,
                {
                    "ok": True,
                    "pendingClients": len(pending),
                    "hasGlobal": global_model is not None,
                },
            )
        elif path == "/global":
            if global_model is None:
                self._send(200, {"empty": True})
            else:
                self._send(200, global_model)
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        global global_model, pending
        path = urlparse(self.path).path
        if path == "/update":
            data = self._read_json()
            client_id = str(data.get("clientId", "unknown"))
            required = ("weights", "bias", "classes", "featureDim")
            if any(k not in data for k in required):
                self._send(400, {"error": f"missing fields, need {required}"})
                return
            pending[client_id] = data
            self._send(
                200,
                {
                    "message": "update accepted",
                    "pendingClients": len(pending),
                    "clients": list(pending.keys()),
                },
            )
        elif path == "/aggregate":
            if not pending:
                self._send(400, {"error": "no pending updates"})
                return
            try:
                global_model = fedavg(list(pending.values()))
            except Exception as exc:  # noqa: BLE001
                self._send(400, {"error": str(exc)})
                return
            pending = {}
            self._send(200, global_model)
        elif path == "/reset":
            pending = {}
            global_model = None
            self._send(200, {"message": "reset"})
        else:
            self._send(404, {"error": "not found"})


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"FedAvg server listening on http://{HOST}:{PORT}")
    print("Endpoints: GET /health /global  POST /update /aggregate /reset")
    server.serve_forever()


if __name__ == "__main__":
    main()
