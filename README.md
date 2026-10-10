# Federated Learning with Smartphone Sensors

CSC 8223 — six-pose phone classification, local-first training, then FedAvg. Raw sensor data stays on-device.

## Source of truth

**[`CSC8223_Project2026.md`](CSC8223_Project2026.md)** is the project plan and requirements source of truth.

**Progress checklists:** [`ROADMAP.md`](ROADMAP.md)

## Run the Android app

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** this folder (`IoT_phone_angle`).
3. Wait for Gradle sync.
4. Prefer physical phones; an emulator works for synthetic-data dry runs.
5. Click **Run** (or `./gradlew :app:installDebug` with a phone connected).

### The five tabs

| Tab | What it does |
|-----|--------------|
| **Home** | Checklist for this phone: claim slot → connect sync → collect → train → federate → compare. Next-step CTA jumps to the right place. |
| **Collect** | **Poses** (main): tap a pose, flip during get-ready if needed, hold 2 s. **Typed words** (preview): record sensor trials while typing a target string — training/detection not built yet. |
| **Model** | Train this phone's **local baseline** (never sees the global model), held-out accuracy / confusion, live predictions. Pose only; typed mode shows a not-ready banner. |
| **Sync** | Connection (Git / LAN + URL / token + Test) and one federated round: **1 Train from global**, **2 Send update**, **3 Get new global** (+ Aggregate). |
| **Results** | Local vs peer vs global on this phone's held-out trials. Pose only while typed ML is unfinished. |

The top bar shows nationality + Class/Personal, sync mode and current global round. Flags are nationalities for privacy (who owns the phone), not rooms. **⋮ → Dev tools** holds extras (HUD, experiments, resets). Routine connection settings live on **Sync**.

### Dry-run without real sensors

1. ⋮ → Dev tools → **Experiments and synthetic data** → **Seed synthetic pose trials**
2. Model tab → **Train local baseline**
3. Sync tab → run a round (see below)

## First-time setup (each phone)

1. **Home** → claim an empty slot (🇨🇦/🇵🇪 × Class/Personal). Claiming **locks that phone forever**.
2. **Sync** → pick **Git** or **LAN**, fill token or server URL, **Test connection**.
3. **Collect** → record primary poses + light cover of the other three.
4. **Model** → train local baseline.
5. **Sync** → run federated rounds (below).
6. **Results** → pull peer baseline and compare.

Typed-word collection is optional (Collect → Typed words). Model / Sync rounds / Results stay pose-only until that pipeline exists.

## Two-phone federated round

### Identity: nationality × Class / Personal

| Nationality (who) | Class phone | Personal phone |
|-------------------|-------------|----------------|
| 🇨🇦 Canada | `canada-class` | `canada-personal` |
| 🇵🇪 Peru | `peru-class` | `peru-personal` (optional) |

Class = course Client A (screen up/down, portrait up). Personal = course Client B (portrait down, landscapes). Use Collect’s **Room / session** tag for the actual room (e.g. `dorm`, `lab`).

On each phone after claiming:

1. **Train** — pulls the newest global model (if any) and trains it further on local data (warm start).
2. **Send update** — uploads weights + counts (and your baseline, for comparison).
3. When every phone's update is in, any one phone presses **Aggregate round now**. It lists who has sent; aggregating early locks the round and phones that missed it must train again.
4. **Get global** on each phone, then repeat from step 1 for the next round.

Round *N* updates are only accepted while the federation waits for round *N*; stale updates are rejected with a clear message.

### Option 1 — LAN (phones together)

```bash
python server/fedavg_server.py
```

Set the laptop's LAN IP on the **Sync** tab (`http://192.168.x.x:8080`), choose **LAN**, and **Test connection**. Details: [`server/README.md`](server/README.md).

### Option 2 — Git (phones in different rooms)

Phones talk to the GitHub Contents API; nobody has to be online at the same time.

1. Make the repo **private** (weights are model data, keep them private unless you are fine sharing them).
2. GitHub → Settings → Developer settings → **Fine-grained tokens** → new token, *only this repository*, permission **Contents: Read and write**.
3. On each phone: **Sync** → set repo (`owner/name`) and token → **Test connection**.
4. Choose **Git** and run rounds as above.

The token is stored in app-private storage only (backups are disabled); never commit it.

Aggregation can run in the app (button) or on the laptop: `python tools/aggregate_from_git.py --commit`.

### What goes into git

```
federation/latest.json                        {"round": N}
federation/rounds/NNNN/updates/<client>.json  weights, sampleCount, per-pose trial counts, sensorConfig, ...
federation/rounds/NNNN/global.json            aggregated model
results/baselines/<client>-local.json         independent local-only model
results/bindings/<deviceHash>.json            permanent phone → slot claims
results/<client>/<timestamp>-<kind>.json      held-out metrics, confusion matrix, session tags
```

Raw trials are never uploaded.

## Package layout

```
edu.iot.phoneangle/
  MainShellActivity, DevToolsActivity, ExperimentsActivity, MainActivity (HUD)
  ui/            Home / Collect / Model / Federate(Sync) / Compare(Results), ChecklistState
  data/          PhonePose, Trial, SyntheticPoseData
  sensors/       SensorCollector
  collection/    TrialRecorder
  store/         LocalDatasetStore, ExperimentStore
  ml/            Preprocessor, Softmax, LocalTrainer, FedAvg, ModelStore (slots), PoseWorkflow, StableSplit
  federation/    FederationTransport (LanServerTransport, GitHubTransport), Payloads (PrivacyGuard), Aggregator, FederationOps
server/          fedavg_server.py, fedlib.py, test_federation.py
tools/           aggregate_from_git.py
```

## Non-IID split

| Class phone (Client A) | Personal phone (Client B) |
|------------------------|---------------------------|
| Screen up, screen down, portrait up | Portrait down, landscape left, landscape right |

Each phone also records a few trials of the other poses so every model can be evaluated on all six.

## Model slots (kept strictly separate)

- `local_baseline` — trained from scratch on this phone only.
- `round_local` — warm-started from the global model, uploaded in step 2.
- `global` — aggregated model from the federation.
- `peer_baseline` — the other client's baseline, pulled for comparison.

Evaluation always uses a stable hash-based holdout (~25% of trials), so a trial never switches between train and test as data grows.

## Privacy

Trials stay under the app's private files. Payload types can only hold weights, counts and metrics, and `PrivacyGuard` (Kotlin and Python) rejects any upload with unexpected keys or keys that look like raw sensor data. `.gitignore` excludes tokens and data exports.

## Tests

```bash
./gradlew :app:testDebugUnitTest      # includes LAN (real Python server) and GitHub (fake API) round trips
python -m unittest server/test_federation.py
```
