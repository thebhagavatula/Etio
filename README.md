# Etio

Offline, voice-assisted operating-theatre day log. Captures **why** time was lost, not just how much, and fans one delay event out into four differently-worded updates.

Native Android · Kotlin · Jetpack Compose · Room · MediaPipe LLM Inference (Gemma 3 1B int4) · everything on-device.

---

## Hour-one checklist

1. Open the folder in Android Studio. When it offers to create the Gradle wrapper, accept — `gradle/wrapper/gradle-wrapper.properties` already pins **Gradle 8.11.1**, only the jar is missing. (From a terminal with Gradle installed: `gradle wrapper`.)
2. Sync. Everything else is pinned in `gradle/libs.versions.toml`.
3. **Put the model on the phone** (§ Model file below). This is the hour-one fatal risk from the PRD — do it before any UI work.
4. Install the offline **Indian English** speech pack: Settings → Google → Voice → Offline speech recognition → add `English (India)`. Without it, ASR fails the moment airplane mode goes on.
5. `./gradlew installDebug`, open the app, watch Logcat for `LLM warm`. The banner at the top of the delay screen shows the backend and load time.

Working as a team? Read **BRANCHES.md** before anyone writes code — it defines the three branches, who owns which files, and the merge protocol.

Until the model is on the device, flip `AiModule.USE_FAKE_LLM = true` — the entire UI, timer and checklist path works against `FakeLlmEngine`, which returns canned responses at realistic latency.

## Model file

Not bundled in the APK (550 MB in git is how you lose an hour on Sunday morning). Sideload it:

```bash
# 1. Get gemma3-1b-it-int4.task onto the laptop (Kaggle / HF), verify the checksum,
#    and keep a second copy on a USB stick. Venue wifi is the fatal risk.

# 2. Push to the sideload path the app checks second:
adb shell mkdir -p /data/local/tmp/llm
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/

# 3. (Optional, faster reads) copy into app-private storage, which the app checks first:
adb shell run-as com.etio.ot.debug mkdir -p files/models
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
adb shell "run-as com.etio.ot.debug cp /data/local/tmp/llm/gemma3-1b-it-int4.task files/models/"
```

`ServiceLocator.resolveModelPath()` prefers app-private, falls back to the sideload path. `ModelLocator.FILE_NAME` is the single place the filename is written down.

**GPU first, CPU automatic fallback.** `MediaPipeLlmEngine` tries the GPU backend and silently retries on CPU if the driver refuses — a loaner phone is not a device you control. NPU/QNN is a Sunday-morning stretch, not a dependency.

## Red Light architecture

Everything tunable lives in JSON, copied from `assets/config/` to `files/config/` on first launch and read from there afterwards:

| File | What you tune |
| --- | --- |
| `prompts.json` | System prefix, Job 1 instruction + few-shot examples, Job 2 per-audience tone rules, sampling params |
| `taxonomy.json` | The 11 delay codes, their descriptions, department hints |
| `checklist.json` | WHO checklist item text per phase, and which items are critical |
| `seed_cases.json` | The demo day's case list |

Red Light hours are then: edit JSON in a phone text editor → force-stop → relaunch → test. **No compiler required.** A malformed edit falls back to the bundled asset rather than crashing — see `ConfigProvider`.

`ConfigProvider.installIfNeeded(force = true)` re-copies from assets if you need to undo a bad night's tuning.

## Architecture

```
ui/            Compose screens + ViewModels. Nothing here awaits inference
               except DelayCaptureViewModel, on purpose.
  Routes.kt    FROZEN. Every route string, declared up front.
  EtioApp.kt   FROZEN. NavHost composing three per-slice graphs.
domain/        Pure Kotlin. No Android imports, no coroutines, no I/O.
  timing/      TimerEngine — a pure function of (cases, events, now).
  checklist/   ChecklistStateMachine — the deterministic safety gate.
  report/      EndOfDayReportBuilder — aggregation only.
ai/            LlmEngine interface + MediaPipe impl + Fake impl.
               DelayClassifier (Job 1), MessageDrafter (Job 2),
               DelayJsonValidator (the actual guarantee), SpeechCapture.
data/          Room entities/DAOs/database, ConfigProvider, repositories.
di/            Manual DI — no Hilt, deliberately. One module per slice:
               CoreModule (config, db, cases), AiModule (LLM, ASR, delays),
               SafetyModule (checklist). ServiceLocator is a frozen shell
               holding only the app context and clock.
```

The `di` and nav split is not ceremony — it is what lets three people work in
parallel without editing the same files. See **BRANCHES.md**.

### Three invariants worth defending in review

**1. Timers never touch inference.** `TimerEngine` is `object TimerEngine` with no Android or coroutine imports, driven by a 1-second ticker in `CaseListViewModel`. If the model hangs, the clocks on the projector keep moving.

**2. The model's output is never trusted.** `DelayJsonValidator` extracts the first balanced `{...}` (small models add fences and preambles), coerces unknown codes to `OTHER`, and **drops `estimated_min` unless a duration actually appears in the transcript** — the single most common hallucination in this task. It never throws and never surfaces a parse error; a failure becomes an `OTHER` the coordinator is asked to correct.

**3. The checklist is model-free.** `ChecklistStateMachine.gate()` is the sole authority on whether an event may be marked. Skipping is allowed but never silent — the reason is stored and appears in the report.

### Events are append-only

`EventEntity` is never updated in place. A correction inserts a new row pointing at the old one via `correctedFromEventId`, and the old row gets `supersededByEventId`. `TimerEngine` reads only live rows. That audit trail is the answer to "how do we know the timestamps are real".

## Demo path

`CaseListScreen` → tap the event grid (WHO dialog fires at `PATIENT_IN_ROOM` / `ANAESTHESIA_START` / `CLOSURE_COMPLETE`) → **hold the mic** → transcript appears → `DelayReviewCard` with the verbatim transcript under every model-assigned field → **Notify** → four messages, surgeon and family adjacent → **report icon** in the top bar.

The refresh icon in the top bar wipes and re-seeds the day. Use it between rehearsals.

## Tests

```bash
./gradlew testDebugUnitTest
```

Covers the three things that must not break: `TimerEngine` spans and corrections, `DelayJsonValidator` against the failure modes a 1B model actually produces, and `ChecklistStateMachine` gating.

## Non-goals (enforce these)

No ambient listening. No predictive re-sequencing. No multi-user sync, accounts, or roles. No EHR/HIS integration. No clinical decision support. No actual message sending — copy and share sheet only. No cloud anything. One theatre, one day.

The app declares **no `INTERNET` permission**. It is architecturally incapable of leaving the device.
