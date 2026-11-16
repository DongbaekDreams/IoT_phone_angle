Carter Tierney & Jacob Cartwright  
Dr. Yingshu Li  
Internet of Things  
18 October 2026

# Phone Angle Survey and Project Design

## Introduction

Modern smartphones include an accelerometer, gyroscope, gravity sensor, and rotation vector. Those sensors make each phone a useful node for collecting motion data and training a local model. A central concern is privacy: raw motion data should stay on the device, because it can reveal sensitive information if shared. Federated learning addresses that by keeping raw samples on the phone, training locally, and exchanging only model weights and training metadata with a server.

This project has two related tracks.

**Path A — required phone-pose federated learning.** We will classify six static poses: Screen Up, Screen Down, Portrait Up, Portrait Down, Landscape Left, and Landscape Right. Training will be non-IID across two clients so neither phone sees the full problem alone:

| Client A (Class phone) | Client B (Personal phone) |
| --- | --- |
| Screen Up, Screen Down, Portrait Up | Portrait Down, Landscape Left, Landscape Right |

Each phone will also collect a light cover of all six poses for evaluation. The core question is: *can devices with different local data collaboratively learn a model that performs better than either device can learn alone?* Raw sensor data will never leave the phones.

**Path B — extra credit: sensor-based typed-word detection.** We will extend beyond static poses to recognize short typed strings from IMU motion alone (no keyboard sniffing). The intended application is a parental-control signal that flags sites parents may not want kids to visit—without reading every keystroke or logging all typed text. For the class demo, `google` and `yahoo` will be harmless placeholders for restricted/adult site names (plus `other` / `idle`) so the work stays appropriate. We will build and validate typed-word models locally first, then apply the same FedAvg workflow used for poses once both phones have compatible local typed pipelines.

**System overview.** An Android (Kotlin) app will collect labeled trials, preprocess them into features, and train lightweight on-device classifiers. Pose models will sync weight updates over a LAN server or a private GitHub repo; a privacy guard will reject any upload that looks like raw samples. Sensor ablations (which channels matter) will be our required technical contribution for the pose task. Multivariate MiniRocket plus a two-stage live detector will be the typed-word extension.

## Methodology

### Tools and architecture

| Component | Role |
| --- | --- |
| Android app (Kotlin) | Collect / Model / Sync / Results UI; live inference |
| SensorCollector + TrialRecorder | Record labeled pose or typing trials on-device |
| Preprocessor (pose) | Window trials; mean/std of gravity, accel, gyro, rotation |
| Softmax / logistic (pose) | Six-class classifier; gradient descent; FedAvg-compatible |
| MiniRocket + TypedSoftmax | Typed-word features (84 kernels × 6 IMU channels → 64 steps) and head |
| TypingActivityDetector + TypedListener | Stage A energy burst proposal; Stage B classify candidate |
| FedAvg aggregator | Weighted average of client updates by local sample count |
| LAN server or GitHub transport | Exchange weights/metadata only |
| Privacy guard | Block raw-sample payloads on every upload |

### Path A — pose model and federation

Each pose trial (≈2 s hold) will become an engineered feature vector (means and standard deviations of selected sensors). A multiclass softmax will score all six poses; the highest probability will be the prediction. Training will use gradient descent with local data only.

Federation workflow:

1. Collect labeled pose trials → preprocess → feature vector.  
2. Train locally (warm-start from the global model when available).  
3. Upload weights + sample counts (not raw samples).  
4. Server aggregates with FedAvg → new global model.  
5. Phones pull the global model → evaluate → next round.

**Technical contribution (pose):** repeatable sensor ablations — accelerometer only, gyroscope only, gravity only, gravity + rotation, accel + gyro, and all sensors — to show which channels carry static-pose signal.

### Path B — typed-word (extra credit)

**Motivation.** The goal is to help catch websites parents may not want kids to visit—without fully invading privacy by reading every keystroke or logging everything typed. Instead of inspecting text, we will ask whether short target names can be flagged from accelerometer and gyroscope patterns alone, with all sensing and inference staying on the device. For this class project, `google` and `yahoo` are harmless placeholders standing in for restricted/adult site names so the demo stays appropriate.

**Data.** Prompted typing trials of those placeholders plus distractor/`other` words; separate idle (non-typing) motion. Labels will come from the training UI only; live inference will never see text, IME events, or keyboard state.

**Features and models.** Multivariate MiniRocket will transform short 6-channel (ax…gz) windows resampled to length 64 into 84 features. We will train two local typed heads: a snipped whole-trial model (`google` / `yahoo` / `other`) and a live model that adds `idle`. Training will include time-warp and hesitation-style augmentation.

**Live pipeline (two stages).**

1. **Stage A (cheap):** detect typing-like IMU energy bursts.  
2. **Stage B (MiniRocket softmax):** run only on candidates; if confident of a target → show **Flagged**; otherwise stay silent.

**Federation plan.** After local collection, MiniRocket features, training, and holdout/event-replay evaluation work on each phone, we will apply the same FedAvg workflow used for poses and compare local A / local B / federated typed models, including cross-device and typing-speed / hand-position robustness.

### High-level flow (both paths)

```text
Path A (pose):     Collect poses → Features (mean/std) → Softmax → FedAvg rounds → Compare A / B / Global
Path B (typed):    Collect typing/idle → MiniRocket → Softmax → Live Stage A+B → FedAvg → Evaluate
Privacy (both):    Raw samples stay on phone; uploads = weights + metadata only
```

## Evaluation

### Data collection

- **Pose:** ≥3 trials of each primary pose per phone, ≥1 of each non-primary pose; Class vs Personal roles will enforce the non-IID split above.  
- **Typed-word:** multiple prompted trials of each target and `other`, plus idle recordings, on both phones where possible.

### Pose experiments (required)

We will compare three models on the same six-pose held-out and live data:

- Client A local baseline  
- Client B local baseline  
- Federated global model  

We will also run sensor ablations and cross-phone tests (each local model on the other phone’s trials). Metrics: overall accuracy, per-class accuracy, macro-F1, confusion matrices. Analysis will focus on whether federation recovers the poses each client never primarily trained on.

### Typed-word experiments (extra credit)

- Window/example holdout: accuracy, macro-F1, confusion (diagnostic).  
- Event-level replay through the real live listener: precision, recall, false alerts/min, latency.  
- Robustness: typing speed, phone angle, hand position, repeated sessions.  
- Sensor subsets where useful (accel vs gyro vs combined).  
- After typed federation: local A vs local B vs federated typed model, including cross-device transfer.

Central questions for Path B: Can short strings be told apart from IMU alone? Does multimodal sensing help? Does federation improve cross-user/device recognition? Can we demo without reading or transmitting keyboard input?

## Implementation Plan

| Period | Focus |
| --- | --- |
| October 1 – 17 | Phase 1 report; pose collect/feature/train pipeline; federation schema; start typed-word local PoC |
| October 18 – November 21 | Real pose data on both phones; multi-round FedAvg; local vs global + ablation tests; typed-word data, MiniRocket train, live Stage A+B tuning |
| November 21 – December 3 | Final pose evaluations; typed-word FedAvg and robustness checks; demo rehearsal; Phase 2 report + Sources |

**Expected outcomes**

- Pose locals strong on their three primary poses and weak on the other three; federated global beats both locals across all six.  
- Ablations: gravity/acceleration dominate static poses; gyroscope-only is weaker.  
- Typed-word: MiniRocket live flagging works for a small target set with acceptable false-alert rate; federation improves cross-device recognition vs local-only.

**Roles**

- **Carter Tierney:** Phase 1 report; data collection (pose + typed); evaluation, metrics, ablation/experiment runs.  
- **Jacob Cartwright:** Android app layout; sensor pipeline; MiniRocket / typed-word extension and live listener; federation transports.  
- **Shared:** multi-phone data collection, federated rounds, demo rehearsal, final report.
