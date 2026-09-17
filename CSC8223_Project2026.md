# CSC 8223 Course Project

## Federated Learning with Smartphone Sensors

> **Source of truth:** This file is the project plan and requirements
> source of truth for the team. Implementation order, architecture,
> evaluation, contribution, and extra credit are defined here.
> Day-to-day progress checklists live in [`ROADMAP.md`](ROADMAP.md).
> If code or other docs disagree with this file, fix them to match —
> or update this plan deliberately first.

> **Project at a Glance:** Build a small federated-learning system with
> at least two Android phones (smart devices). Each phone collects its
> own sensor data and trains locally. The server combines model updates
> without receiving raw sensor data. Compare two local-only models with
> the federated global model, then add and evaluate one meaningful
> technical contribution.

## Core Question

*Can the devices with different local data collaboratively learn a model
that performs better than any device can learn alone?*

## Phase 0 - Team Formation

Each team may have up to 2 members. If you cannot find a team, notify
the TA before Sep. 15th. Send your team information to the TA by
Sep. 17th.

## Phase 1 - Survey and Project Design

Before implementation, conduct a focused survey of the related knowledge
and techniques. By the end of Phase 1, your team should have a clear
plan for what problem you will address, how your system will work, and
how you will evaluate it.

Your Phase 1 report should be approximately 1-3 pages and include:

-   **Introduction:** background and motivation, problem definition, and
    system overview.
-   **Methodology:** potential algorithms/models/methods/tools, system
    architecture/components, and a high-level flow chart.
-   **Evaluation:** datasets or data-collection plan, experiment
    settings, evaluation metrics, testing plan, and analysis
    methodology.
-   **Implementation Plan:** timeline, expected outcomes, and the
    role/task assignment for each team member.

> **Teamwork Requirement:** Workload must be distributed fairly and
> reasonably. Significant workload imbalance may be inspected and may
> affect individual credit. If you are concerned about a teammate's
> performance or behavior, you may contact the instructor and TA.

**Suggested completion date:** Oct. 20th.

## Phase 2 - Implementation, Evaluation, and Demo

Phase 2 is the implementation phase. Your project must include a
meaningful technical contribution beyond simply reproducing an existing
algorithm, framework, or system. Use experiments and test cases to
validate your design, and analyze the results to explain what you
learned.

**Phase 2 Deadline:** Project Report, Code, and In-Class Demo are due
Dec. 3rd.

# 1. What You Will Build

Each team will build a small federated-learning system using at least
two Android phones (or other smart devices) as IoT clients. Each phone
collects its own motion/orientation data, trains a local model, and
participates in federated learning. Raw sensor data must remain on the
phones; only model updates and necessary training metadata may be
exchanged with the server.

**Workflow:** Collect Data -\> Train Locally -\> Send Update -\>
Aggregate -\> Evaluate

# 2. Phone-Pose Classification Task

Your system will recognize six clearly defined phone poses. The poses
should be collected in a repeatable way so that the data labels are
unambiguous.

  -----------------------------------------------------------------------
  Pose                                Definition
  ----------------------------------- -----------------------------------
  Screen Up                           Phone lies flat with the screen
                                      facing upward.

  Screen Down                         Phone lies flat with the screen
                                      facing downward.

  Portrait Up                         Phone is vertical with the top edge
                                      pointing upward.

  Portrait Down                       Phone is vertical with the top edge
                                      pointing downward.

  Landscape Left                      Phone is vertical with the left
                                      edge pointing upward.

  Landscape Right                     Phone is vertical with the right
                                      edge pointing upward.
  -----------------------------------------------------------------------

You may use smartphone sensors such as the accelerometer, gyroscope,
gravity sensor, and rotation/orientation sensor. You must decide how to
convert raw sensor measurements into useful model inputs.

# 3. Non-IID Local Data

The two clients must have different local training distributions. This
creates a non-IID federated-learning setting in which neither client
sees the complete problem by itself.

  -----------------------------------------------------------------------
  Client A - Primary Training Poses   Client B - Primary Training Poses
  ----------------------------------- -----------------------------------
  Screen Up                           Portrait Down

  Screen Down                         Landscape Left

  Portrait Up                         Landscape Right
  -----------------------------------------------------------------------

Both phones should also collect a small amount of data covering all six
poses for evaluation or calibration.

# 4. Local Learning

Each phone must train a lightweight classifier using only its own local
training data. Your team should make and justify reasonable design
choices, including:

-   how sensor data are sampled and grouped into training examples;
-   which sensors and features are useful;
-   how features are normalized or represented;
-   which lightweight classification model to use.

Your design should be practical for a mobile device, and you should be
able to explain why your choices make sense.

# 5. Federated Learning

Implement a basic federated-learning workflow. Federated Averaging
(FedAvg) may be used as the baseline aggregation method.

1.  Each client receives the current global model.
2.  Each client trains the model using its own local data.
3.  Each client sends a model update to the server.
4.  The server combines the client updates into a new global model.
5.  The updated global model is returned to the clients, and the process
    repeats.

> **Privacy Rule:** Raw sensor data and extracted training samples must
> remain on the phones. The server should receive only model updates and
> necessary training metadata.

# 6. Models You Must Compare

At the end of the project, you should have three distinct models:

-   **Client A Local Model** - trained only on Client A's local data.
-   **Client B Local Model** - trained only on Client B's local data.
-   **Federated Global Model** - trained collaboratively using model
    updates from both clients.

The local-only models must remain independent baselines; they should not
be copies of the final global model.

# 7. Evaluation

Your evaluation should answer the central question: **What does
federated learning provide that local training alone cannot?**

Test all three models on the same six poses and compare their behavior.

Possible metrics include:

-   overall accuracy;
-   per-class accuracy;
-   confusion matrix;
-   macro-F1.

Choose metrics that best support your conclusions rather than reporting
numbers without analysis.

# 8. Required Technical Contribution

The baseline federated-learning pipeline is only the starting point.
Each team must add at least one meaningful technical extension and
evaluate it experimentally.

Possible extensions include:

-   different sensor combinations or feature designs;
-   a different lightweight model;
-   robustness to noisy or incorrect labels;
-   improved or alternative aggregation;
-   cross-device generalization;
-   moving or unknown poses;
-   communication/computation efficiency;
-   a different non-IID data design.

You are not limited to these examples. Clearly state what you changed,
why you changed it, and what the experiments show.

# 9. Optional Advanced Extensions (Up to 30 Extra Points)

Teams that go substantially beyond the required project requirements may
earn extra credit. Extra credit is intended for technically ambitious
work that adds meaningful new capability, not simply more code or a
cosmetic feature.

Possible directions include:

-   **More devices or more clients:** extend the system beyond two
    phones to smartwatches, tablets, wearable devices, IoT sensor
    boards, or other smart devices, and study how heterogeneous devices
    collaborate.
-   **Multimodal sensing:** combine motion sensors with audio,
    images/video, location, light, or other available sensing
    modalities, while respecting privacy and data-handling requirements.
-   **Richer prediction tasks:** move beyond phone poses to recognize
    activities, gestures, device-carrying states, environmental
    conditions, user context, or multiple related targets.
-   **Multimodal or cross-device fusion:** design methods that combine
    information from different sensors or devices, including cases where
    different clients have different sensing capabilities.
-   **Robustness and reliability:** study client dropout, noisy or
    missing sensor data, incorrect labels, device differences, or
    unreliable model updates.
-   **Privacy and security:** explore techniques such as secure
    aggregation, differential privacy, or defenses against
    abnormal/malicious client updates.
-   **Communication- and resource-efficient FL:** reduce communication,
    model size, latency, or energy use through compression,
    quantization, sparsification, adaptive participation, or other
    methods, and measure the trade-offs.
-   **Continual or adaptive learning:** allow the system to learn new
    users, devices, environments, or classes over time without
    retraining everything from scratch.

You are encouraged to propose your own extension. A strong extra-credit
contribution should introduce a clear technical challenge, be integrated
into the working system, and be evaluated with meaningful experiments.

**Extra-credit scoring:** technical ambition and originality (10),
correctness and system integration (10), experimental evaluation (10),
and quality of analysis/demo (10). Several small add-ons do not
automatically receive more credit than one substantial, well-evaluated
extension. Extra credit cannot replace any required project component.

## Our Planned Extra-Credit Extension: Sensor-Based Typed-Word Detection

For the extra-credit portion of the project, we plan to extend the baseline phone-pose classifier into a **sensor-based typed-word detection system**.

The main idea is to determine whether specific predefined words or strings can be recognized from the small movements produced while a person types on the phone. The system will use the phone's **gyroscope, accelerometer/IMU, orientation/rotation information, and any other useful onboard sensor signals** rather than directly reading the keyboard input.

For the class demonstration, we will use harmless proof-of-concept targets such as:

- `google.com`
- `youtube.com`
- `wikipedia.org`
- other predefined benign words or domain names

The intended application is a **non-invasive word checker for parental-control systems**. Rather than recording everything a child types, the system would attempt to identify only predefined target strings from sensor patterns. In a real application, this could potentially be used as an additional signal for blocking restricted websites or searches. For this class project, however, we will only demonstrate the concept using benign targets.

### Extension Plan

1. **Collect labeled typing trials on each phone**
   - Users will type a fixed set of predefined benign words or domains.
   - During each trial, the phone will record gyroscope, accelerometer/IMU, gravity, rotation/orientation, and other useful sensor signals.
   - Each trial will be labeled with the string that was typed.
   - Raw sensor data will remain on the phone.

2. **Convert the sensor stream into training examples**
   - Segment each typing trial into a fixed or normalized time window.
   - Synchronize the available sensor channels.
   - Normalize measurements so that differences in device orientation and sensor scale are reduced.
   - Compare raw time-series input against simple extracted features where useful.

3. **Train a lightweight local classifier**
   - Each phone will train a model using only its own locally collected typing data.
   - The initial task will be multiclass classification of a small set of predefined strings.
   - We may also evaluate a simpler binary formulation: **target string vs. non-target string**.

4. **Finish and validate the typed-word system locally before adding federation**
   - The complete typed-word pipeline will first work independently on a single device: collection, segmentation, preprocessing, local training, inference, and evaluation.
   - The same pipeline will then be verified independently on the second phone.
   - Federation will not be added until both devices can train and evaluate compatible local models successfully.
   - This keeps sensor/model problems separate from networking and aggregation problems during development.

5. **Federate the already-working local models**
   - Use the same federated-learning workflow as the main project.
   - Each phone receives the same model architecture and preprocessing specification.
   - Each phone trains locally and sends model updates rather than raw sensor traces.
   - The server combines compatible updates into a global model using FedAvg or the aggregation method selected for the main project.
   - The updated global model is then returned to both clients for the next round.

6. **Test cross-device and cross-user generalization**
   - Compare Client A's local model, Client B's local model, and the federated global model.
   - Test whether the federated model is better able to recognize the same target strings when typed by a different user or on a different phone.

7. **Evaluate which sensors matter**
   - Compare:
     - gyroscope only;
     - accelerometer/IMU only;
     - orientation/rotation only where appropriate;
     - combined sensor input.
   - This will show whether multimodal sensing improves typed-word recognition.

8. **Evaluate reliability**
   - Measure overall accuracy, per-class accuracy, macro-F1, confusion matrices, false positives, and false negatives.
   - Test repeated trials of the same word.
   - Test small natural variations such as typing speed, phone angle, and hand position.
   - Determine whether the model is actually detecting repeatable typing patterns rather than memorizing one recording condition.

9. **Demonstrate the proof of concept**
   - During the final demo, a user will type one of several benign test strings.
   - The application will not directly inspect the text entered.
   - The classifier will use only the recorded sensor pattern to predict whether one of the predefined target strings was typed.

### Additional Extra-Credit Elements Integrated Into the Extension

This extension can also incorporate several of the advanced directions suggested in the project specification without turning them into separate projects:

- **Multimodal sensing:** combine gyroscope, accelerometer, gravity, rotation/orientation, and other useful phone sensors instead of relying on a single signal.
- **Richer prediction task:** move beyond static phone poses to classification of short, dynamic typing sequences.
- **Cross-device fusion/generalization:** train across two phones and examine whether the federated model transfers better between users and devices.
- **Robustness and reliability:** evaluate the effects of different typing speeds, phone orientations, missing/noisy sensor channels, and natural variation between repeated trials.
- **Privacy and security analysis:** keep raw typing-related sensor data local and discuss the trade-off between useful detection and the fact that motion sensors themselves may reveal information about user input.

### Primary Research Questions

The extension will focus on the following questions:

1. Can short typed strings be distinguished from one another using only smartphone motion/orientation sensor data?
2. Does combining multiple sensor modalities improve performance over using the gyroscope or accelerometer alone?
3. Does federated learning improve recognition across users/devices compared with local-only training?
4. How reliable is the approach when typing speed, phone angle, and hand position vary?
5. Can this be demonstrated without directly collecting or transmitting raw keyboard input?


## Development Strategy: Local First, Federation Second

The project will be developed in two major layers:

1. **Build and validate the complete machine-learning workflow locally on individual phones.**
2. **Add federated learning only after the same model and preprocessing pipeline works independently on both devices.**

This separation is intentional. Sensor collection, preprocessing, model training, Android integration, networking, serialization, and federated aggregation can all fail for different reasons. Building them simultaneously would make debugging unnecessarily difficult.

The local training code should therefore be treated as the core reusable component of the project. Federation will later act as a wrapper around that already-working local training process.

Conceptually:

```text
LocalTrainer
    input: model + local dataset
    output: updated model + training metrics
```

The federated client later becomes:

```text
Receive global model
        |
        v
LocalTrainer
        |
        v
Produce local model update
        |
        v
Send update to server
        |
        v
Receive next global model
```

The goal is that adding federation should **not require rewriting the sensor, preprocessing, training, or inference pipelines**.

### Core Architecture

The app should keep the major responsibilities separated so each component can be tested independently.

```text
Android Sensors
      |
      v
Sensor Collector
      |
      v
Local Dataset / Trial Store
      |
      v
Preprocessing + Windowing
      |
      v
Local Trainer
      |
      +--------------------+
      |                    |
      v                    v
Local Model           Evaluation
      |
      v
Inference
```

Federation is added later:

```text
                    Federated Server
                         ^       |
                         |       v
Phone A <---- model updates / global model ----> Phone B
   |                                             |
   v                                             v
Local Trainer                               Local Trainer
   |                                             |
   v                                             v
Local Data                                  Local Data
```

Raw sensor samples and extracted training examples remain local to each device.

### Recommended App Components

The implementation should be organized around reusable components rather than separate one-off screens:

- **SensorCollector** - subscribes to Android sensor streams and timestamps measurements.
- **TrialRecorder** - starts/stops labeled recording sessions for poses or typed strings.
- **LocalDatasetStore** - stores trials and metadata locally on the device.
- **Preprocessor** - synchronizes channels, windows trials, normalizes data, and creates model-ready tensors/features.
- **ModelDefinition** - defines the exact architecture and input/output shapes.
- **LocalTrainer** - trains the current model using only local data.
- **ModelStore** - saves local/global model versions and associated preprocessing/model metadata.
- **InferenceEngine** - performs predictions from newly collected sensor data.
- **Evaluator** - computes accuracy, per-class accuracy, confusion matrix, macro-F1, false positives, and false negatives.
- **FederationClient** - added later; exchanges model parameters/updates and round metadata with the server.
- **FederatedServer** - coordinates rounds and aggregates compatible client updates.

Keeping these boundaries clean is important because the same `SensorCollector`, `Preprocessor`, `LocalTrainer`, and `Evaluator` can be reused for both the required pose task and the typed-word extension.

## Progressive App Structure

The Android app will expose the project as a series of sections that increase in complexity. Earlier sections remain functional when later sections are added. This makes the development process incremental and also creates a natural final demonstration.

### Section 1 - Local Phone-Pose Classifier

The first section will complete the required pose-classification task **entirely on one device** before any federated-learning code is introduced.

The section will provide:

- live accelerometer, gyroscope, gravity, and rotation/orientation readings;
- controls for recording labeled examples of each of the six required poses;
- local storage of sensor trials;
- preprocessing and feature/tensor generation;
- local model training;
- local model saving/loading;
- live pose inference;
- prediction confidence/probability where supported;
- a small local evaluation view.

The complete single-device pipeline is:

```text
Collect -> Label -> Store -> Preprocess -> Train -> Save -> Predict -> Evaluate
```

The same section should then be run independently on Phone A and Phone B.

**Completion gate:** do not begin federation until both phones can independently collect data, train the same model architecture, save/reload it, and produce local predictions on held-out pose samples.

### Section 2 - Local Evaluation and Sensor Experiments

Once local pose classification works, the second section will make the single-device system measurable and configurable.

It will provide:

- train/test separation;
- overall accuracy;
- per-class accuracy;
- confusion matrix;
- macro-F1;
- number of samples collected per class;
- model/training metadata;
- sensor configuration selection.

Sensor ablations can include:

- accelerometer only;
- gyroscope only;
- gravity/orientation only;
- accelerometer + gyroscope;
- all selected motion/orientation sensors.

This gives us a useful technical contribution even before federation and establishes which inputs are worth carrying forward.

**Completion gate:** the app can run repeatable local experiments and record enough metadata to reproduce which sensor configuration and preprocessing settings produced a result.

### Section 3 - Local Typed-Word Proof of Concept

The main extra-credit extension will also be developed **locally first**.

A user will type predefined benign strings such as:

- `google.com`
- `youtube.com`
- `wikipedia.org`
- other selected control strings.

During controlled data collection, the typed text supplies the trial label. The classifier itself will receive only sensor-derived inputs.

The section will reuse the existing infrastructure:

```text
SensorCollector
      |
      v
TrialRecorder
      |
      v
Preprocessor
      |
      v
LocalTrainer
      |
      v
Typed-Word Model
```

The primary difference from the pose task is that each example is now a **short dynamic time series** rather than a static orientation.

The first local version should support:

- repeated labeled typing trials;
- synchronized motion/orientation channels;
- variable-duration trial recording;
- conversion to a consistent model input;
- local training;
- local inference;
- multiclass string prediction;
- optionally, a simpler `target vs non-target` classifier;
- false-positive and false-negative measurement.

This section should first be proven on one phone and then independently repeated on the second.

**Completion gate:** both phones can independently collect typing trials, train a compatible typed-word model, and evaluate it locally without requiring any server connection.

### Section 4 - Local Robustness Experiments

Before federation is introduced, we will determine how fragile the local models are.

For pose classification, tests can include:

- slightly imperfect poses;
- motion while entering a pose;
- different placement surfaces;
- different device orientations before the trial begins.

For typed-word detection, tests can include:

- different typing speeds;
- different hand positions;
- small changes in phone angle;
- repeated trials collected on different days/sessions;
- noisy or missing sensor channels;
- different sensor subsets.

This section addresses the project's suggested **robustness and reliability** direction while also helping identify preprocessing problems before federated training adds another source of complexity.

### Section 5 - Federated Pose Learning

Only after the local pipeline is stable on both phones will federated learning be introduced.

The federated version will reuse the exact local trainer from Section 1.

For each round:

1. The server maintains the current global model.
2. Phone A and Phone B receive the same global model version.
3. Each phone trains that model using only its own local pose data.
4. Each phone produces a model update and training metadata.
5. The phones send those updates to the server.
6. The server validates that the updates correspond to the same architecture/model version.
7. The server aggregates them using FedAvg.
8. The new global model is returned to the phones.
9. Both phones evaluate the new global model locally.
10. The process repeats for the configured number of rounds.

FedAvg should use the amount of local training data when weighting client updates rather than treating clients as identical when their sample counts differ.

The server should receive only:

- model parameters/updates;
- number of local training samples needed for aggregation;
- training/round metadata needed to coordinate the experiment.

It should **not** receive raw sensor trials or extracted training examples.

### Section 6 - Federated Typed-Word Learning

After pose federation works, the same federated infrastructure will be applied to the typed-word model.

This is deliberately late in the implementation sequence: at this point we should already know that:

- sensor collection works;
- typed-word preprocessing works;
- local typed-word training works;
- both devices use compatible model definitions;
- serialization/deserialization works;
- client/server communication works;
- FedAvg works for the simpler pose model.

The remaining question becomes whether federated training improves the harder typed-word task.

This section will compare:

- Phone A local typed-word model;
- Phone B local typed-word model;
- federated typed-word model.

### Section 7 - Unified Model Comparison and Final Evaluation

The final section will combine results from the earlier stages into a single evaluation/dashboard view.

For the required pose task, compare:

- Client A Local Model;
- Client B Local Model;
- Federated Global Model.

For the extra-credit typed-word task, compare the same three model types where feasible.

Metrics should include:

- overall accuracy;
- per-class accuracy;
- macro-F1;
- confusion matrix;
- false positives;
- false negatives;
- cross-device performance.

Additional useful engineering measurements can be collected with little extra complexity:

- model size;
- size of each federated update;
- training time per local round;
- server aggregation time;
- end-to-end federated round time.

These measurements lightly incorporate the assignment's suggested **communication/resource-efficiency** direction without requiring a separate compression project.

## Detailed Development Sequence

### Milestone 1 - Sensor Infrastructure

Build and test:

- Android sensor subscriptions;
- timestamps;
- start/stop recording;
- local trial storage;
- label assignment;
- export/debug view if useful during development.

Validate that multiple sensor streams remain synchronized closely enough for the planned preprocessing.

### Milestone 2 - Pose Dataset and Preprocessing

Implement:

- collection of all six required poses;
- fixed sampling/windowing rules;
- normalization;
- conversion into model-ready input;
- local train/test splitting.

Avoid tying preprocessing directly to the UI. It should be callable by both local and federated training later.

### Milestone 3 - Local Pose Model

Implement:

- lightweight model definition;
- local training;
- model persistence;
- inference;
- local metrics.

At this point Phone A should be a complete standalone machine-learning application.

Repeat the same workflow on Phone B without changing the architecture.

### Milestone 4 - Local Experimental Framework

Add:

- sensor selection;
- experiment configuration;
- evaluation metrics;
- confusion matrix;
- saved experiment metadata;
- repeatable test procedure.

This becomes the evaluation framework for every later model.

### Milestone 5 - Local Typed-Word Dataset

Implement:

- controlled typing-trial recording;
- benign target/control strings;
- sequence start/stop handling;
- variable-duration trial metadata;
- local storage.

Collect enough repeated trials to determine whether there is a detectable signal before spending time on federation.

### Milestone 6 - Local Typed-Word Model

Start with the simplest model that can represent the sequence task and can later be federated cleanly.

Candidate model families include:

- a small MLP over engineered/window summary features;
- a small 1D CNN over synchronized sensor sequences;
- another lightweight sequence model if the simpler approaches are inadequate.

Model architecture should remain identical across clients so parameter aggregation is well-defined.

Evaluate locally first.

### Milestone 7 - Freeze the Federated Interface

Before networking, define exactly what every client/server message contains.

At minimum, establish:

- model identifier/version;
- round number;
- parameter names/shapes;
- local sample count;
- local training configuration;
- model update/weights;
- success/error status.

Also define the preprocessing version associated with a model. Two phones should never federate models trained from incompatible preprocessing pipelines.

### Milestone 8 - Implement the Federated Server

Start with a minimal server that can:

- create/hold a global model;
- register or identify clients;
- start a round;
- distribute the current global model;
- receive updates;
- reject malformed/incompatible updates;
- perform FedAvg;
- version the new global model;
- return the model to clients;
- log round-level metrics.

Do not add advanced privacy/efficiency mechanisms until this baseline works.

### Milestone 9 - One-Round Federation Sanity Test

Before attempting repeated training:

1. Start both clients from the exact same global model.
2. Train each client locally once.
3. inspect/validate the returned parameter shapes;
4. aggregate once;
5. send the new model back;
6. confirm both clients can load and run inference with it.

This isolates serialization and aggregation bugs before multi-round state is introduced.

### Milestone 10 - Multi-Round Pose Federation

Run repeated FedAvg rounds for the required pose task.

Verify:

- both clients begin each round from the correct global version;
- local datasets remain local;
- sample-count weighting is correct;
- model versions cannot be mixed accidentally;
- local baseline models are preserved separately from federated models.

The assignment requires the two local-only models to remain independent baselines, so the app should store them separately rather than overwriting them with the global model.

### Milestone 11 - Federated Typed-Word Extension

Once pose federation is reliable:

- reuse the federation client/server;
- swap in the compatible typed-word model definition;
- run local training on each phone;
- aggregate;
- evaluate local vs federated word recognition;
- test cross-user/device transfer.

No new networking architecture should be necessary.

### Milestone 12 - Robustness, Bonus Experiments, and Final Demo

Use the completed system to evaluate:

- sensor ablations;
- device differences;
- user differences;
- typing-speed/angle/hand-position variation;
- noisy/missing sensors;
- update size and round timing.

The final app/demo can then move through the sections in the same order the system was developed:

```text
Local Pose
   ->
Local Evaluation
   ->
Local Typed-Word Detection
   ->
Robustness Tests
   ->
Federated Pose
   ->
Federated Typed-Word Detection
   ->
Local vs Federated Comparison
```

## Validation and Debugging Strategy

A major design goal is to avoid debugging several layers at once.

### Sensor/Data Bugs

Validate these before model training:

- expected sensors are available;
- timestamps increase correctly;
- sampling is sufficiently consistent;
- labels match the intended trial;
- trials contain the expected duration/sample count;
- no stale sensor data leak between trials.

### Model Bugs

Validate locally before federation:

- input tensor shape is correct;
- labels/classes are mapped consistently;
- training loss behaves sensibly;
- model can be saved and loaded without changing predictions;
- evaluation uses held-out data rather than training data;
- Phone A and Phone B instantiate exactly the same architecture.

### Federation Bugs

Validate separately from sensor collection:

- clients receive identical starting weights;
- parameter names/shapes match;
- updates can round-trip through serialization;
- aggregation reproduces the expected weighted average;
- global model version increments correctly;
- clients cannot accidentally send updates from an old round;
- global models do not overwrite preserved local-only baselines.

### Privacy Check

Before the final demo, verify that network traffic contains only allowed model/training metadata and model updates. Raw sensor arrays and extracted training samples should never be sent to the server.

## Scope Priorities

If time becomes limited, work should be protected in this order:

1. **Required local pose classifiers on both devices**
2. **Required federated pose classifier**
3. **Required local-vs-federated evaluation**
4. **One strong, working typed-word proof of concept**
5. **Federated typed-word training**
6. **Sensor ablations and robustness experiments**
7. **Additional efficiency/privacy enhancements**

This ordering ensures that extra-credit work cannot jeopardize the required project components.

## Optional Small Bonus Additions

Several bonus-credit directions can be incorporated with relatively little additional architecture once the main system works:

- **Multimodal sensing:** already supported through selectable sensor combinations.
- **Richer prediction tasks:** the typed-word classifier is the primary extension.
- **Cross-device generalization:** test each local model on the other phone/user's held-out trials and compare with the federated model.
- **Robustness/reliability:** test changed typing speed, phone angle, hand position, noisy/missing channels, and pose imperfections.
- **Communication efficiency measurement:** report update size and federated round latency even if no compression method is implemented.
- **Client dropout experiment:** deliberately omit one client from selected rounds and observe how the system behaves.
- **Continual/adaptive learning stretch goal:** allow a user to collect additional local examples and continue training from the current model rather than rebuilding the entire dataset/model from scratch.

More ambitious privacy mechanisms such as differential privacy or secure aggregation should be treated as stretch goals only after the required system and main typed-word extension are stable.


# 10. Implementation Choices Left to You

This project intentionally does not prescribe every implementation
detail. Your team is expected to make reasonable engineering and
modeling decisions, test them, and justify them.

Examples include:

-   window length;
-   sampling strategy;
-   feature extraction;
-   normalization;
-   model hyperparameters;
-   communication protocol;
-   number of federated rounds;
-   exact evaluation procedure.

# 11. Final Demonstration

Your final demo should make the system behavior easy to understand. Show
that both phones can collect data, train locally, participate in
federated learning, and compare the three models on the same pose
inputs. Also demonstrate your technical extension and explain the main
experimental findings.

All team members must be present. Each team will have 15-30 minutes for
the demonstration and Q&A.

# 12. Phase 2 Deliverables and Grading

## Project Report - 15%

Submit a 5-10 page report.

  -----------------------------------------------------------------------------
  Section                                     Points Required Content
  --------------------- ---------------------------- --------------------------
  Introduction                                    10 Background and motivation,
                                                     problem definition, system
                                                     overview.

  Methodology                                     40 Proposed
                                                     algorithms/methods,
                                                     adopted models, system
                                                     architecture/components,
                                                     design justifications, and
                                                     analysis.

  Evaluation                                      30 Experiment/demo settings,
                                                     results, observations, and
                                                     inferences.

  Learning Outcomes                               18 Tasks accomplished by each
                                                     team member, reasons for
                                                     unaccomplished tasks or
                                                     failures, lessons learned
                                                     from the project and
                                                     teamwork experience.
  -----------------------------------------------------------------------------

## Code and Sources Folder - 5%

Create a folder named `Sources` containing all project code and any
required executables. Include a README that states the required
systems/software with version information and gives step-by-step
instructions for building and running the client app, server, and
evaluation code.

The team leader should submit the report and Sources folder together as
one zip/tar file to iCollege. Each team submits one copy. Only the last
uploaded submission will be graded.

## In-Class Demo - 80%

The final demo will be held in class. A group of users will test your
smartphone pose-recognition system and determine its prediction
accuracy. Team accuracy results will be ranked, and the accuracy-based
demo score will be assigned based on the team's position in the class
ranking. The demo component accounts for 80% of the final project grade.

# 13. Project Submission and Deadlines

  ------------------------------------------------------------------------
  Phase            Deliverable /    Deadline                    Assessment
                   Submission                        
  ---------------- ---------------- ---------------- ---------------------
  Phase 0          Sign up for a    Sep. 17th                          N/A
                   team / send team                  
                   information                       

  Phase 1          Partial report / Oct. 20th                          N/A
                   project design                    

  Phase 2          Project Report - Dec. 3rd                           15%
                   iCollege                          

  Phase 2          Code (Sources    Dec. 3rd                            5%
                   folder) -                         
                   iCollege                          

  Phase 2          Demo - in class, Dec. 1st/3rd                       80%
                   in person                         
  ------------------------------------------------------------------------

# 14. Final Checklist

-   [ ] Two Android clients and a federated server are implemented.
-   [ ] Raw sensor data remain on the phones.
-   [ ] Client A and Client B have different local training
    distributions.
-   [ ] Two local-only models and one federated global model are
    evaluated.
-   [ ] The team includes and evaluates at least one meaningful
    technical contribution.
-   [ ] The report, Sources folder, README, and in-class demo are
    complete.
-   [ ] Extra credit: sensor-based detection of predefined typed strings
    is implemented and evaluated.
