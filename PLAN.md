# Ravam — build plan

Written 2026-10-05. Based on a nine-agent research pass with an adversarial
verification stage. Where a claim was checked against AOSP source or a primary
document it is marked **confirmed**; where it is inference it says so.

---

## 0. The decision, made

**Ravam is written from scratch. Nothing is copied from anyone.**

Forking CallVault was on the table and was rejected. It would have bought ~100,000
lines and cost independence: GPL-3.0 forever, a permanent "this is a fork of" banner,
and a product defined by someone else's architecture.

### What "don't copy" means in practice

The distinction matters, so it is written down rather than assumed:

| | |
|---|---|
| **Not ours, never used** | Their source, their structure, their naming, their resources. No file in this repo derives from theirs. |
| **Nobody's property** | `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, `isClientSilenced()`, the AOSP audio policy, Shizuku's shell binding. These are platform APIs and public documentation. Using them is no more copying than using a `TextView`. |

Everything here is built from AOSP source and Android's own documentation. Ravam
therefore carries no inherited licence and is free to choose its own.

### What we are actually betting on

Not a longer feature list — that race is lost before it starts, and it is the wrong
race. The bet is a different idea about what a recording *is*:

> **A recording is not a file that happened. It is a measurement, with its evidence
> attached.**

Every app in this category treats capture as fire-and-forget: start the stream, write
bytes, hope. That is why they all ship the same failure — a file of the right length
containing one voice, or none, discovered days later.

Ravam does not hand over a recording it has not measured. Concretely:

1. **Nothing is green unless two voices were measured.** `TwoVoiceAnalyzer` decides,
   and it is a plain JVM module with 23 tests that run in seconds on any machine.
2. **Failure is caught while the call is still live.** `LiveIntegrityMonitor` watches
   as it records and distinguishes *the stream died* from *the far side went missing* —
   because those need different instructions and only one of them the user can fix.
   Nothing in this category does this.
3. **The device is tested, not assumed.** The probe measures what this handset really
   does, per app, before anyone relies on it.
4. **No `INTERNET` permission.** "Nothing leaves your phone" becomes a claim the
   manifest proves. Accepted cost: no cloud, no in-app updates, no downloaded models.
5. **A consent layer**, which nobody has at all.


---

## 1. The gaps that are actually real

Checked against CallVault's source and README, not guessed:

### 1.1 BOTIM is invisible to them
`grep -ri botim` across their entire tree returns **nothing**. Their README
names WhatsApp, Signal and Telegram. Their VoIP policy code is
package-agnostic, so BOTIM *may* work by accident — but it is untested,
unlisted, and nobody in the Gulf is their user. **This is the single clearest
opening, and it is your own daily use case.**

### 1.2 There is no legal layer at all
Their code contains this comment, in `VoipAppPolicy.kt`:

> *"a work call in a two-party-consent country"*

They know the problem exists. Their entire answer to it is a per-app on/off
switch. There is no jurisdiction detection, no warning, no consent capture, no
mention of Article 44 or any equivalent anywhere in 100,000 lines.

**This is the original idea from the planning conversation, and it is
unoccupied.** It is also the thing that matters most to a Gulf user, who is one
recording away from a six-month prison term.

### 1.3 Carrier Wi-Fi calling is not covered
Their README: *"Carrier Wi-Fi calling (VoWiFi/VoLTE) is **not** covered."*
VoWiFi is heavily used in both India and the UAE. The reference phone for this
project shows VoLTE active on both SIMs.

### 1.4 A call that starts too early is lost
Their README: *"an app call that starts before CallVault is ready is lost
rather than recorded late — the routing is fixed the moment the call begins."*
That is a real, admitted reliability hole.

### 1.5 Device coverage is thin and untested in the open
App-call capture is verified on exactly two handsets — OnePlus 12 and Galaxy
S24 FE. No Motorola, no Xiaomi, no Realme, no Oppo, no Vivo. And there is no
in-app capability test, so a user on an unlisted phone finds out by losing a
call that mattered.

### 1.6 They ship `android.permission.INTERNET`
Confirmed in their manifest, alongside `REQUEST_INSTALL_PACKAGES`. They need it
for Drive backup, model downloads and in-app updates — all reasonable. But it
means they can never make the claim a manifest can prove.

**Ravam shipping with no `INTERNET` permission at all** makes "nothing leaves
your phone" machine-verifiable by F-Droid, by any APK scanner, and by any
sceptic with `aapt`. The cost is real and must be accepted deliberately: no
in-app updates, no model downloads, no Drive. Transcription models would have
to ship in the APK or be side-loaded by the user.

---

## 2. How this actually works, at the API level

### 2.1 The two gates

Every "why can't my recorder hear the other person" question is two questions:

| | What it decides | Where it lives |
|---|---|---|
| **Gate 1** | Whether your `AudioRecord` gets real PCM or **silence** | AOSP audio policy — same on every device |
| **Gate 2** | Whether the far end is **physically present** in that stream | The audio HAL — per OEM, per chipset |

Gate 1 fails *silently*: `startRecording()` succeeds, the file is the right
length, and it contains digital zero. That is the single largest source of
one-star reviews across this entire app category.

### 2.2 The accessibility carve-out — confirmed in source

`AudioPolicyService::updateUidStates_l()`, in
`frameworks/av/services/audiopolicy/service/`:

```cpp
if (isA11yOnTop) {
    if (source == AUDIO_SOURCE_VOICE_RECOGNITION || source == AUDIO_SOURCE_HOTWORD) {
        allowCapture = true;          // not gated on in-call or privacy-sensitive
    }
}
```

A UID that is a **bound AccessibilityService**, in process state
`TOP..BOUND_FOREGROUND_SERVICE`, recording on `VOICE_RECOGNITION`, is **never
silenced** — not during a cellular call, not while BOTIM holds a
privacy-sensitive capture.

**Verified present and semantically identical on Android 11, 12, 13, 14, 15, 16
and 17.** (An earlier claim that it was *byte*-identical was wrong — the text
differs across versions, the behaviour does not.)

This is what Cube ACR is doing. Its two-APK split is a **Google Play policy
artefact** — Play bans accessibility for call recording, so the service has to
live off-Play. Distributing outside Play, one APK is enough.

**The catch:** this only stops the framework muting you. It does *not* route the
far end into your buffer. That is Gate 2, and on most phones the other person
arrives only as leakage through the earpiece.

### 2.3 The privileged path — better, and what CallVault uses

The shell UID (`com.android.shell`, uid 2000) holds
`CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`,
`CAPTURE_MEDIA_OUTPUT`, `CALL_AUDIO_INTERCEPTION` and `MODIFY_AUDIO_ROUTING` —
**confirmed against both the manifest and the privapp whitelist.**

`CAPTURE_VOICE_COMMUNICATION_OUTPUT` **was added in Android 14.** It is the one
permission that legitimately unlocks VoIP far-end capture, via
`AudioMixingRule.Builder.voiceCommunicationCaptureAllowed(true)` plus
`allowPrivilegedPlaybackCapture(true)` — the latter ignores an app's own opt-out.

Reaching shell UID without root is done either through **Shizuku** or through
**embedded ADB** (pair once on the phone itself, no PC). CallVault does the
latter, which is why it needs no companion app.

### 2.4 The landmine: Android 14 "while-in-use"

`RECORD_AUDIO` is a while-in-use permission. You **cannot start a
`microphone`-typed foreground service while the app is in the background** —
and an incoming call is, by definition, the app being in the background.

`SYSTEM_ALERT_WINDOW` is **not** on the exemption list. The exemptions are
narrow: a system component starting the service, widget interaction,
notification interaction, a PendingIntent from a visible app, a device-owner
DPC, a `VoiceInteractionService`, or the privileged
`START_ACTIVITIES_FROM_BACKGROUND`.

There is a plausible escape that the research found and nobody has tested:
`system_server` binds an AccessibilityService with **`BIND_INCLUDE_CAPABILITIES`**,
documented as granting *"while-in-use access such as location, camera,
microphone from background."* If that holds, the accessibility path survives.
If it does not, the accessibility tier only works for outgoing calls — which is
the minority of the use case.

**This is spike E3 and it is a go/no-go.**

### 2.5 The other landmine: `WHILE_AWAKE`

The same bind uses `BIND_FOREGROUND_SERVICE_WHILE_AWAKE`. Hold the phone to
your ear, the proximity sensor blanks the screen, and the process state may drop
out of the `TOP..BOUND_FOREGROUND_SERVICE` band — at which point capture is
**silenced mid-call**.

This is a concrete, mechanical explanation for "recordings truncate", which
nobody in this category has published. It is spike E1.

---

## 3. The spikes — run these before writing product code

Reference device: **Moto G84 5G, Android 15**, dual SIM (Etisalat UAE / Jio
India). Near-stock Android, minimal OEM audio customisation — so a result here
is more likely to generalise than one from a Samsung or Xiaomi.

| # | Experiment | Decides |
|---|---|---|
| **E0** | `adb shell dumpsys package com.catalinagroup.callrecorder \| grep userId=` (and `.helper`); `adb shell dumpsys media.audio_policy \| grep -A6 "UID Policy"` | Whether Cube shares a UID — one APK or two |
| **E1** | Re-run the working BOTIM recording on **earpiece**, with the **screen allowed to blank**, and on **Bluetooth** | Whether the observed result generalises, and whether `WHILE_AWAKE` silences us |
| **E2** (key) | Shizuku/ADB throwaway: dynamic `AudioPolicy`, `LOOPBACK\|RENDER`, `voiceCommunicationCaptureAllowed(true)` + `allowPrivilegedPlaybackCapture(true)`, dump PCM during a **BOTIM** call | Whether the deterministic VoIP path works for BOTIM specifically |
| **E3** | Can a **backgrounded** app start a `microphone` FGS and get non-zero PCM on Android 14+? Test at targetSdk 34 and 30 | Whether the accessibility tier works for **incoming** calls at all |

**E1 first.** It is free, it needs no code, and it reconciles the one piece of
real-world evidence this project has against the theory. The theory predicts the
far side should be faint on earpiece; the observed result was *both sides clear*.
If that was on speakerphone, the theory holds and the result does not generalise.
If it was on the earpiece, something else is happening and we need to know what.

**Then E3**, because it is cheaper than E2 and can invalidate a whole tier.

**Then E2**, the prize. If BOTIM's far end comes out clean through a privileged
dynamic `AudioPolicy`, Ravam has a deterministic, speakerphone-free, root-free
BOTIM recorder — and CallVault, which has never tested BOTIM, does not.

---

## 4. The capture ladder

Whatever the spikes say, the engine takes the same shape: a ranked ladder,
probed at runtime, with **the active tier shown in the UI during every
recording**.

```
1. PRIVILEGED_ROOT    root / system app      — most reliable, fewest users
2. PRIVILEGED_SHELL   Shizuku or embedded ADB — deterministic on Android 14+
3. ACCESSIBILITY      a11y + VOICE_RECOGNITION — wide reach, Gate 2 lottery
4. SPEAKERPHONE       mic only                — always works, clearly labelled
```

Never silently fall back. The user is told which tier is active and what it
means, every time.

---

## 5. v1 scope

### Ship
1. **`isClientSilenced()` wired in from the first commit.** One API call that
   kills the entire silent-file failure class. Abort and tell the user; never
   leave a file that only *looks* like a recording.
2. **"Test my phone".** Plays a tone, records, measures per-channel energy,
   gives a plain verdict per app: *"Carrier: both sides. BOTIM: both sides.
   WhatsApp: your side only."* Runs on install and after every OS update.
   Cached on `Build.FINGERPRINT`.
3. **Both-sides verification per recording.** VAD + log-RMS histogram —
   bimodal with two speakers, unimodal with one. Three honest states:
   *Both sides / Your side only / Couldn't tell.*
4. **Crash-safe incremental writes.** A process kill mid-call still leaves a
   playable file.
5. **The consent layer** (section 6). This is the differentiator.
6. **BOTIM as a first-class target**, named and tested, not inferred.

### Cut from v1
Encryption at rest, transcription, summaries, Drive backup, cloud anything.

---

## 6. The consent layer

The original proposal was to encode the user's risk acceptance into the
recording's filename (`APC-Y_UD-AR`). **Do not build that.** The verification
pass was unambiguous: it is a strictly dominated design.

- UAE Article 44 requires **mens rea**. Good faith is one of the few real
  defences a UAE court entertains.
- A filename stating the user was warned it was a crime and proceeded anyway is
  a durable, timestamped, machine-readable confession — attached to the evidence
  itself, on a device that can be seized, readable in a forensic file listing
  before anyone plays a second of audio.
- Because the flag was to be user-editable, it is **worthless as proof of the
  good thing** and **fully effective as proof of the bad thing**.

### Build instead

1. **Nothing about warnings, risk or refusal is ever written to disk.** Not the
   filename, not a sidecar, not MP4 tags, not SharedPreferences. Onboarding
   state is a bare `onboarding_version = N` with no legal semantics.
2. **Keep the warning, lose the acceptance.** Both buttons choose a *recording
   mode*. Neither says "I accept the risk."
3. **Never block the app.** Jurisdiction sets a default — `AUTO` / `ASK` /
   `OFF` — never access. One tap overrides.
4. **ConsentAssist — the actual deliverable.** Recording starts into a pre-roll
   buffer so the consent exchange lands *inside the file*. An in-call card shows
   the exact sentence to say, in large type. *"They agreed"* keeps from buffer
   start and writes one positive ledger row. *"Stop & delete"* discards
   everything and writes nothing.
5. **Positive-only ledger**, keyed by contact, with a `script_version` so you
   can show what the person was actually told. **No negative rows, ever.**
6. **Jurisdiction resolver**, union rule: network country of the subscription
   carrying the call + the remote number's region via `libphonenumber` + SIM
   home countries as a weak tiebreak. **Any all-party member in the union →
   `ASK`.** For VoIP with no number, fall back to device network country and say
   in the UI that the other party's country is unknown. **Never request location
   permission.**
7. **Ship Saudi Arabia as all-party.** Defaulting it to one-party would
   auto-record Gulf users into a one-year offence.
8. **Filenames for humans only:** `2026-10-05 19:42 Rahul (BOTIM).m4a`.

### Before any Gulf-facing wording ships
Buy one hour from a UAE-qualified lawyer. Four questions:
1. Does a pre-act acknowledgement of illegality aggravate an Article 44 case?
2. Is an in-call spoken "yes, that's fine" sufficient consent under Article 44?
3. What is the developer's own exposure for distributing the tool?
4. Does amicable settlement (available for Article 44) change the calculus?

---

## 7. Deadlines

Three clocks are running, and all three are against us.

1. **Android 17 shipped June 2026.** Under Advanced Protection Mode it can
   **revoke accessibility** from any app not declaring `isAccessibilityTool` —
   with no user override. Opt-in today. Tier 3 of the ladder is on a platform
   feature Google is actively walling off.
2. **Google's mandatory developer verification.** Live in Brazil, Indonesia,
   Singapore and Thailand since September 2026; **global in 2027.** It applies
   to *all* install paths on certified devices, raw sideloading included. Legal
   name, address, government photo ID, USD 25. Anonymous distribution has
   roughly a year left, and F-Droid has publicly called it existential.
3. **Android 13 Restricted Settings.** A sideloaded APK **cannot** enable an
   Accessibility Service or Notification Listener until the user manually
   unblocks it in App info. **App-store installs, F-Droid included, are
   exempt.** This is a conversion killer for raw APKs and a strong argument for
   leading with F-Droid.

---

## 8. Corrections to earlier assumptions

Recorded so nobody re-derives them wrongly.

| Earlier claim | Correction |
|---|---|
| Cube ACR is a weak 2.3 stars incumbent | **4.1 stars, 909K reviews, 50M+ installs** on Play. The 2.3 was PissedConsumer, four reviews. |
| India is a one-party-consent country | "One-party consent" is American framing. India has **no such statute**. *Vibhor Garg v. Neha* (2025) concerned **admissibility** between spouses, not general legality. Legality separately engages the Telegraph Act s.25, the IT Act, and post-*Puttaswamy* privacy. |
| A spoken announcement makes recording legal in an all-party country | It does **not**. Build it because it is decent and useful, never tell a user in Dubai it makes them lawful. |
| The carve-out is byte-identical across versions | Semantically identical. The text differs. |
| Cube is a weak competitor | The real competitor is **CallVault**, and it is strong. |

---

## 9. Open questions

1. **E1 result** — earpiece, blanked screen, Bluetooth. Blocks the architecture.
3. **Accept the no-`INTERNET` constraint?** It is the strongest trust claim
   available and it costs in-app updates and on-device transcription models.
4. `ravam.app` / `ravam.in` availability — unverified, needs a registrar check.
5. `github.com/ravam` as an **org**, before the namespace goes.
