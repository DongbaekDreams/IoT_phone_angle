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

### The four tabs

| Tab | What it does |
|-----|--------------|
| **Collect** | Tap a pose card, hold the phone that way for 2 s. Set the *Location tag* to the same short name on both phones in that room. The card highlights when the phone already matches that pose. |
| **Model** | Train this phone's **local baseline** (never sees the global model), see held-out accuracy / confusion matrix, try live predictions. |
| **Federate** | One round in three steps: **1 Train from global**, **2 Send update**, **3 Get new global**. Switch between **Git** and **LAN**. |
| **Compare** | Local vs peer vs global, all scored on this phone's held-out trials. With several peers you choose which phone's baseline to compare against (opposite role is listed first). |

The top bar shows which phone this is (🇨🇦/🇵🇪 × Class/Personal), sync mode and current global round. **⋮ → Dev tools** holds connection settings, the orientation HUD, experiments (ablations, room-generalization), typed-word trials and resets.

### Dry-run without real sensors

1. ⋮ → Dev tools → **Experiments and synthetic data** → **Seed synthetic pose trials**
2. Model tab → **Train local baseline**
3. Federate tab → run a round (see below)

## Two-phone federated round

### Layout: 🇨🇦 Canada and 🇵🇪 Peru

| Site | Class phone | Personal phone | Collect tag |
|------|-------------|----------------|-------------|
| 🇨🇦 Canada | `canada-class` | `canada-personal` | `canada` |
| 🇵🇪 Peru | `peru-class` | `peru-personal` (optional) | `peru` |

Class = course Client A (screen up/down, portrait up). Personal = course Client B (portrait down, landscapes).

Pick the phone in ⋮ → **Which phone is this?** That sets the role, the file id, and the Collect location tag. If Peru only has one phone, pick Class or Personal and leave the other unused.

Then on each phone:

1. **Train** — pulls the newest global model (if any) and trains it further on local data (warm start).
2. **Send update** — uploads weights + counts (and your baseline, for comparison).
3. When every phone's update is in, any one phone presses **Aggregate round now**. It lists who has sent; aggregating early locks the round and phones that missed it must train again.
4. **Get global** on each phone, then repeat from step 1 for the next round.

Round *N* updates are only accepted while the federation waits for round *N*; stale updates are rejected with a clear message.

### Option 1 — LAN (phones together)

```bash
python server/fedavg_server.py
```

Set the laptop's LAN IP in Dev tools (`http://192.168.x.x:8080`), choose **LAN** on the Federate tab, and use **Test connection**. Details: [`server/README.md`](server/README.md).

### Option 2 — Git (phones in different rooms)

Phones talk to the GitHub Contents API; nobody has to be online at the same time.

1. Make the repo **private** (weights are model data, keep them private unless you are fine sharing them).
2. GitHub → Settings → Developer settings → **Fine-grained tokens** → new token, *only this repository*, permission **Contents: Read and write**.
3. On each phone: Dev tools → set repo (`owner/name`), branch and token → **Test connection**.
4. Choose **Git** on the Federate tab and run rounds as above.

The token is stored in app-private storage only (backups are disabled); never commit it.

Aggregation can run in the app (button) or on the laptop: `python tools/aggregate_from_git.py --commit`.

### What goes into git

```
federation/latest.json                        {"round": N}
federation/rounds/NNNN/updates/<client>.json  weights, sampleCount, per-pose trial counts, sensorConfig, ...
federation/rounds/NNNN/global.json            aggregated model
results/baselines/<client>-local.json         independent local-only model
results/<client>/<timestamp>-<kind>.json      held-out metrics, confusion matrix, session tags
```

Raw trials are never uploaded.

## Package layout

```
edu.iot.phoneangle/
  MainShellActivity, DevToolsActivity, ExperimentsActivity, TypedWordActivity, MainActivity (HUD)
  ui/            Collect / Model / Federate / Compare fragments, ConfusionMatrixView
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
