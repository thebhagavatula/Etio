# Etio

Offline, voice-assisted operating-theatre day log. Captures **why** time was lost, not just how much, and fans one delay event out into four differently-worded updates.

Native Android · Kotlin 2.0.21 · Jetpack Compose · Room · DataStore · MediaPipe LLM Inference (Gemma 3 1B int4) · everything on-device.

`applicationId com.etio.ot` (debug installs as `com.etio.ot.debug`) · minSdk 26 · compileSdk/targetSdk 35 · JVM target 17.

---

## Hour-one checklist

1. Open the folder in Android Studio. When it offers to create the Gradle wrapper, accept — `gradle/wrapper/gradle-wrapper.properties` already pins **Gradle 8.11.1**, only the jar is missing (it is deliberately not committed). From a terminal with Gradle installed: `gradle wrapper`.
2. Sync. Every version is pinned in `gradle/libs.versions.toml` — AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, Room 2.6.1, MediaPipe `tasks-genai` 0.10.24.
3. **Put the model on the phone** (§ Model file). This is the hour-one fatal risk — do it before any UI work.
4. Install the offline **Indian English** speech pack: Settings → Google → Voice → Offline speech recognition → add `English (India)`. `AndroidSpeechCapture` sets `EXTRA_PREFER_OFFLINE`; without the pack, ASR fails the moment airplane mode goes on.
5. `./gradlew installDebug`, open the app, watch Logcat for `LLM warm` from `EtioApplication`. The banner at the top of the delay screen shows the backend and load time.

The first launch opens the tutorial, not the day (§ First run, and settings).

Working in parallel? Run `bash tools/check-boundaries.sh` before you open a PR — it is the merge protocol in executable form (§ Lanes and frozen files).

Until the model is on the device, flip `AiModule.USE_FAKE_LLM = true` — the entire UI, timer and checklist path works against `FakeLlmEngine`, which returns canned responses at realistic latency. **It must be `false` on `main`**; the boundaries script fails the check if a branch tries to merge with it flipped.

## Model file

Not bundled in the APK (550 MB in git is how you lose an hour on Sunday morning) — `.task`, `.bin` and `.tflite` are all gitignored. Sideload it.

Get `gemma3-1b-it-int4.task` onto the laptop (Kaggle / HF), verify the checksum, and keep a second copy on a USB stick — venue wifi is the fatal risk. Then push it to the sideload path the app checks second:

```bash
adb shell mkdir -p /data/local/tmp/llm && adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
```

Optionally copy it into app-private storage, which the app checks first and reads fastest:

```bash
adb shell run-as com.etio.ot.debug mkdir -p files/models && adb shell "run-as com.etio.ot.debug cp /data/local/tmp/llm/gemma3-1b-it-int4.task files/models/"
```

`AiModule.resolveModelPath()` prefers app-private (`<files>/models/`), falls back to `/data/local/tmp/llm/`. `ModelLocator` in `ai/LlmEngine.kt` is the single place the filename and both paths are written down; `AiModule.modelPresent()` answers whether either exists.

**GPU first, CPU automatic fallback.** `MediaPipeLlmEngine` tries the GPU backend and silently retries on CPU if the driver refuses — a loaner phone is not a device you control. NPU/QNN is a Sunday-morning stretch, not a dependency.

## Red Light architecture

Everything tunable lives in JSON, copied from `app/src/main/assets/config/` to `<files>/config/` on first launch by `ConfigProvider.installIfNeeded()` and read from there afterwards:

| File | What you tune | Shape today |
| --- | --- | --- |
| `prompts.json` | System prefix, Job 1 instruction + few-shot examples, Job 2 per-audience tone rules, sampling params | 4 few-shot examples; 4 audience rules |
| `taxonomy.json` | The delay codes, their descriptions, typical department | 11 codes, 11 department hints |
| `checklist.json` | WHO checklist item text per phase, and which items are critical | Sign In 7 (5 critical) · Time Out 7 (5) · Sign Out 5 (4) |
| `seed_cases.json` | The demo day's case list | 5 cases in OT-2, 08:30 → 16:15 |

These map 1:1 onto the `@Serializable` classes in `data/config/ConfigModels.kt`.

Red Light hours are then: edit JSON in a phone text editor → force-stop → relaunch → test. **No compiler required.** A malformed edit falls back to the bundled asset rather than crashing — every accessor in `ConfigProvider` is wrapped. `installIfNeeded(force = true)` re-copies from assets if you need to undo a bad night's tuning; `configDirPath()` tells you what to edit on the phone.

## Repository layout

```
Etio/
├── app/
│   ├── build.gradle.kts             Compose, Room+KSP, DataStore, MediaPipe
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  FROZEN. RECORD_AUDIO only — no INTERNET
│       │   ├── assets/config/       prompts · taxonomy · checklist · seed_cases
│       │   ├── java/com/etio/ot/    (see below)
│       │   └── res/                 launcher icons, strings, splash theme
│       └── test/java/com/etio/ot/   ai/ and domain/ — plain JVM unit tests
├── gradle/
│   ├── libs.versions.toml           every version, one file
│   └── wrapper/                     properties only; the jar is generated, not committed
├── tools/
│   ├── check-boundaries.sh          pre-merge lane + frozen-file gate
│   └── check-contrast.py            WCAG ratios for the palette, both themes
├── build.gradle.kts · settings.gradle.kts · gradle.properties
└── .gitattributes                   eol=lf everywhere; three Windows machines
```

### `java/com/etio/ot/`

```
EtioApplication.kt   FROZEN. Startup order: context → config on disk → seed → warm-up.
MainActivity.kt      Splash, keep-screen-on, mic permission; resolves the persisted
                     theme and shows the tutorial or the app on the first frame.
core/                FROZEN. Clock (injectable now), Outcome, newId, msToMinutes.
ui/                  Compose screens + ViewModels. Nothing here awaits inference
                     except DelayCaptureViewModel, on purpose.
  Routes.kt          FROZEN. Every route string and argument, declared up front.
  EtioApp.kt         FROZEN. NavHost composing three per-slice graphs.
  theme/             EtioTheme.kt (colour, type, spacing, radius tokens),
                     Glass.kt (the one blurred-chrome modifier), Haptics.kt.
  caselist/          CaseListScreen/ViewModel, CaseCard, NextEventBar,
                     DelayBreachBanner, EventTimeDialog, SpineNav.
  events/            EventGrid — the out-of-order sheet.
  delay/             DelayCaptureScreen/ViewModel, DelayReviewCard, AiNav.
  messages/          MessagesScreen/ViewModel — four drafts as a 2x2, copy and share.
  checklist/         ChecklistDialog, ChecklistHost, ChecklistGateController.
  report/            ReportScreen/ViewModel, SafetyNav.
  settings/          SettingsScreen/ViewModel — theme choice, replay the tutorial.
  tutorial/          TutorialHost, TutorialViewModel, Spotlight — six steps over
                     the real screens, not a mock of them.
domain/              Pure Kotlin. No Android imports, no coroutines, no I/O.
  timing/            TimerEngine (spans), DayMetrics (the result type),
                     DayFlow (next action, focus, breach, send-for),
                     ScheduleProjector (what a stated delay does to the day).
  checklist/         ChecklistStateMachine — the deterministic safety gate.
  report/            EndOfDayReportBuilder — aggregation only.
ai/                  LlmEngine interface + ModelLocator, MediaPipeLlmEngine,
                     FakeLlmEngine, GemmaChatTemplate, PromptSource,
                     DelayClassifier (Job 1), MessageDrafter (Job 2),
                     MessageDraftCoordinator (Job 2 off the UI path),
                     DelayJsonValidator (the actual guarantee), SpeechCapture.
data/                config/ (ConfigProvider, ConfigModels),
                     local/ (EtioDatabase, Converters, dao/Daos.kt, entity/Entities.kt),
                     settings/SettingsStore.kt (DataStore — theme, tutorial flag),
                     model/Enums.kt, repository/ (Case, Delay, Checklist).
di/                  Manual DI — no Hilt, deliberately. ServiceLocator is a frozen
                     shell holding only the app context and clock; one module per
                     slice: CoreModule, AiModule, SafetyModule.
```

## Data model

Five Room tables, `version = 1`, `exportSchema = true` (schemas land in `app/schemas/`), `fallbackToDestructiveMigration()` — a schema change wipes local data rather than failing to launch twenty minutes before the pitch. All IDs are app-generated UUID strings; every foreign key is `ON DELETE CASCADE`; enums are stored **by name, not ordinal** (`data/local/Converters.kt`), so reordering an enum can never silently reinterpret existing rows.

| Table | Entity | Fields |
| --- | --- | --- |
| `cases` | `CaseEntity` | `id`, `caseNumber`, `theatreId`, `procedureName`, `surgeon`, `scheduledStartMs`, `scheduledDurationMin`, `status`, `orderIndex` |
| `events` | `EventEntity` | `id`, `caseId` → `cases`, `type`, `timestampMs`, `correctedFromEventId`, `supersededByEventId`, `source` |
| `delay_records` | `DelayRecordEntity` | `id`, `caseId` → `cases`, `createdAtMs`, `transcriptRaw`, `code`, `attributedDept`, `avoidable`, `estimatedMin?`, `note`, `modelConfidence`, `userEdited`, `fellBackToOther` |
| `checklist_runs` | `ChecklistRunEntity` | `id`, `caseId` → `cases`, `phase`, `itemsConfirmed`, `skipped`, `skipReason`, `completedAtMs` — unique on (`caseId`, `phase`) |
| `generated_messages` | `GeneratedMessageEntity` | `id`, `delayRecordId` → `delay_records`, `audience`, `body`, `generatedAtMs`, `copied` |

`transcriptRaw` is never discarded — it is the grounding evidence shown beside every model-assigned field, and it is what the messages screen quotes back.

**Not in Room, on purpose.** `SettingsStore` keeps `theme_mode` and `tutorial_completed` in a DataStore called `etio_settings`. Those belong to the person, not to the day: the demo reset wipes the database, and neither of them should come back with it — least of all the tutorial flag, which must never re-fire on stage.

### Enums (`data/model/Enums.kt`, frozen)

| Enum | Values |
| --- | --- |
| `CaseStatus` | `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` |
| `EventType` | `PATIENT_SENT_FOR`, `PATIENT_IN_ROOM`, `ANAESTHESIA_START`, `KNIFE_TO_SKIN`, `CLOSURE_COMPLETE`, `PATIENT_OUT`, `ROOM_CLEAN_START`, `ROOM_READY` — **ordinal order is the expected clinical order**, and the checklist gate relies on it |
| `EventSource` | `TAP`, `VOICE`, `INFERRED` |
| `DelayCode` | `SURGEON_LATE`, `ANAESTHESIA_DELAY`, `STERILE_SET_UNAVAILABLE`, `PATIENT_NOT_READY`, `CONSENT_INCOMPLETE`, `PREVIOUS_CASE_OVERRUN`, `PORTER_TRANSPORT`, `BLOOD_PRODUCTS`, `EQUIPMENT_FAILURE`, `ICU_BED_UNAVAILABLE`, `OTHER` |
| `Avoidability` | `AVOIDABLE`, `UNAVOIDABLE`, `UNCLEAR` — tri-state, because the model must be allowed to say it does not know |
| `Audience` | `WARD`, `FAMILY`, `SURGEON`, `ANAESTHESIA`; `demoOrder` is surgeon, family, ward, anaesthesia so the contrast lands |
| `ChecklistPhase` | `SIGN_IN` ← `PATIENT_IN_ROOM`, `TIME_OUT` ← `ANAESTHESIA_START`, `SIGN_OUT` ← `CLOSURE_COMPLETE` |
| `ThemeMode` | `SYSTEM`, `LIGHT`, `DARK` (`data/settings/SettingsStore.kt`) |

`DelayCode` is the closed set the LLM must emit verbatim, and `DelayCode.fromModelOutput()` coerces anything else to `OTHER`. Adding a value means editing `taxonomy.json` too — the prompt is built from there.

### Events are append-only

`EventEntity` is never updated in place. A correction inserts a new row pointing at the old one via `correctedFromEventId`, and the old row gets `supersededByEventId`. Every DAO query and `TimerEngine` read filters on `supersededByEventId IS NULL`. That audit trail is the answer to "how do we know the timestamps are real".

## Lanes and frozen files

The `di` and nav split is not ceremony — it is what lets three people work in parallel without editing the same files. `tools/check-boundaries.sh` encodes the whole arrangement and answers three questions against `main`: did you touch a frozen file, did you touch another slice's files, and is `USE_FAKE_LLM` still true?

| Lane | Owns | DI module | Nav graph |
| --- | --- | --- | --- |
| `feat/spine` | `data/local`, `data/config`, `CaseRepository`, `domain/timing`, `ui/caselist`, `ui/events`, `ui/theme`, `res/`, the Gradle files | `CoreModule` (config, settings, db, cases) | `caselist/SpineNav.kt` |
| `feat/ai` | `ai/`, `DelayRepository`, `ui/delay`, `ui/messages`, `prompts.json`, `taxonomy.json` | `AiModule` (LLM, ASR, delays, drafting) | `delay/AiNav.kt` |
| `feat/safety` | `domain/checklist`, `domain/report`, `ChecklistRepository`, `ui/checklist`, `ui/report`, `checklist.json` | `SafetyModule` (checklist) | `report/SafetyNav.kt` |

Dependencies point one way: AI and Safety may read Core; Core reads neither. Frozen files — `ServiceLocator.kt`, `Routes.kt`, `EtioApp.kt`, `EtioApplication.kt`, `MainActivity.kt`, `core/`, `Enums.kt`, `Entities.kt`, `AndroidManifest.xml`, `.gitattributes`, the script itself — change on `main`, with all three owners, or not at all.

```bash
bash tools/check-boundaries.sh
```

`ui/settings/`, `ui/tutorial/`, `data/settings/` and `tools/check-contrast.py` arrived after the lane lists were written, so the script reports them as "in no declared lane" — a warning, not a failure. They are spine-lane files; add the paths next time the script is touched on `main`.

## Three invariants worth defending in review

**1. Timers never touch inference.** `TimerEngine` is `object TimerEngine` with no Android or coroutine imports — a pure function of (cases, events, now), driven by a 1-second ticker in `CaseListViewModel`. If the model hangs, the clocks on the projector keep moving. `DayFlow` and `ScheduleProjector` sit beside it under the same rule.

**2. The model's output is never trusted.** `DelayJsonValidator` extracts the first balanced `{...}` (small models add fences and preambles), coerces unknown codes to `OTHER`, and **drops `estimated_min` unless a duration actually appears in the transcript** — the single most common hallucination in this task. It never throws and never surfaces a parse error; a failure becomes an `OTHER` the coordinator is asked to correct, flagged on the row as `fellBackToOther`. `MessageDrafter` applies the same guard to drafts: a message naming a duration the record does not have is replaced by the deterministic fallback.

**3. The checklist is model-free.** `ChecklistStateMachine.gate()` is the sole authority on whether an event may be marked. A phase blocks only events strictly after its trigger in `EventType` order, and is satisfied by every *critical* item confirmed, or by an explicit skip **with a reason**. Skipping is allowed but never silent — the reason is stored and appears in the report. Nothing in the automation touches it: no bulk confirm, no pre-checked items, no inference. An inferred `PATIENT_IN_ROOM` makes Sign In due exactly as a tapped one would.

## Demo path

`CaseListScreen` → tap the **single next-event button** at the bottom (the WHO dialog fires at `PATIENT_IN_ROOM` / `ANAESTHESIA_START` / `CLOSURE_COMPLETE`, hosted over the list by `ChecklistHost` — it is not a route) → **tap the mic** → transcript appears while she is still talking → `DelayReviewCard` with the verbatim transcript under every model-assigned field → **Notify**, which reveals four already-drafted messages as a 2x2, surgeon beside family → **report icon** in the top bar.

If the mic is refused or unavailable, "Type it instead" runs the identical classification path — the demo never dead-ends on a permission dialog.

The default screen shows only the focused case, the next-event button, and the top bar: theatre name, the date with `DAY COMPLETE` appended once every case is marked out, and one variance chip reading `on time`, `12 min behind` or `5 min ahead`, tinted green/amber/red. `All cases` expands the list; `Other event` opens the full grid for out-of-order marking; `Timer breakdown` expands the spans on a card. Long-press any marked event to correct its time.

**Long-press the theatre name** in the top bar to wipe and re-seed the day. Deliberately hidden, and it asks for confirmation — a reset control you can brush on stage is a reset control that ends a demo.

### First run, and settings

The first launch opens a six-step tutorial over the real UI, against a sandbox theatre holding one case (`OT1 · Demo · Dr Placeholder`). Every step is performed for real — including recording an actual delay — and `Modifier.spotlight` is how the coach marks find the live controls rather than a mock of them. Steps 2 and 3 advance on the database write itself: an event row appearing, then one superseded by a correction.

Finishing **or** skipping is the same promise — `tutorial_completed` is set and the sandbox is replaced by the seeded OT-2 day. That flag lives in DataStore, not Room, so **the demo reset never brings the tutorial back**. Replay it deliberately from Settings, which also holds the light/dark/system theme choice.

### The look

`ui/theme/EtioTheme.kt` holds every colour, type, spacing and radius token; screens read `Etio.colors` / `Etio.space` rather than hardcoding. No dynamic colour — the wallpaper on a loaner phone is not something we control. Dark is the target and what the palette is tuned against; `tools/check-contrast.py` prints the WCAG ratios for both themes, including the blurred and flat glass surfaces.

`Modifier.glass()` is the one blurred-chrome treatment, and it is for chrome only. `rememberEtioHaptics()` gives three weights used consistently — `tick` for marking an event, `medium` for recording started or stopped, `double` for a checklist phase completed — so the phone means something through a glove.

### What the app fills in for you

`DayFlow` and `ScheduleProjector` are pure Kotlin beside `TimerEngine` — list order, marked events and arithmetic, never the model:

| Behaviour | Rule |
| --- | --- |
| The primary button's label | The earliest unmarked event in the day, walking cases in list order — so room clean-up on case N stays reachable after case N+1 becomes active |
| Which case is on screen | The active one; once the last `PATIENT_OUT` is marked, the last case in the list, so the screen never goes blank mid-demo |
| Missing earlier events | Written as `EventSource.INFERRED`, spaced evenly between the last real mark and this one, surfaced as a dismissible "assumed at HH:MM — tap to set" chip |
| "Send for case N+1?" | Offered once `ROOM_READY` is marked, gone once `PATIENT_SENT_FOR` exists |
| Threshold breach | In room >30 min with no knife, turnover >25 min, or >15 min past a scheduled start (`DayFlow.*_BREACH_MIN`) — the notice carries the mic, and dismissal is keyed so it does not re-fire a second later |
| Revised start times | Every case from the delayed one onwards moves by the stated `estimated_min`, and only when one was actually spoken; a case already past its slot is measured from now, so its revised time is never in the past |

Job 2 starts the moment a `DelayRecord` is confirmed, on an app-scoped coroutine in `MessageDraftCoordinator`, so Notify is a reveal rather than a fifteen-second wait — and the drafting survives walking away from the capture screen.

### What the report admits to

`EndOfDayReportBuilder` attributes lost minutes per code and per department, using the spoken estimate when there was one and the measured idle span otherwise — `DelayAttribution.measured` records which, and the screen says so. `ReportViewModel` adds the two things minute totals do not admit to on their own: **compliance** (which WHO phases came due, were confirmed, were skipped and with what reason) and **provenance** (how many event timestamps the app inferred rather than observed). The screen is reactive, not a snapshot taken at open time.

## Tests

```bash
./gradlew testDebugUnitTest
```

Plain JVM unit tests, no device needed (`unitTests.isReturnDefaultValues = true` keeps `android.util.Log` from throwing on the fallback paths):

| File | Covers |
| --- | --- |
| `domain/TimerEngineTest.kt` | Spans, open spans measured to now, superseded rows ignored, next expected event |
| `domain/DayFlowTest.kt` | Next action across cases, focus during and after the day, breaches and send-for offers |
| `domain/ScheduleProjectorTest.kt` | Downstream shift by the stated minutes only, cases past their slot, summary wording |
| `domain/ChecklistStateMachineTest.kt` | Gating, critical-item satisfaction, skip-with-reason, progress |
| `domain/DelayJsonValidatorTest.kt` | Fences and preambles, unknown codes, hallucinated durations, empty output |
| `ai/DelayClassifierTest.kt` | Prompt construction from config, sampling params, failure falling back to `OTHER` |
| `ai/MessageDrafterTest.kt` | Per-audience prompts and rules, stripped labels, deterministic fallbacks, the duration guard |

## Non-goals (enforce these)

No ambient listening. No predictive re-sequencing. No multi-user sync, accounts, or roles. No EHR/HIS integration. No clinical decision support. No actual message sending — copy and share sheet only. No cloud anything. One theatre, one day.

The app declares **no `INTERNET` permission** — `RECORD_AUDIO` and a required microphone, nothing else. It is architecturally incapable of leaving the device.
