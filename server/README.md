# Federated Averaging server (stdlib Python only)

## Run

```bash
python server/fedavg_server.py
```

Listens on `0.0.0.0:8080`.

- Emulator → host: use app default `http://10.0.2.2:8080`
- Physical phone: set the PC’s LAN IP, e.g. `http://192.168.1.20:8080`

## API

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/health` | Liveness + pending client count |
| GET | `/global` | Current global model (or `{ "empty": true }`) |
| POST | `/update` | Submit one client’s weights + `sampleCount` |
| POST | `/aggregate` | FedAvg all pending updates → new global |
| POST | `/reset` | Clear pending + global |

Clients send **model updates only** — never raw sensor trials.
