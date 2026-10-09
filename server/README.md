# Federated Averaging server (stdlib Python only)

File-backed LAN server. It stores state in the repo using the **same layout as the GitHub transport**, so a LAN session can be committed afterwards (`git add federation results`).

## Run

```bash
python server/fedavg_server.py [--port 8080] [--root <repo root>]
```

Listens on `0.0.0.0:8080`. The Federate tab in **LAN** mode talks to it.

- Emulator → host: app default `http://10.0.2.2:8080`
- Physical phones on the same Wi-Fi: set the PC's LAN IP in **Dev tools**, e.g. `http://192.168.1.20:8080` (`ipconfig` shows it). Allow Python through the Windows firewall for private networks.

## API

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | Liveness |
| GET | `/status` | `latestGlobalRound`, `pendingRound`, `pendingClients` |
| GET | `/global` | Latest global model (or `{ "empty": true }`) |
| GET | `/baselines` | Every client's local-only baseline |
| POST | `/update` | One client's round update (weights + `sampleCount`); must be for `pendingRound` |
| POST | `/aggregate` | FedAvg the pending round → new global (409 if nothing pending) |
| POST | `/baseline` | A client's local-only baseline |
| POST | `/results` | Evaluation metrics |
| POST | `/reset` | Archive `federation/` to `federation.old-<ts>/`, restart at round 0 |

Every payload is checked against a key allowlist; anything that looks like raw sensor data (`samples`, `trial`, `gravity`, …) is rejected with 400. Updates that differ in `featureDim`, class list, `sensorConfig` or preprocessing version are rejected with 409.

## Git-side aggregation (no server)

```bash
git pull
python tools/aggregate_from_git.py            # FedAvg for latest+1
python tools/aggregate_from_git.py --commit   # ...and commit + push
```

## Tests

```bash
python -m unittest server/test_federation.py
```
