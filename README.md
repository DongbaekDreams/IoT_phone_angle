# Federated Learning with Smartphone Sensors

CSC 8223 — six-pose phone classification, local-first training, then FedAvg. Raw sensor data stays on-device.

## Source of truth

**[`CSC8223_Project2026.md`](CSC8223_Project2026.md)** is the project plan and requirements source of truth.

**Progress checklists:** [`ROADMAP.md`](ROADMAP.md)

## Run the Android app

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** this folder (`IoT_phone_angle`).
3. Wait for Gradle sync.
4. Prefer a physical phone; emulator works for synthetic-data dry runs.
5. Click **Run**.

Hub sections: orientation → pose collect/train/infer → experiments → typed-word → federation → compare.

### Dry-run without real sensors

1. Open **Experiments & synthetic data** → **Seed synthetic pose trials**
2. **Train & evaluate** or **Run all sensor ablations**
3. **Live pose inference** (real sensors still needed for live predict)
4. Start the FedAvg server (below), then **Federation**

## Run the FedAvg server

```bash
python server/fedavg_server.py
```

See [`server/README.md`](server/README.md). Emulator default URL: `http://10.0.2.2:8080`. On a physical phone, use your PC’s LAN IP.

## Package layout

```
edu.iot.phoneangle/
  Hub + pose / experiments / typed-word / federation / compare activities
  data/          PhonePose, Trial, SyntheticPoseData
  sensors/       SensorCollector
  collection/    TrialRecorder
  store/         LocalDatasetStore, ExperimentStore
  ml/            Preprocessor, Softmax, LocalTrainer, FedAvg, ModelStore, …
  federation/    FederationClient
server/          fedavg_server.py
```

## Non-IID split

| Client A primary | Client B primary |
|------------------|------------------|
| Flat face-up / face-down, upright | Upside-down, on left/right side |

## Privacy

Trials stay under the app’s private files. Federation exchanges **model updates**, not raw samples.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```
