# Typed-Word Detection (Extra Credit): Process and Design

Project: CSC8223 IoT Android app. Everything here is the **extra credit** track. The
class-project baseline is the **phone-angle (pose) classifier with federated learning**.
It is separate and untouched. Typed-word models are local only and are not federated.

## 1. Goal (corrected)

Intended eventual application: an on-device parental-safety filter that can **flag** a small
set of prohibited website names from IMU motion alone — without inspecting keyboard input,
capturing typed text, or transmitting private data.

For this university project, **`google` and `yahoo` are harmless stand-ins**.

We are **not** doing speech recognition, sentence recognition, text reconstruction, or
continuous transcription. Desired live behavior:

1. Monitor accelerometer + gyroscope.
2. Propose short candidate typing sequences from IMU energy (Stage A).
3. Classify the candidate (Stage B): `google` / `yahoo` / `other` / `idle`.
4. If confident a target was typed → show **Flagged**. Otherwise remain silent.

Do not wait for a complete sentence or a spacebar. Prefer accuracy / low false positives
over unsupported early predictions.

## 2. Privacy and project constraints

Live detection uses **only** `ax,ay,az,gx,gy,gz`.

Forbidden as model input or live gate: text, keyboard events, IME callbacks, key identities,
key timestamps, keyboard visibility.

Training UI may still provide ground-truth labels (prompted words / feed timeline).

All sensor data, training, and inference stay on device. No upload of motion, typed text,
or derived keystroke information.

Phone-angle / FedAvg path unchanged. Snipped-word demo kept. Typed models not federated.

## 3. Modes (separate app features)

| Mode | Screen | Purpose |
|---|---|---|
| Prompted words (Play) | Extra credit → Prompted words | One word at a time; primary collection for snipped + live training. |
| Idle recording | Extra credit → Idle | Non-typing phone motion. |
| Live flagging | Extra credit → Live flagging | Event-driven IMU monitor; shows **Flagged**. |

(A separate continuous / search-bar feed mode was removed — it duplicated Play.)

Entry: Home “Extra credit” card or overflow menu → `ExtraCreditActivity`. Pose Collect/Model/Sync/Results stay pose-only.

## 4. Two-stage live pipeline

### Stage A — `TypingActivityDetector` (cheap)

- Ring history of gyro magnitude + high-pass accelerometer energy.
- Adaptive threshold from recent median / p85 (tolerant of weak touchscreen motion).
- Proposes a candidate after ≥ ~280 ms activity followed by ~320 ms quiet (or max ~1.8 s).
- Must not assume every peak is a keystroke.
- Tuned for **high proposal recall**; Stage B rejects non-targets.

### Stage B — MiniRocket Softmax (only on candidates)

- On a completed burst, take samples in `[startNs, endNs]` and **index-resample to 64**
  (`channelsFromSpan`) — the **same feature path as the snipped / Play model**.
- Progressive prefixes use the same span resample from burst start (not a trailing pad).
- Training adds time-warp (≈0.75–1.35×) and mid-burst hesitation (typo/backspace proxy).
- Play’s “Typo” button **saves** the trial (labeled as the prompt) instead of discarding it.
- Stage A quiet gap ≈ 550 ms so hesitations stay in one burst.
- Early flag only if unusually confident; completed-burst flag ≥ 0.55 with margin.
- One flag per burst; a new independent burst may flag again after quiet.

While Stage A reports idle, Stage B is **skipped** (CPU saving). Stale inference jobs are dropped (`AtomicBoolean` busy flag) rather than queued.

## 5. Features and model size

- Channels: 6 (ax…gz). Input length: 64.
- MiniRocket: 84 kernels (C(9,3)), seed 8223, one bias per kernel → **84 features**.
- Softmax head: 4 classes × 84 weights + bias ≈ **few KB** JSON (`typed_live_local.json`).
- Snipped model unchanged: 3 classes, whole-trial resample (`typed_local.json`).
- Feature contract version: `TypedPreprocessor.VERSION = 7` (burst+whole snipped examples, hard negatives, aug; retrain required).
- **168 features (2 biases/kernel)** investigated conceptually; not enabled — keep 84 until
  device benchmarks show a clear gain without material slowdown.

Optimizations: in-place `transformInto` / scaler buffers, reused channel scratch arrays,
convolution inner loops tightened, model load + inference off UI thread.

## 6. Training data

Focus on short realistic typing events (no sentence-template generator, no
type-type-type chat rhythm):

- Independent `google` / `yahoo` / other short words (Play), including surrounding motion in each trial.
- Targets alone and among distractors; idle / non-typing motion.
- Progressive **prefixes** of target bursts (and hard-negative other words) so early
  checkpoints match training.

Session integrity: train/val/test assign **whole trial ids**. Overlapping examples from one
recording never cross sets. Biases, scaler, and weights fit on train only.

## 7. Evaluation

- Window / example holdout metrics (accuracy, macro F1, confusion) — diagnostic only.
- **Event-level replay** (`TypedReplayEval`): feeds recorded samples through the real listener
  with labels withheld from inference. Reports precision, recall, false alerts/min, latency,
  Stage B run count, mean inference ms.
- Synthetic unit tests prove plumbing (phase, idle skip, back-to-back). They are **not**
  proof of real-world word recognition.

## 8. Live UI

Minimal: Monitoring status, optional debug confidence, prominent **Flagged** banner.
No reconstructed text. Keyboard is a demo prop only.

## 9. Android system-wide feasibility (separate from class demo)

Intended eventual use: monitor typing while another app is open.

- **IMU via foreground service:** Android allows continuous accelerometer/gyroscope from a
  properly declared, user-visible foreground service (`FOREGROUND_SERVICE` /
  `FOREGROUND_SERVICE_HEALTH` or `SPECIAL_USE` depending on OEM/API). The user must grant
  notification visibility; background limits and OEM battery killers still apply.
- **No Accessibility / keyboard sniffing:** we deliberately do not read other apps’ text.
  Without a foreground service + notification, reliable background IMU is not permitted.
- **Class demo scope:** in-app IMU-only monitoring is sufficient. System-wide monitoring is
  feasible in principle with a foreground service, but is out of scope for the current UI.

## 10. File map

- `ml/typed/TypingActivityDetector.kt` — Stage A
- `ml/typed/TypedListener.kt` — event-driven Stage B + progressive flags
- `ml/typed/TypedWindowing.kt`, `ActivitySegmenter` — train examples / burst locate
- `ml/typed/TypedReplayEval.kt` — event-level replay metrics
- `ml/typed/MiniRocketTransform.kt`, `TypedPreprocessor.kt`, `TypedSoftmax.kt`, stores/workflow
- UI: `ExtraCreditActivity`, `TypedPlayActivity`, `TypedWordActivity`, `TypedListenActivity`
- Tests: `TypedWordTest.kt`
