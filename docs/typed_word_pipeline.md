# Typed-Word Detection (Extra Credit): Search-Query Spotting

Project: CSC8223 IoT Android app. Everything here is the **extra credit** track. The class-project
baseline is the **phone-angle (pose) classifier with federated learning**, which is separate and
untouched by this work.

## 1. Goal

Intended application: an on-device parental-safety filter that **flags** a small set of blocked
website names typed into a **search bar**, from IMU motion alone, without reading keyboard input or
transmitting private data. For the class project, `google` and `yahoo` are harmless stand-ins.

Scope is deliberately narrow: one short query at a time (a search bar), not free text. Output per
query: `google` / `yahoo` / `other`, and a **Flagged** banner within ~0.5 s of the typing pause.

## 2. Why the old pipeline was replaced

The first version (Stage A burst detector → MiniRocket 84 features → linear softmax per whole word)
scored poorly and lagged:

| Problem | Effect |
|---|---|
| 50 Hz sampling (`SENSOR_DELAY_GAME`) plus a 15 ms spacing filter | A 20–60 ms tap got 1–3 samples |
| Raw accelerometer with gravity, no detrending | Features dominated by hold angle, not taps |
| Index-resampling each burst to 64 steps | Aliasing; typing speed distorts everything |
| Whole-word templates from tens of trials | Too little data per class; no sharing between words |
| Decision only after 850 ms of quiet | ≥1 s lag, never mid-typing |
| Sensor callbacks on the UI thread; full buffer copied every 100 ms | Jank with the keyboard open |

## 3. Approach: per-tap position + keyword spotting

This follows the motion side-channel literature (TouchLogger, ACCessory, TapLogger, TapPrints:
per-tap inference plus a dictionary) and keyword–filler spotting from speech recognition.

```
IMU 200 Hz ──► TapStream ──► tap features ──► TapPositionModel ──► WordSpotter ──► Flagged?
             (gravity removed,  (22 numbers     (ridge → x,y on    (Viterbi align vs
              onset detector)    per tap)        the keyboard)      google/yahoo/background
                                                                     + lookalike words)
```

1. **Sampling** — `SensorCollector.TAP_SAMPLING_US` = 5 ms (200 Hz), delivered on a background
   `HandlerThread`.
2. **`TapStream` + `TapDetector`** — tap onsets come from a **learned detector**, trained on each
   phone from Play's touch-downs:
   - Samples are resampled onto a 5 ms grid and high-passed (τ = 0.15 s).
   - Each frame's window (−40…+70 ms, 6 channels, plus absolute values = 264 numbers) is scored
     by logistic regression for "a tap starts here".
   - Candidates closer than 60 ms keep the strongest. The probability threshold is picked on
     validation.
   - Before there are enough labeled taps, an energy fallback is used
     (`|accel_hp| + |gyro − baseline|` above noise floor × *sensitivity*). Its noise floor now
     always updates (fast down, slow up). It used to update only below threshold, which locked
     up on a noisy phone and fired on noise.
   - Gravity / gyro baselines for the tap features nearly freeze during a tap. All history is in
     preallocated primitive ring buffers.
3. **Tap features** (`TapStream.FEATURE_NAMES`, 22 values). Signals are interpolated onto a 5 ms
   grid, so phones sampling at 100 Hz and 400 Hz produce the same features:
   - gyro rotation integrated over 0–80 and 80–150 ms after onset
   - accelerometer means over 0–30 and 30–100 ms
   - signed gyro peaks
   - pre-tap rotation (thumb approach)
   - gravity direction (grip/posture)
   - log tap energy
4. **`TapPositionModel`** — two ridge regressions predict the tap's normalized keyboard
   position (x, y), with λ picked by 4-fold CV on the train split.
   - **Calibration:** with R² around 0.5, ridge output is shrunk toward the keyboard centre.
     Scoring it against raw key centres made central keys (g, y) beat edge keys (e, o) every
     time, which is why google always lost to googly / goggle. On out-of-fold predictions
     (folds by query) the model fits `prediction ≈ α·true + β` with residual σ, and the matcher
     scores against `α·key + β`. The movement model is calibrated the same way.
   - It also stores feature-distance statistics for same-key vs different-key tap pairs.
   A logistic **thumb-side** classifier (P(right thumb)) is trained on two-thumb sessions, with
   labels taken from which half of the keyboard was touched. Taps within 0.12 of the centre are
   left out because their labels are ambiguous. It is skipped if either side has fewer than
   20 taps.
   A **movement** model (two more ridge regressions) predicts the finger's displacement
   (Δx, Δy) between consecutive taps from the difference of their scaled features. It is trained
   on consecutive touches in the same query (≤ 1.5 s apart, at least 20 pairs).
5. **`WordSpotter`** — for each candidate word and variant (`word`, `word.com`, either followed
   by Enter), it computes the best Viterbi alignment of taps to key centres:
   - Each tap is scored by a Gaussian around the key centre (key width folded in), so neighbour
     slips like o/p or g/h cost little.
   - The alignment allows extra taps (typo, backspace, stray) and missed taps, each with a penalty.
   - **Repeated letters** (`oo` in both targets, `g…g` in google) add a same-key bonus: taps
     aligned to the same key should have similar raw motion. This works the same on any device.
   - **Double-key timing** (optional): when a double letter is typed as two back-to-back taps,
     the second gap is compared with this query's median gap. A gap at or below half the median
     earns the full bonus, and a gap at or above the median earns nothing. The bonus is one-sided,
     so hesitations are never penalized.
   - **Thumb side** (optional): each tap's P(right thumb) is compared with the side the key is
     usually typed with (soft near the middle, since g/h/t/y/b vary between people). google
     ≈ L R R L R L and yahoo ≈ R L R R R. This only adds information when position is unsure
     which half the tap was on; otherwise the background model absorbs it.
   - **Movement** (optional): for consecutive letters matched to consecutive taps, the predicted
     Δ is compared with the step between the two keys (g→o up-right, o→o none), against a
     background of steps between random keys. Grip offsets that shift every absolute position
     cancel in the difference, and pauses between taps don't matter.
   - All three optional weights come from {0, 0.5, 1} and are picked on validation. Ties go to
     the simpler setting, so an extra stays off unless it helps.
   - Each score is a log-likelihood ratio against a background model (letter-frequency mixture
     over all keys).
   - Lookalike **competitors** (`goggle`, `googly`, `goose`, `gogo`, `yoga`, `yahtzee`, `gmail`,
     `youtube`) are scored too, each weighted by a prior ρ = 0.1 relative to a blocked word.
     They are much rarer searches, so when the motion cannot separate google from googly, the
     tie goes to the target. A clearly typed lookalike still loses.
   - Flag when `best target LLR − log(1 + Σ ρ·exp(competitor LLR)) ≥ threshold`.
6. **`QueryListener`** (live) — a typing pause ends the query, and the query is scored once.
   - The pause adapts to the typist: 3× the median gap between taps over the last 32 gaps,
     clamped to 450–1000 ms (450 ms until 6 gaps have been seen).
   - **Carry-over:** taps from an unflagged query are kept for 2 s. If the next query does not
     flag on its own, the joined sequence is scored too, so "goo… gle" still flags. Unrelated
     earlier taps only cost extra-tap penalties, so this is conservative.
   - From the 4th tap on, an optional early flag needs a +3 nat stricter margin.
   - Competitors also get the prefix allowance, so `goog…` ties with `googly` (margin ln 10 ≈
     2.3, below the early bar) and cannot early-flag, but `yaho…` can.
   - One flag per query.

No text is reconstructed: only the fixed word hypotheses are ever scored, and per-tap positions
stay in memory.

## 4. Privacy and constraints

- Live detection input: accelerometer + gyroscope only. No text, IME events, key identities,
  key timestamps or keyboard visibility. The query boundary is the IMU typing pause, and Enter
  is just another tap.
- Training labels come from the **in-app training keyboard** (`TapKeyboardView`, Play only):
  touch position + touch-down time on the sensor clock. These are labels only, never model input.
- All data, training and inference stay on device. Federation shares model weights/statistics
  only (see §8).
- Honest caveat for the report: a per-tap position model is the core of a motion keylogger. The
  design limits it to scoring a fixed blocklist and never emits a decoded string.

## 5. Collection (Extra credit → Search queries)

- A prompt, a fake search bar, and the training keyboard (QWERTY drawn exactly as
  `KeyboardLayout`, Gboard-like row 4: `?123 , space . search`).
- Prompt mix (`TypedWordBank`):
  - 20% `google`, 20% `yahoo` (a third of each as `.com`)
  - 15% lookalikes
  - 45% ordinary searches
- The query is saved on **search** with label = what was actually typed (the prompt goes in the
  notes). A mistyped blocked word ("gogle" for google) is still evaluated as that target, since
  an attempted google is what a filter should catch. Recording continues 400 ms after search so
  the last tap's window is complete.
- **Grip** (two thumbs / one thumb / on table) is stored as `sessionTag`, for cross-grip
  evaluation.
- **Idle** recordings (200 Hz, no typing) measure false taps per minute.
- Stored as `TrialTask.TYPED_TAP` with `Trial.touches`. Old 50 Hz `typed_word` trials are ignored.
- Rough target per person per phone: ~20 of each target, ~60 other queries, 3 idle recordings
  (~30 min).

## 6. Training and evaluation (`TapTrainer`)

### Learning from the real keyboard

Taps on the training keyboard turned out to be firmer than on the phone's normal keyboard (about
half the high-frequency accelerometer motion on the real keyboard). The real keyboard also has a
different size and position on screen. A detector trained only on Play under-counted real taps
("yahoo" as 3 taps).

The live search page therefore has an opt-in **Save my searches for tuning** switch. Each search
stores the motion plus the typed text (labels only, never model input). The system keyboard only
reports when the text changed, and it commits a letter on **finger lift**: candidate onsets
cluster 0–20 ms (the lift) and 60–100 ms (the press) before each text change. Training therefore
runs in two stages:

1. A detector is fit on Play's exact touch-downs.
2. Each real-keyboard search is aligned. For each typed character, in order, the label is the
   most confident candidate tap 35–150 ms before the text changed. Characters with no candidate
   are dropped, and frames near them are ignored.
3. The detector and position model are refit on Play plus the aligned real-keyboard taps, with
   the real-keyboard taps weighted ×3. Positions use the typed key's centre.

### A second typist

The second Play batch (52 queries) was typed by a **different person**. They held the phone
tilted further back (gravity on the long axis 0.57 vs 0.87) and their taps carried less signal:

| | Typist A (98 queries) | Typist B (52 queries) |
|---|---|---|
| Position R², perfect detection (x / y) | 0.57 / 0.33 | 0.29 / 0.03 |
| Tap detection P / R | 0.92 / 0.94 | 0.66 / 0.83 |
| Query macro-F1 | 0.80 | 0.47 |

A model trained on A and used on B, position R² (x / y):

| Setup | x / y |
|---|---|
| A's model and A's feature scaling | 0.19 / −0.17 |
| B's taps standardized on B's own averages (no labels from B) | 0.32 / 0.15 |
| + 10 labeled queries from B | 0.39 / 0.23 |
| + 20 labeled queries from B | 0.41 / 0.24 |

So the cross-user design is a shared model plus per-person feature scaling, which can be
estimated from unlabeled taps. That matches FedAvg (shared weights) + FedBN (local
normalization). Tilt interactions in the position model also help across holding styles:
pooled R² went from 0.51 / 0.25 to 0.59 / 0.32.

### Real-keyboard results (114 searches by typist A, 5-fold CV)

| Change | Real keyboard right | Blocked words caught |
|---|---|---|
| All data, missed tap p = 0.08 | 82 / 114 | 11 / 37 |
| Typist B's Play queries left out (experiment, since reverted) | 88 / 114 | 15 / 37 |
| + detector threshold tuned on real-keyboard searches (grid down to 0.2) | 86 / 114 | 16 / 37 |
| + missed tap p = 0.2 (soft taps are often missed) | 91 / 114 | 22 / 37 |
| All data kept (current), missed tap p = 0.2 | 86 / 114 | 15 / 37 |

Leaving typist B out only helped because every real-keyboard search was by typist A. Training
on both typists in one model blurs their styles. The current build keeps all data; per-typist
scaling is the fix.

False flags on ordinary real-keyboard searches: about 10% (8 / 77). The missed-tap setting was
compared on these same CV results (0.08 / 0.2 / 0.3), so its gain is slightly optimistic.
google is usually flagged when all 7 of its taps are found. Most misses are searches where the
detector found only 4–6, so tap recall on the soft real keyboard is the main remaining limit.

The report adds a **Real keyboard** line: searches right, and blocked words caught, among
real-keyboard searches in the cross-validation pool.

Evaluation is **5-fold cross-validation by query**: every query is tested exactly once, by a model
that never saw it. With ~100 queries, a single 20% test split held only 3–4 targets, so one query
swung every number. Inside each fold the training queries split 75/25:

| Part | Used for |
|---|---|
| Fit (75%) | Learned tap detector (or energy sensitivity); position, thumb and movement models |
| Validation (25%) | Detector threshold; thumb / timing / movement weights; flag threshold (midpoint of the best-macro-F1 margin interval) |
| Held-out fold | All reported numbers, pooled over the 5 folds |

The deployed model runs the same fit / validation procedure on all queries.

Training taps are detected onsets matched to touches (±60 ms after removing the median lag), so
they are exactly what live inference sees. Touches the detector missed are added, anchored at
touch time + lag.

Report (`TapEvalReport`, shown on the Extra credit screen and saved in the model JSON):

- Tap detection precision / recall, and idle false taps per minute
- Tap position: row accuracy, exact-key accuracy, mean error in key widths
- Query level: accuracy, macro-F1, 3×3 confusion matrix, false-flag rate on `other` queries
- Thumb-side accuracy (two-thumb test taps)
- **Ablation**: position only / + double-key timing / + thumb side / + movement / + all / selected. Each row
  gets its own validation threshold and is scored on test (macro-F1, false-flag rate).
- Mean scoring time per query

Unit tests (`TypedWordTest`) cover the plumbing on synthetic IMU (physically motivated tap
twists), not real-world accuracy. `RealDataReplayTest` runs the real trainer on recordings pulled
from a phone. It is skipped unless `TYPED_DATA_DIR` points at a folder of trial JSON files:

```bash
adb exec-out run-as edu.iot.phoneangle tar cf - files/trials > data.tar
```

### Results on real data (Galaxy S20+, one user, two thumbs, 98 queries, 5-fold CV)

| | First version | After detector + calibration + prior |
|---|---|---|
| Tap detection P / R | 0.44 / 0.81 | 0.91 / 0.94 |
| Keyboard row / exact key | 39% / 10% | 46% / 13% |
| Thumb side | 85% | 96% |
| Query accuracy / macro-F1 | — (flagged nothing) / 0.30 | 88% / 0.83 |
| google flagged | 0 / 4 (test split) | 16 / 17 |
| yahoo flagged | — | 7 / 10 |
| False flags on other queries | 0% | 11% (8 / 71) |

Per-tap key accuracy stays low by design (horizontal R² ≈ 0.57, vertical ≈ 0.33 even with perfect
detection). Words are spotted from evidence accumulated over 5–6 taps, not from individual keys.

## 7. Cost on the phone

| Step | Frequency | Cost |
|---|---|---|
| Filters + learned onset detector | every 5 ms frame | ~300 multiply-adds, no allocation |
| Features | per tap | ~200 interpolations |
| Position | per tap | 2 × 22 multiply-adds |
| Spotting | per query | ~40 alignments of ≤ 24 × 11 cells (well under 1 ms) |

## 8. Next steps

- Collect on both phones; report per-grip and cross-device results.
- Typed federation is implemented (`TypedFederation`, Extra credit → **Federate**, Git transport
  only): every numeric field of the model JSON is averaged, weighted by training taps. Scaler,
  calibration, sigmas, thresholds and spotter weights stay local (FedBN-style — they absorb the
  phone's hand / hold); a phone with no local data uses the averaged model as-is. Not yet tried
  on two physical phones.
- Optional: per-grip models using the gravity direction.

## 9. File map

- `ml/typed/TapStream.kt`: 200 Hz front end, frame grid, energy fallback, tap features, ring buffer
- `ml/typed/TapDetector.kt`: learned per-frame tap-onset detector
- `ml/typed/TapPositionModel.kt`: ridge position model, scaler, same/different-key stats
- `ml/typed/WordSpotter.kt`: keyword–filler alignment and flag decision
- `ml/typed/QueryListener.kt`: live query segmentation + scoring
- `ml/typed/TapTrainer.kt`, `TapWorkflow.kt`, `TapModelStore.kt`: training, evaluation, persistence
- `ml/typed/TypedFederation.kt`: tap-count-weighted FedAvg over model JSON, local adaptation
- `ml/typed/KeyboardLayout.kt`, `TypedWordBank.kt`, `TypedLabel.kt`
- UI: `ExtraCreditActivity`, `TypedPlayActivity` + `ui/TapKeyboardView`, `TypedWordActivity` (idle),
  `TypedListenActivity`
- Tests: `TypedWordTest.kt`, `RealDataReplayTest.kt` (real recordings)
