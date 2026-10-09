"""Round-trip tests for the LAN server and tools/aggregate_from_git.py.

Run:  python -m unittest server/test_federation.py -v   (from the repo root)
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fedavg_server  # noqa: E402
import fedlib  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
CLASSES = ["screen_up", "screen_down"]
DIM = 3


def model_payload(kind: str, client: str, rnd: int, n: int, value: float, **extra) -> dict:
    payload = {
        "schema": 1,
        "type": kind,
        "clientId": client,
        "clientRole": "CLIENT_A",
        "round": rnd,
        "sampleCount": n,
        "poseCounts": {"screen_up": n},
        "sensorConfig": "all",
        "preprocessingVersion": 1,
        "featureDim": DIM,
        "classes": CLASSES,
        "bias": [value, value],
        "weights": [[value] * DIM, [value] * DIM],
        "createdAtEpochMs": 1,
        "contributors": [],
    }
    payload.update(extra)
    return payload


class ServerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        fedavg_server.ROOT = Path(self.tmp.name)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), fedavg_server.Handler)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def call(self, method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as err:
            return err.code, json.loads(err.read() or b"{}")

    def test_two_client_rounds(self) -> None:
        self.assertEqual(self.call("GET", "/status")[1]["pendingRound"], 1)
        self.assertEqual(self.call("POST", "/update", model_payload("update", "client-a", 1, 1, 0.0))[0], 200)
        self.assertEqual(self.call("POST", "/update", model_payload("update", "client-b", 1, 3, 8.0))[0], 200)
        code, global1 = self.call("POST", "/aggregate")
        self.assertEqual(code, 200)
        self.assertEqual(global1["round"], 1)
        self.assertAlmostEqual(global1["bias"][0], 6.0)
        self.assertEqual(global1["contributors"], ["client-a", "client-b"])
        code, body = self.call("POST", "/aggregate")  # nothing pending for round 2
        self.assertEqual(code, 409, body)
        self.assertIn("already aggregated", body["error"])
        self.assertEqual(self.call("GET", "/global")[1]["round"], 1)

        # Round 2 must be warm-started against round 2; round 1 updates are now stale.
        self.assertEqual(self.call("POST", "/update", model_payload("update", "client-a", 1, 1, 0.0))[0], 409)
        self.assertEqual(self.call("POST", "/update", model_payload("update", "client-a", 2, 1, 1.0))[0], 200)
        self.assertEqual(self.call("GET", "/status")[1]["latestGlobalRound"], 1)

        # Files exist in the git layout.
        root = Path(self.tmp.name)
        self.assertTrue((root / "federation/rounds/0001/global.json").exists())
        self.assertTrue((root / "federation/rounds/0001/updates/client-a.json").exists())
        self.assertEqual(json.loads((root / "federation/latest.json").read_text())["round"], 1)

    def test_rejects_incompatible_update(self) -> None:
        self.call("POST", "/update", model_payload("update", "client-a", 1, 1, 0.0))
        code, body = self.call("POST", "/update", model_payload("update", "client-b", 1, 1, 0.0, sensorConfig="gravity_only"))
        self.assertEqual(code, 409, body)

    def test_rejects_raw_sensor_keys(self) -> None:
        bad = model_payload("update", "client-a", 1, 1, 0.0, samples=[[1, 2, 3]])
        code, body = self.call("POST", "/update", bad)
        self.assertEqual(code, 400, body)
        bad_result = {"schema": 1, "type": "result", "clientId": "client-a", "modelKind": "global", "trials": []}
        self.assertEqual(self.call("POST", "/results", bad_result)[0], 400)

    def test_baselines_and_results(self) -> None:
        self.assertEqual(self.call("POST", "/baseline", model_payload("baseline", "client-a", 0, 5, 1.0))[0], 200)
        baselines = self.call("GET", "/baselines")[1]["baselines"]
        self.assertEqual([b["clientId"] for b in baselines], ["client-a"])
        result = {
            "schema": 1, "type": "result", "clientId": "client-a", "clientRole": "CLIENT_A",
            "modelKind": "global", "round": 1, "sensorConfig": "all", "evalCount": 4,
            "accuracy": 0.75, "macroF1": 0.7,
            "perClass": [{"pose": "screen_up", "accuracy": 1.0, "f1": 1.0, "support": 2}],
            "classes": ["screen_up"], "confusion": [[2]], "sessionTags": ["room-1"], "createdAtEpochMs": 5,
        }
        self.assertEqual(self.call("POST", "/results", result)[0], 200)
        self.assertTrue((Path(self.tmp.name) / "results/client-a/5-global.json").exists())


class AggregateFromGitTest(unittest.TestCase):
    def test_script_aggregates_files_on_disk(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for client, samples, value in (("client-a", 2, 0.0), ("client-b", 2, 4.0)):
                fedlib.write_json(
                    fedlib.updates_dir(root, 1) / f"{client}.json",
                    model_payload("update", client, 1, samples, value),
                )
            proc = subprocess.run(
                [sys.executable, str(REPO / "tools" / "aggregate_from_git.py"), "--root", str(root)],
                capture_output=True, text=True,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            global1 = json.loads(fedlib.global_path(root, 1).read_text())
            self.assertAlmostEqual(global1["bias"][0], 2.0)
            self.assertEqual(fedlib.latest_round(root), 1)

            again = subprocess.run(
                [sys.executable, str(REPO / "tools" / "aggregate_from_git.py"), "--root", str(root)],
                capture_output=True, text=True,
            )
            self.assertEqual(again.returncode, 1)  # nothing for round 2 yet


if __name__ == "__main__":
    unittest.main()
