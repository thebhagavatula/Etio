# Branch plan — three people, disjoint lanes, atomic merges

Three feature branches off `main`. Each owns a vertical slice — data, domain and UI together — so every person can install a working APK alone and demo their own piece. Nobody is blocked on anybody.

The scaffold has already been refactored so the three lanes do not overlap. That refactor is the reason these merges can be atomic; read *Why the lanes are actually disjoint* before you start moving files around.

---

## Branches

| Branch | Slice | Owner | PRD features |
| --- | --- | --- | --- |
| `feat/spine` | Case list, event marking, live timers, Room, build files | strongest on Android/Compose | F1, F2, F3 |
| `feat/ai` | MediaPipe, ASR, classification, four-audience drafting | most comfortable with the risky unknown | F4, F5 |
| `feat/safety` | WHO checklist, end-of-day report, Office Kit bridge | steadiest — this slice is the most self-contained | F6, F7, F8 |

**Assignment is by risk, not preference.** The AI slice is the hour-one fatal risk from the PRD (model won't load, OOM on a loaner phone, ASR mangles clinical nouns). It gets your strongest person and the earliest start. The safety slice is fully deterministic and testable without a device, so it survives a slow start or a person who has to sleep first.

## Setup

```bash
git checkout main
git pull

git checkout -b feat/spine  main && git push -u origin feat/spine
git checkout -b feat/ai     main && git push -u origin feat/ai
git checkout -b feat/safety main && git push -u origin feat/safety

git checkout main
```

Each person works on their branch only. Nobody commits to `main` except during an integration round.

---

## Ownership

Everything under `app/src/main/java/com/etio/ot/` unless noted.

### `feat/spine`

```
data/local/**                       entities, DAOs, database, converters
data/config/**                      ConfigProvider, ConfigModels
data/repository/CaseRepository.kt
domain/timing/**                    TimerEngine, DayMetrics
ui/caselist/**                      CaseListScreen, CaseCard, ViewModel, SpineNav
ui/events/**                        EventGrid
ui/theme/**
di/CoreModule.kt
assets/config/seed_cases.json
res/**
test/.../TimerEngineTest.kt
app/build.gradle.kts, build.gradle.kts, settings.gradle.kts, gradle/**
```

Spine owns the build files. If you need a dependency added, ask — do not add it yourself. Two people editing `libs.versions.toml` is the single most annoying conflict available.

### `feat/ai`

```
ai/**                               LlmEngine, MediaPipe, Fake, Classifier,
                                    Drafter, Validator, SpeechCapture
data/repository/DelayRepository.kt
ui/delay/**                         DelayCaptureScreen, ReviewCard, ViewModel, AiNav
ui/messages/**                      MessagesScreen, ViewModel
di/AiModule.kt
assets/config/prompts.json, taxonomy.json
test/.../DelayJsonValidatorTest.kt
```

### `feat/safety`

```
domain/checklist/**                 ChecklistStateMachine
domain/report/**                    EndOfDayReportBuilder
data/repository/ChecklistRepository.kt
ui/checklist/**                     ChecklistDialog, GateController, ChecklistHost
ui/report/**                        ReportScreen, ViewModel, SafetyNav
di/SafetyModule.kt
assets/config/checklist.json
test/.../ChecklistStateMachineTest.kt
```

### Frozen — changing these needs all three of you

```
di/ServiceLocator.kt                app context + clock, nothing else
ui/Routes.kt                        the navigation contract
ui/EtioApp.kt                       NavHost, composes the three graphs
EtioApplication.kt                  startup order
MainActivity.kt
core/**                             Clock, ids, formatters
data/model/Enums.kt                 the taxonomy and every other enum
data/local/entity/Entities.kt       the Room schema
AndroidManifest.xml
.gitattributes, .github/CODEOWNERS, tools/check-boundaries.sh
```

`Entities.kt` and `Enums.kt` are the two that will tempt you. Both are frozen **from the moment you branch** — the schema was designed against the PRD §8 data model and every slice reads it. If you genuinely need a column, that is a 60-second conversation and a commit on `main` that everyone rebases onto, not a change on your branch.

---

## Why the lanes are actually disjoint

Three files used to be edited by all three slices. They have been split so that is no longer true:

**Dependency injection.** `ServiceLocator` was one object holding every dependency. It is now a frozen shell holding the app context and clock, with `CoreModule` / `AiModule` / `SafetyModule` beside it — one per slice, one owner each. Dependencies point one way: AI and safety may read Core; Core reads neither.

**Navigation.** `EtioApp` used to declare all four destinations inline. It now calls three extension functions — `spineGraph()`, `aiGraph()`, `safetyGraph()` — each in a file its slice owns. Every route string lives in the frozen `Routes.kt`, so the spine branch can navigate to a screen the AI branch has not written yet.

**The checklist gate.** `CaseListViewModel` used to contain the gating logic, the dialog state and five checklist methods — safety-branch code living in a spine-branch file. All of it now sits behind `ChecklistGateController`, which safety owns. The spine branch's entire contact with the checklist is three calls:

```kotlin
if (!checklistGate.allows(caseId, type, marked)) return@launch   // in markEvent
checklistGate.onEventMarked(caseId, type)                        // after a mark
ChecklistHost(viewModel.checklistGate, snackbar)                 // in the screen
```

That surface is a contract. Treat it as frozen once you branch — if safety needs different behaviour, change it *inside* the controller.

---

## Working without the other slices

Each branch compiles and runs on its own from day one, because every screen already exists as real code on `main`.

- **`feat/spine`** — set `AiModule.USE_FAKE_LLM = true` locally and the delay path returns canned responses at realistic latency. **Do not commit that flip.** The pre-merge script fails the branch if you do.
- **`feat/ai`** — the case list, timers and seeded cases are all live on `main`, so you have a real case to attach a delay to on your first run.
- **`feat/safety`** — `TimerEngine`, `ChecklistStateMachine` and `EndOfDayReportBuilder` are pure Kotlin with no Android imports. Most of this slice can be built and tested with `./gradlew testDebugUnitTest`, no phone required.

---

## Integration rounds

Merge at fixed points, all three of you present, one branch at a time. Do **not** merge opportunistically when you happen to finish something.

Suggested points, mapped to the PRD build plan:

| When | Round | Goal |
| --- | --- | --- |
| End of the first Green Light window | R0 — smoke | Everyone merges whatever exists, even if thin. The point is to prove the merge mechanics work while it is cheap. |
| Before Evaluation Round 1 | R1 — spine + one classification | Demo the spine plus one working delay classification. Not messages yet. |
| Before the Red Light rotation | R2 — hardened | Job 1 validated, checklist state machine landed. Whoever sleeps first merges first. |
| Feature freeze | R3 — final | Everything. Nothing new merges after this — bug fixes on the demo path only. |

### Merge order within a round — always the same

```
feat/spine  →  main      (no dependencies; carries build files and the schema)
feat/ai     →  main      (depends on Core; highest-risk, so integrate it with time left)
feat/safety →  main      (depends on Core; smallest surface, safest to land last)
```

### The procedure

Each person, on their own branch, before the round:

```bash
git fetch origin
git rebase origin/main                 # rebase, not merge — keeps main's history linear
bash tools/check-boundaries.sh         # must exit 0
./gradlew assembleDebug testDebugUnitTest
git push --force-with-lease
```

Then, one at a time, in the order above:

```bash
git checkout main && git pull
git merge --no-ff feat/spine -m "Merge spine: <what landed>"
./gradlew assembleDebug testDebugUnitTest
git push
```

`--no-ff` keeps each slice as one identifiable merge commit, so `git revert -m 1 <sha>` backs out a whole slice cleanly if a merge turns out to have broken the demo path. That is the atomic part: a slice lands as one unit, or it is removed as one unit.

After `main` is green, everyone rebases onto it again before continuing.

### Pre-merge checklist

1. `bash tools/check-boundaries.sh` exits 0 — no frozen files, no other slice's files, `USE_FAKE_LLM = false`.
2. `./gradlew assembleDebug testDebugUnitTest` is green.
3. The app launches and **your** slice's demo path works on the actual loaner phone.
4. You rebased onto `origin/main` within the last few minutes, not an hour ago.

---

## Rules that are worth the friction

**Rebase your branch onto main. Merge your branch into main.** Never `git merge main` into a feature branch — it makes the history a braid and turns `git revert` on a bad slice into an archaeology exercise.

**Never commit the model file.** `.gitignore` already excludes `*.task`, `*.bin` and `*.tflite`. A 550 MB blob in the history cannot be removed in a hurry.

**Never force-push `main`.** `--force-with-lease` on your own feature branch after a rebase is fine and expected.

**Commit messages say what landed, not what you did.** `Job 1 validated JSON + OTHER fallback` beats `fix stuff`. At the fourth integration round you will be reading these to work out what is safe to revert.

**The Red Light hours produce JSON diffs, not Kotlin diffs.** Prompt, taxonomy and checklist tuning happens in `assets/config/*.json`, each file owned by exactly one slice. Tuning during Red Light should never touch a `.kt` file — if it does, something is hardcoded that should not be.
