# Federated Learning with Smartphone Sensors

CSC 8223 — six-pose phone classification, local-first training, then FedAvg. Raw sensor data stays on-device.

## Source of truth

**[`CSC8223_Project2026.md`](CSC8223_Project2026.md)** is the project plan and requirements source of truth.

**Progress checklists:** [`ROADMAP.md`](ROADMAP.md)

## Recent fixes (Carter)

1. **Non-IID split** — Class and Personal phones of the same nationality now get complementary halves of the six poses (table below); before, Personal phones trained on all six and a same-nationality pair had no real A/B split.
2. **One sensor config everywhere** — federation, the Model tab and the "save" step of the ablation run now share one setting, `PoseWorkflow.DEFAULT_SENSOR_CONFIG` (all 22 features, kept for a planned higher-precision stretch feature). Compare the other configs in Dev tools → Experiments. On phones without a gravity sensor the accelerometer stands in for the gravity features (accel ≈ gravity on a static hold). An existing global model keeps its own sensor config — start a fresh federation to change it.
3. **Live test default** — the Model tab's live test now defaults to the global model once one exists (was: always the local baseline).
4. **Holdout + readiness** — evaluation uses a stratified stable holdout (every 4th trial of each pose, by recording order) instead of a hash; readiness counts only this phone's role and usable training trials; minimums raised to 10 trials per primary pose / 4 per other pose.

Second pass:

5. **Federation reset recovery** — if the remote's round is lower than this phone's saved global (LAN `/reset`, or `federation/` deleted from the repo), the phone drops its stale global and round-local models and follows the remote. Before, it stayed stuck on "Run step 1 again" forever.
6. **No more UI freezes from typed data** — pose evaluation skips other trial types by reading only the file header (the big 200 Hz typed recordings are never parsed), and the Model / Results tabs evaluate on a background thread.
7. **Slot lock survives reinstalls** — claims also store a hash of the phone's hardware model, so a reinstalled phone, or one running a build from another computer, is recognised as the same phone and can claim its slot again. There is still no "release slot" button. Bindings made before this change have no model hash: if one blocks you, delete `results/bindings/<client>.json` from the sync repo (or the LAN server folder) and claim again.
8. **Typed-model uploads are allowlisted** — typed models are checked key by key with the same `PrivacyGuard` as pose uploads, instead of a string search.
9. **One-update aggregation warning** — aggregating with only one phone's update now shows an explicit warning that the "global" model would know only half the poses.

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
| **Collect** | Pose trials only: tap a pose, flip during get-ready if needed, hold 2 s. Typed words live under Home → Extra credit. |
| **Model** | Train the pose baseline (default: all 22 features), held-out metrics, live test — uses the global model once one exists. |
| **Sync** | Connection (Git / LAN + URL / token + Test) and one federated round: **1 Train from global**, **2 Send update**, **3 Get new global** (+ Aggregate). |
| **Results** | Local vs peer vs global on this phone's held-out trials. Pose only. |

The top bar shows nationality + Class/Personal, sync mode and current global round. Flags are nationalities for privacy (who owns the phone), not rooms. **⋮ → Dev tools** holds extras (HUD, experiments, resets). Routine connection settings live on **Sync**.

### Dry-run without real sensors

1. ⋮ → Dev tools → **Experiments and synthetic data** → **Seed synthetic pose trials**
2. Model tab → **Train local baseline**
3. Sync tab → run a round (see below)

## First-time setup (each phone)

1. **Home** → claim an empty slot (🇨🇦/🇵🇪 × Class/Personal). Claiming **locks that phone forever**.
2. **Sync** → pick **Git** or **LAN**, fill token or server URL, **Test connection**.
3. **Collect** → record ≥10 trials of each ★ primary pose and ≥4 of each other pose.
4. **Model** → train local baseline.
5. **Sync** → run federated rounds (below).
6. **Results** → pull peer baseline and compare.

### Typed words (extra credit, local)

Home → **Extra credit** (details: [`docs/typed_word_pipeline.md`](docs/typed_word_pipeline.md)).

1. **Search queries** → type each prompt on the training keyboard and tap search (≥20 queries; aim for ~20 google, ~20 yahoo, ~60 other). Set your grip in the top right.
2. **Record idle** a few times (no typing) so false taps can be measured.
3. **Train tap model** → held-out tap detection, key/row accuracy, query confusion matrix.
4. **Live flagging** → type in the search box on your normal keyboard; pause, and google/yahoo get **Flagged**.

The typed tap model can be federated from Extra credit → **Federate**, Git transport only (LAN not supported). Phones upload weights/statistics only to `federation/typed/<client>.json`; the average is weighted by training taps, and each phone keeps its own scaling, calibration and thresholds. Not yet tried on two physical phones.

## Two-phone federated round

### Identity: nationality × Class / Personal

| Nationality (who) | Class phone | Personal phone |
|-------------------|-------------|----------------|
| 🇨🇦 Canada | `canada-class` | `canada-personal` |
| 🇵🇪 Peru | `peru-class` | `peru-personal` (optional) |

Class = course Client A, Personal = course Client B — but pose sets depend on nationality too (see Non-IID split below).

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
federation/typed/<client>.json                typed-word tap model (weights/statistics only)
results/baselines/<client>-local.json         independent local-only model
results/bindings/<client>.json                permanent phone → slot claims (install key + hardware-model hash)
results/<client>/<timestamp>-<kind>.json      held-out metrics, confusion matrix
```

Raw trials are never uploaded.

## Package layout

```
edu.iot.phoneangle/
  MainShellActivity, DevToolsActivity, ExperimentsActivity, MainActivity (HUD)
  ExtraCreditActivity, TypedPlayActivity, TypedWordActivity, TypedListenActivity, IdentityBinding
  ui/            Home / Collect / Model / Federate(Sync) / Compare(Results), ChecklistState
  data/          PhonePose, Trial, SyntheticPoseData
  sensors/       SensorCollector
  collection/    TrialRecorder
  store/         LocalDatasetStore, ExperimentStore
  ml/            Preprocessor, Softmax, LocalTrainer, FedAvg, ModelStore (slots), PoseWorkflow, StableSplit
  ml/typed/      tap detector, position model, word spotter, TypedFederation
  federation/    FederationTransport (LanServerTransport, GitHubTransport), Payloads (PrivacyGuard), Aggregator, FederationOps
server/          fedavg_server.py, fedlib.py, test_federation.py
tools/           aggregate_from_git.py
```

## Non-IID split

| Slot | Training poses (★ primary) |
|------|----------------------------|
| canada-class | Screen up, screen down, portrait up |
| canada-personal | Portrait down, landscape left, landscape right |
| peru-class | Portrait down, landscape left, landscape right |
| peru-personal | Screen up, screen down, portrait up |

Each phone also records a few trials of the other poses so every model can be evaluated on all six. Any same-nationality pair, the two class phones, or all four phones together cover all six poses; canada-class + peru-personal (or canada-personal + peru-class) would duplicate the same half.

## Model slots (kept strictly separate)

- `local_baseline` — trained from scratch on this phone only.
- `round_local` — warm-started from the global model, uploaded in step 2.
- `global` — aggregated model from the federation.
- `peer_baseline` — the other client's baseline, pulled for comparison.

Evaluation always uses a stratified stable holdout — every 4th trial of each pose by recording order — so a trial never switches between train and test as data grows.

## Privacy

Trials stay under the app's private files. Payload types can only hold weights, counts and metrics, and `PrivacyGuard` (Kotlin and Python) rejects any upload with unexpected keys or keys that look like raw sensor data. `.gitignore` excludes tokens and data exports.

## Tests

```bash
./gradlew :app:testDebugUnitTest      # includes LAN (real Python server) and GitHub (fake API) round trips
python -m unittest server/test_federation.py
```

Results so far are from unit tests and synthetic data; two-phone runs on real collected data are still to do.
