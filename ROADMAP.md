# Roadmap

Check items off as you finish them. Order matters — finish each gate before the next.

**Source of truth for requirements:** [`CSC8223_Project2026.md`](CSC8223_Project2026.md)

---

## 0 — Sensors / foundation

- [x] Live orientation HUD (pitch / roll / yaw)
- [x] Project hub screen
- [x] `SensorCollector`, `TrialRecorder`, `LocalDatasetStore`
- [x] Six poses + Client A / B roles in the app

## 1 — Local pose classifier (one phone)

- [x] Record labeled 2s pose trials
- [x] Store trials on-device (JSON)
- [x] Preprocess / window / features
- [x] Lightweight local model + train (softmax / logistic)
- [x] Save / reload model
- [x] Live pose inference
- [x] Local evaluate (accuracy, per-class, confusion, macro-F1)
- [x] Synthetic pose data (for emulator / dry-run before real collection)

**Gate:** one phone can collect → train → save → predict → evaluate on held-out poses.

## 2 — Repeat on second phone + non-IID data

- [ ] Phone A: plenty of primary poses (flat face-up/down, upright) + light cover of all six
- [ ] Phone B: plenty of primary poses (upside-down, on left/right side) + light cover of all six
- [ ] Same model architecture and preprocessing on both devices

**Gate:** both phones independently train and evaluate compatible local models.

## 3 — Local experiments (required contribution)

- [x] Train/test split + reproducible metrics UI
- [x] Sensor ablations (accel / gyro / gravity / combined)
- [x] Record which config produced each result
- [ ] Re-run ablations on **real** collected data (not only synthetic)

**Gate:** repeatable local experiments with enough metadata to reproduce.

## 4 — Typed-word extension (extra credit) — local first

- [x] Labeled typing trial recorder UI (benign targets)
- [ ] Time-series preprocess + local typed-word model
- [ ] Local infer + FP/FN metrics on each phone

**Gate:** both phones can train/evaluate typed-word models with no server.

## 5 — Federated pose learning

- [x] Federated server (FedAvg baseline) — `server/fedavg_server.py`
- [x] `FederationClient` + in-app federation UI (model updates only)
- [x] Model compare screen (Local / Peer / Global)
- [ ] Keep independent Client A / Client B local baselines (with real phones)
- [ ] Compare three models on the same six-pose eval set (real data)

**Gate:** federated global model runs; raw samples never leave the phones.

## 6 — Federate typed-word + wrap-up

- [ ] Federate typed-word models (same workflow as pose)
- [ ] Cross-device / robustness notes for demo
- [ ] Phase 1 design report (~Oct 20)
- [ ] Phase 2 report + `Sources` + in-class demo (~Dec 1–3)

---

## Final checklist

- [ ] Two Android clients and a federated server
- [ ] Raw sensor data remain on the phones
- [ ] Client A and B have different local training distributions
- [ ] Two local-only models + one federated global model evaluated
- [ ] At least one meaningful technical contribution evaluated
- [ ] Report, Sources folder, README, and in-class demo complete
- [ ] Extra credit: predefined typed-string detection implemented and evaluated
