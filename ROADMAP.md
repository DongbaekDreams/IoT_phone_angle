# Roadmap

Check items off as you finish them. Order matters — finish each gate before the next.

**Source of truth for requirements:** [`CSC8223_Project2026.md`](CSC8223_Project2026.md)

---

## 0 — Sensors / foundation

- [x] Live orientation HUD (pitch / roll / yaw)
- [x] App shell: Home / Collect / Model / Sync / Results tabs + Dev tools
- [x] `SensorCollector`, `TrialRecorder`, `LocalDatasetStore`
- [x] Six poses + Class / Personal roles (course Client A / B) in the app

## 1 — Local pose classifier (one phone)

- [x] Record labeled 2s pose trials
- [x] Store trials on-device (JSON)
- [x] Preprocess / window / features
- [x] Lightweight local model + train (softmax / logistic)
- [x] Save / reload model
- [x] Live pose inference
- [x] Local evaluate (accuracy, per-class, confusion, macro-F1) on a stable held-out split
- [x] Synthetic pose data (for emulator / dry-run before real collection)

**Gate:** one phone can collect → train → save → predict → evaluate on held-out poses.

## 2 — Repeat on second phone + non-IID data

- [ ] Class phones: plenty of primary poses (screen up / down, portrait up) + light cover of all six
- [ ] Personal phones: plenty of primary poses (portrait down, landscape left / right) + light cover of all six
- [x] Same model architecture and preprocessing on both devices (compatibility checked on every update)
- [ ] 🇨🇦 Canada Class+Personal and 🇵🇪 Peru (1 or 2 phones); room tags separate from nationality

**Gate:** both phones independently train and evaluate compatible local models.

## 3 — Local experiments (required contribution)

- [x] Train/test split + reproducible metrics UI
- [x] Sensor ablations (accel / gyro / gravity / combined)
- [x] Record which config produced each result
- [x] Sensor ablation UI in Dev tools → Experiments
- [ ] Re-run ablations on **real** collected data (not only synthetic)

**Gate:** repeatable local experiments with enough metadata to reproduce.

## 4 — Typed-word extension (extra credit) — local first

- [x] ~~MiniRocket whole-word classifier~~ replaced (weak scores, ~1 s lag) — see `docs/typed_word_pipeline.md` §2
- [x] 200 Hz IMU on a background thread; in-app training keyboard records tap position + time labels
- [x] Tap detector → per-tap position model → keyword–filler word spotter (search-query scope)
- [x] 60/20/20 train/val/test by query; tap, key and query metrics on the Extra credit screen
- [x] Live flagging on typing pause (IMU only)
- [ ] Enough real queries on both phones; per-grip and cross-device results

**Gate:** both phones can train/evaluate typed-word models with no server.

## 5 — Federated pose learning

- [x] Federation protocol with two transports: LAN server and GitHub repo
- [x] Iterative rounds with warm start (Train from global → Send update → Get global)
- [x] Separate model slots: local baseline / round-local / global / peer baseline
- [x] Privacy guard on every upload (+ unit tests, `.gitignore` for tokens/exports)
- [x] In-app and laptop (`tools/aggregate_from_git.py`) aggregation
- [x] Automated round-trip tests (real server, fake GitHub API)
- [ ] LAN round trip on two physical phones
- [ ] Git round trip on two physical phones (private repo + fine-grained token)
- [ ] Compare three models on the same six-pose eval set (real data), results pushed to `results/`

**Gate:** federated global model runs; raw samples never leave the phones.

## 6 — Federate typed-word + wrap-up

- [x] Federate typed-word tap model (Git transport; scaler/calibration stay local)
- [ ] Typed federation tried on two physical phones
- [ ] Cross-device / robustness notes for demo
- [ ] Phase 1 design report (~Oct 20)
- [ ] Phase 2 report + `Sources` + in-class demo (~Dec 1–3)

---

## Data campaign (needs the phones)

- [ ] All phones installed (`./gradlew :app:installDebug`)
- [ ] Each phone claims one slot (🇨🇦/🇵🇪 × Class/Personal); locked on device, synced via Git/LAN
- [ ] Peru: use whichever phone(s) are available; unused Peru slot stays empty
- [ ] Each phone: ≥ 10 trials of each primary pose, ≥ 4 of the others
- [ ] Train local baseline on each phone; **Send update** (also publishes the baseline)
- [ ] 2+ federated rounds; Results tab → *Get peer baseline and global* → *Share my results*
- [ ] Run all ablations and the generalization experiment; copy numbers into the report

## Final checklist

- [ ] Two Android clients and a federated server
- [x] Raw sensor data remain on the phones (enforced in code + tests)
- [ ] Class and Personal phones have different local training distributions
- [ ] Two local-only models + one federated global model evaluated
- [ ] At least one meaningful technical contribution evaluated
- [ ] Report, Sources folder, README, and in-class demo complete
- [ ] Extra credit: predefined typed-string detection implemented and evaluated
