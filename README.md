# Etio

**An operating-theatre day log that listens.**

Etio records what happened in a theatre and, more importantly, *why* time was lost. The coordinator marks events with one thumb while she walks. When something holds the list up, she says it out loud in her own words, and Etio turns that sentence into a structured record — cause, department, whether it was avoidable, how long — and drafts the four messages she would otherwise have typed to the ward, the family, the surgeon and anaesthesia.

Everything runs on the phone. The app declares no internet permission, so it is not merely offline-capable: it is architecturally incapable of sending your theatre's data anywhere.

---

## What you need

| | |
|---|---|
| **Phone** | Android 8.0 (API 26) or newer. A 2021-or-later mid-range or better is comfortable; the model runs on the GPU where the driver allows and falls back to CPU on its own. |
| **Free space** | ~1.2 GB — about 620 MB for the model file, the rest for the app and its data. |
| **Memory** | 4 GB RAM minimum, 6 GB comfortable. |
| **A computer** | Windows, macOS or Linux, with a USB cable. Needed once, to put the app and the model on the phone. |
| **Model file** | `gemma3-1b-it-int4.task` (~550 MB). Not bundled — see below. |

---

## Installing it

### 1. Get the tools

Install **Android Platform Tools**, which contains `adb`, the program that talks to your phone over USB.

- **Windows** — download the [SDK Platform Tools zip](https://developer.android.com/tools/releases/platform-tools), unzip it somewhere memorable such as `C:\platform-tools`, and open a terminal in that folder.
- **macOS** — `brew install android-platform-tools`
- **Linux** — `sudo apt install android-tools-adb` (or your distribution's equivalent)

Check it works:

```bash
adb version
```

### 2. Let your phone accept the connection

On the phone: **Settings → About phone → tap "Build number" seven times.** You will be told you are now a developer. Then **Settings → System → Developer options → enable "USB debugging"**.

Plug the phone into the computer. A dialog will ask you to allow USB debugging from this computer — tick "always allow" and accept it. Then confirm the phone is visible:

```bash
adb devices
```

You should see one device listed as `device`. If it says `unauthorized`, look at the phone's screen and accept the dialog.

### 3. Install the app

If you were given an APK file:

```bash
adb install -r etio.apk
```

If you are building it yourself, from the project folder:

```bash
./gradlew installDebug
```

On Windows without a Gradle wrapper jar, open the folder in Android Studio and press Run — it will build and install in one step.

### 4. Put the model on the phone

Etio does not ship with the language model. It is 550 MB, it belongs to Google under its own licence, and bundling it would make the app impossible to distribute sensibly. Download `gemma3-1b-it-int4.task` from [Kaggle](https://www.kaggle.com/models/google/gemma-3) or [Hugging Face](https://huggingface.co/litert-community), then:

```bash
adb shell mkdir -p /data/local/tmp/llm
adb push gemma3-1b-it-int4.task /data/local/tmp/llm/
```

That push moves half a gigabyte over USB and takes a minute or two. The app looks for the model in its own private storage first and falls back to this path, so this is all that is required.

**Without the model, Etio still works.** Every timer, every event mark, the WHO checklist and the whole report behave normally. Only the two things that need a model — reading a spoken delay into a structured record, and drafting the four messages — are unavailable, and the app says so plainly on screen rather than failing quietly.

### 5. Install the offline speech pack

Etio uses Android's own speech recogniser with offline mode forced on. The language pack has to be present or there is nothing to recognise with.

**Settings → Google → All services → Voice → Offline speech recognition → download "English (India)."**

The exact path varies by manufacturer; search your phone's settings for "offline speech" if it differs. English (US) or (UK) work too — Etio asks for Indian English and falls back to whatever is installed.

### 6. Open it

The first launch runs a short tutorial in a sandbox theatre with one demo case in it. You perform each step for real, including recording an actual delay. It takes about a minute, it can be skipped at any point, and it never appears again — not even after a data reset. You can replay it deliberately from Settings.

---

## Using it

### The day

The home screen shows one thing at a time on purpose: the theatre and how far behind the day is running, the case you are on, and one large button at the bottom carrying the **next event** — "Sent for", "Patient in room", "Knife to skin", and so on. Tap it and the clock is marked; it relabels itself to whatever comes next.

You never choose from a list of events unless you want to. "Other event" opens the full grid for marking out of order, and the day's remaining cases sit behind "All cases".

**If you skip an event**, Etio does not block you and does not lose the gap. Marking "Knife to skin" without having marked "Patient in room" writes the missing event with an assumed time, spread evenly between the last real mark and this one, and tells you: *"Anaesthesia start assumed at 09:42 — tap to set."* Assumed times are shown in italics with a tilde wherever they appear, and a long press corrects any timestamp.

When a case finishes, the next one becomes current on its own. When the room is declared ready, Etio offers to send for the next patient — one tap writes it.

### Recording a delay

Tap the microphone beside the next-event button, or the microphone inside the notice Etio raises when a span runs long. Then:

1. **Tap once to start.** The phone buzzes, a counter starts, and a bar moves with the room so you can see it is hearing you.
2. **Say what happened**, in your own words. Pause mid-sentence if you need to; it will wait.
3. **Tap again to stop.** It stops on its own after 30 seconds.

A few seconds later you get a structured card: cause, department, avoidable, expected delay, and a short note — with your exact words printed underneath it in monospace. Every field has a small pencil to correct it, and correcting the words themselves re-reads the sentence from scratch.

Fields carry a small **✓ HEARD** mark when the words behind them came out of your sentence. A field marked **NOT HEARD** contains something the model introduced, and the number or note has already been dropped or replaced with your own words. Etio will not keep a duration nobody said out loud.

Tap **Notify** and you get four messages — surgeon and family side by side, because the difference in how the same fact is told is the point. Each has copy, share and rewrite. Nothing is ever sent for you; Etio puts the words on the clipboard and gets out of the way.

### The safety checklist

At patient-in-room, anaesthesia start and closure complete, the WHO Surgical Safety Checklist appears. It looks deliberately unlike the rest of the app — flat, opaque, its own colour — because it is a stop rather than a convenience.

**Nothing on it is ever automated.** There is no confirm-all, nothing is pre-ticked, and no part of it is inferred from anything else. You confirm each item yourself. Progress is stated as a count — "3 of 7 confirmed" — and until the critical items are confirmed, the next event cannot be marked. A phase can be skipped, but only with a written reason, and that reason appears in the end-of-day report.

### The report

The report icon in the top bar opens the end of day: total minutes lost as a single number, a stacked bar broken down by cause with the department attributed on each row, WHO compliance per phase with any skips and their reasons, and where each timestamp came from — how many you tapped, how many the app filled in, how many were corrected.

Every lost minute traces back to something someone said. Where a duration was spoken it is used as stated; where none was, the span is measured from the clock and labelled as measured. The whole thing copies out as plain text.

---

## Settings

Reached from the top bar.

- **Appearance** — follow the system, or pin light or dark. Dark is the default and the one the app is tuned for.
- **Replay the tutorial** — runs the sandbox again. It clears the current day and re-seeds it afterwards, so it asks first.
- **Diagnostics** — what the model is actually doing on this phone: which backend loaded, how long it took, prompt sizes, classification and drafting latency, how often output needed a retry, and the correction rate — the share of records you had to edit before confirming. That last number is the honest answer to "how do you know it works", so nothing rounds it in the app's favour.

### Starting the day over

**Long-press the theatre name** in the top bar and confirm. This clears every event, delay, checklist and drafted message, and reloads the seeded list. There is no visible button for it, deliberately — a reset control that can be brushed against is one that will be.

It does not touch your theme, and it never brings the tutorial back.

---

## The demo day

Out of the box Etio seeds one theatre, OT-2, with five cases:

| | Scheduled | Procedure | Surgeon |
|---|---|---|---|
| 1 | 08:30 | Laparoscopic cholecystectomy | Dr. Rao |
| 2 | 10:00 | Open reduction internal fixation, left tibia | Dr. Menon |
| 3 | 12:15 | Total knee replacement, right | Dr. Menon |
| 4 | 15:00 | Inguinal hernia repair, mesh | Dr. Rao |
| 5 | 16:15 | Diagnostic laparoscopy | Dr. Iyer |

Times are set relative to the day you open it, so the list is always live.

---

## Tuning it without rebuilding

Everything adjustable lives in JSON, copied out of the app onto the phone's storage on first launch and read from there afterwards. Edit a file with any text editor on the phone, force-stop Etio, reopen it, and the change is live — no computer, no rebuild.

| File | What it controls |
|---|---|
| `prompts.json` | How the model is instructed for both jobs, the worked examples, per-audience tone rules, sampling settings, and the department alias table the grounding checks use |
| `taxonomy.json` | The eleven delay codes, their descriptions and their default departments |
| `checklist.json` | WHO checklist wording per phase, and which items are critical |
| `seed_cases.json` | The day's case list |

They are at `Android/data/com.etio.ot/files/config/` on the phone's internal storage. A malformed edit does not break anything: Etio falls back to the copy bundled inside the app and carries on.

---

## How it decides things

**Timers are arithmetic, never inference.** Every span, variance and turnover figure is pure system-clock subtraction. If the model hangs, stalls or was never installed, the clocks keep moving.

**The model is never trusted.** Its output is parsed defensively, coerced into the eleven-code taxonomy, and then checked against your transcript by deterministic code. A duration survives only if that number appears in what you said — as digits, as words, or as a phrase like "half an hour". Note text must be built from words you used. A department must be named or implied by an alias you said. Anything that fails is dropped or replaced with your own words, and marked on the card. No model is ever asked to check another model's work.

**The checklist is model-free.** It is the one part of the app no automation touches.

**Corrections never overwrite.** Changing a timestamp writes a new row pointing at the old one; the original is kept. That audit trail is the answer to "how do we know these times are real".

---

## If something goes wrong

**"Model unavailable" on the delay screen.** The model file is not where the app expects it. Re-run the push in step 4, then tap "Try loading again". Everything except classification and drafting keeps working meanwhile.

**"No offline speech recogniser on this phone."** The language pack is missing — step 5. Until then, "Type it instead" gives you the same structured card from typed text.

**"Microphone access is off."** Grant it when asked. If you have refused twice, Android stops asking, and the button becomes "Open settings" to take you straight there.

**"Didn't catch that — tap to try again."** Less than about fifteen characters came back. Etio does not send that to the model, because a fragment produces a confident-looking record built on nothing.

**Classification feels slow, or the screen sits on "Reading that…".** Open Settings → Diagnostics and look at the backend and the classify latency. On phones whose GPU driver refuses to reuse a cached prompt, each call re-sends the full instruction, which is slower but correct; the diagnostics screen says so outright.

**The phone got hot and things stopped responding.** Close Etio fully, let the phone cool, and reopen. If the model will not load afterwards, power the phone off properly — not a restart — and try again.

**You want to start clean.** Long-press the theatre name and confirm. To go further, Android's app settings will clear all of Etio's data, after which the tutorial runs again on next launch.

---

## What Etio does not do

It does not send messages — it writes them and puts them on your clipboard. It does not listen unless you tap the microphone, and there is no always-on mode to turn off. It does not sync, has no accounts and no cloud. It does not integrate with a hospital system. It gives no clinical advice and never reorders your list for you. It covers one theatre, one day.

---

## Built with

Kotlin · Jetpack Compose · Room · MediaPipe LLM Inference (Gemma 3 1B, int4) · Android's offline speech recogniser. Minimum Android 8.0, built against Android 15.

Licensed per the repository's licence. The Gemma model carries Google's own terms, which you accept when you download it.
