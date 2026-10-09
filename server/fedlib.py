"""Shared federation helpers (stdlib only).

Both the LAN server and tools/aggregate_from_git.py use the same on-disk layout as the
phone's GitHub transport, so LAN and Git federation produce identical, versionable files:

  federation/latest.json                        {"round": N}
  federation/rounds/NNNN/updates/<client>.json  one file per client per round
  federation/rounds/NNNN/global.json            aggregate of that round
  results/baselines/<client>-local.json         independent local-only models
  results/<client>/<timestamp>-<kind>.json      evaluation metrics

Only weights, counts, and metrics are ever stored - raw sensor data never reaches this code.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any

SCHEMA = 1

MODEL_KEYS = {
    "schema", "type", "clientId", "clientRole", "round", "sampleCount", "poseCounts",
    "sensorConfig", "preprocessingVersion", "featureDim", "classes", "bias", "weights",
    "createdAtEpochMs", "contributors", "testAccuracy",
}
RESULT_KEYS = {
    "schema", "type", "clientId", "clientRole", "modelKind", "round", "sensorConfig",
    "evalCount", "accuracy", "macroF1", "perClass", "classes", "confusion", "sessionTags",
    "createdAtEpochMs",
}
FORBIDDEN_KEYS = {
    "samples", "sample", "trial", "trials", "timestampns",
    "accel", "gyro", "gravity", "rotationvector", "features", "examples",
}


class FedError(Exception):
    """Client-visible error; `status` is the HTTP code the server should answer with."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def check_keys(obj: dict[str, Any], allowed: set[str]) -> None:
    unexpected = [k for k in obj if k not in allowed]
    if unexpected:
        raise FedError(f"non-allowlisted keys: {unexpected}")
    forbidden = [k for k in obj if k.lower() in FORBIDDEN_KEYS]
    if forbidden:
        raise FedError(f"keys look like raw sensor data: {forbidden}")


def safe_name(raw: str) -> str:
    name = "".join(c if (c.isalnum() or c in "-_") else "-" for c in str(raw).lower()).strip("-")
    if not name:
        raise FedError("empty client id")
    return name


def read_json(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, indent=2), encoding="utf-8")
    os.replace(tmp, path)


def round_dir(root: Path, r: int) -> Path:
    return root / "federation" / "rounds" / f"{r:04d}"


def updates_dir(root: Path, r: int) -> Path:
    return round_dir(root, r) / "updates"


def global_path(root: Path, r: int) -> Path:
    return round_dir(root, r) / "global.json"


def latest_round(root: Path) -> int:
    data = read_json(root / "federation" / "latest.json")
    return int(data["round"]) if data else 0


def write_latest(root: Path, r: int) -> None:
    write_json(root / "federation" / "latest.json", {"round": r, "updatedAtEpochMs": int(time.time() * 1000)})


def list_update_files(root: Path, r: int) -> list[Path]:
    d = updates_dir(root, r)
    return sorted(d.glob("*.json")) if d.exists() else []


def read_updates(root: Path, r: int) -> list[dict[str, Any]]:
    return [json.loads(p.read_text(encoding="utf-8")) for p in list_update_files(root, r)]


def compat_problem(ref: dict[str, Any], other: dict[str, Any]) -> str | None:
    if ref["featureDim"] != other["featureDim"]:
        return f"featureDim {other['featureDim']} != {ref['featureDim']}"
    if ref["classes"] != other["classes"]:
        return "class list differs"
    if ref.get("sensorConfig") != other.get("sensorConfig"):
        return f"sensorConfig {other.get('sensorConfig')} != {ref.get('sensorConfig')}"
    if ref.get("preprocessingVersion", 1) != other.get("preprocessingVersion", 1):
        return "preprocessingVersion differs"
    return None


def fedavg(updates: list[dict[str, Any]], round_no: int) -> dict[str, Any]:
    """Sample-count-weighted average of compatible client updates."""
    if not updates:
        raise FedError("no updates to aggregate")
    ref = updates[0]
    for u in updates:
        if u.get("type") != "update":
            raise FedError(f"{u.get('clientId')}: not an update payload")
        if int(u["round"]) != round_no:
            raise FedError(f"{u['clientId']}: update is for round {u['round']}, expected {round_no}")
        problem = compat_problem(ref, u)
        if problem:
            raise FedError(f"{u['clientId']}: incompatible update ({problem})")

    total = sum(max(1, int(u["sampleCount"])) for u in updates)
    classes = ref["classes"]
    feature_dim = int(ref["featureDim"])
    weights = [[0.0] * feature_dim for _ in classes]
    bias = [0.0] * len(classes)
    for u in updates:
        w = max(1, int(u["sampleCount"])) / total
        for c in range(len(classes)):
            bias[c] += w * float(u["bias"][c])
            for f in range(feature_dim):
                weights[c][f] += w * float(u["weights"][c][f])

    pose_counts: dict[str, int] = {}
    for u in updates:
        for k, v in (u.get("poseCounts") or {}).items():
            pose_counts[k] = pose_counts.get(k, 0) + int(v)

    return {
        "schema": SCHEMA,
        "type": "global",
        "clientId": "global",
        "clientRole": "",
        "round": round_no,
        "sampleCount": sum(int(u["sampleCount"]) for u in updates),
        "poseCounts": pose_counts,
        "sensorConfig": ref.get("sensorConfig", "all"),
        "preprocessingVersion": ref.get("preprocessingVersion", 1),
        "featureDim": feature_dim,
        "classes": classes,
        "bias": bias,
        "weights": weights,
        "createdAtEpochMs": int(time.time() * 1000),
        "contributors": sorted(str(u["clientId"]) for u in updates),
    }


def aggregate_round(root: Path, round_no: int | None = None) -> dict[str, Any]:
    """Aggregate the pending round (default latest+1). Idempotent if already aggregated."""
    latest = latest_round(root)
    target = round_no if round_no is not None else latest + 1
    existing = read_json(global_path(root, target))
    if existing is not None:
        if target > latest:
            write_latest(root, target)
        return existing
    updates = read_updates(root, target)
    if not updates:
        hint = f" (round {latest} is already aggregated)" if latest else ""
        raise FedError(f"no updates for round {target} yet{hint}", status=409)
    global_model = fedavg(updates, target)
    write_json(global_path(root, target), global_model)
    write_latest(root, target)
    return global_model


def submit_update(root: Path, payload: dict[str, Any]) -> dict[str, Any]:
    check_keys(payload, MODEL_KEYS)
    if payload.get("type") != "update":
        raise FedError("type must be 'update'")
    client = safe_name(payload["clientId"])
    latest = latest_round(root)
    pending = latest + 1
    if int(payload["round"]) != pending:
        raise FedError(
            f"server is waiting for round {pending}, update is for round {payload['round']}; "
            "fetch the latest global and train again",
            status=409,
        )
    existing = read_updates(root, pending)
    others = [u for u in existing if safe_name(u["clientId"]) != client]
    if others:
        problem = compat_problem(others[0], payload)
        if problem:
            raise FedError(f"incompatible with other updates this round ({problem})", status=409)
    write_json(updates_dir(root, pending) / f"{client}.json", payload)
    return {"message": "update accepted", "pendingRound": pending, "pendingClients": len(others) + 1}


def submit_baseline(root: Path, payload: dict[str, Any]) -> None:
    check_keys(payload, MODEL_KEYS)
    if payload.get("type") != "baseline":
        raise FedError("type must be 'baseline'")
    write_json(root / "results" / "baselines" / f"{safe_name(payload['clientId'])}-local.json", payload)


def list_baselines(root: Path) -> list[dict[str, Any]]:
    d = root / "results" / "baselines"
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(d.glob("*-local.json"))] if d.exists() else []


def submit_result(root: Path, payload: dict[str, Any]) -> None:
    check_keys(payload, RESULT_KEYS)
    for entry in payload.get("perClass", []):
        check_keys(entry, {"pose", "accuracy", "f1", "support"})
    client = safe_name(payload["clientId"])
    kind = safe_name(payload.get("modelKind", "result"))
    stamp = int(payload.get("createdAtEpochMs") or time.time() * 1000)
    write_json(root / "results" / client / f"{stamp}-{kind}.json", payload)


def status(root: Path) -> dict[str, Any]:
    latest = latest_round(root)
    pending = [p.stem for p in list_update_files(root, latest + 1)]
    return {"latestGlobalRound": latest, "pendingRound": latest + 1, "pendingClients": pending}
