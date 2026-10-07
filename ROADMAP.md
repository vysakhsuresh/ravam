# Ravam — roadmap

Where the app is, and what each phase delivers. A phase is **done** only when its
"proven by" line is true — on a real device where that's what it takes.

## Where we are now

Built and **testable without a handset** (39 passing tests in `:core`):
- `TwoVoiceAnalyzer` — both-sides verdict from mono or stereo audio.
- `LiveIntegrityMonitor` — catches the far side dropping out mid-call.
- `PcmSink` — crash-safe writing + orphan recovery.
- `JurisdictionResolver` — strictest-rule-in-the-union, incl. the dual-SIM Gulf case.

Built, **not yet device-proven** (`:app` — compiles in Android Studio, not here):
- Recording service with live verdict in the notification.
- Call detection (telephony + audio-mode for VoIP).
- Capture tier ladder + runtime selection.
- Full UI: Home (test-record), Recordings (playback, search, star), "My phone" self-test.
- Consent + jurisdiction layer with the warn-and-decide dialog.
- Layerbit brand footer with the in-app Get Help dialog.

## The honesty rule

Every phase carries the same non-negotiable: **the app never claims a capability it
has not measured.** Green means two voices were measured, here, now. That rule is the
product.

## Phases

### Phase 0 — Spikes (de-risk on the real phone)
| | |
|---|---|
| **Do** | E1: BOTIM on earpiece / screen-off / Bluetooth. E3: can a backgrounded app start mic capture for an *incoming* call? |
| **Proven by** | You running them on the G84 and reporting what survived |
| **Why first** | Either can reshape the capture architecture |

### Phase 1 — Carrier calls, both sides, bulletproof *(mostly built)*
| | |
|---|---|
| **Do** | Finish accessibility-tier wiring; verify crash-safety and live monitor on device |
| **Proven by** | A 40-min carrier call: both sides, survives a force-kill, honest badge |

### Phase 2 — BOTIM / VoIP (the hard unlock) *(scaffolded: `ShellCapture`)*
| | |
|---|---|
| **Do** | E2: shell-UID → `CAPTURE_VOICE_COMMUNICATION_OUTPUT` → dynamic `AudioPolicy`; clean on-device Shizuku pairing |
| **Proven by** | BOTIM both sides on the G84, no speakerphone, no root |
| **Risk** | If E2 fails, VoIP falls back to the accessibility lottery — replan |

### Phase 3 — Consent & jurisdiction *(built; needs device signals verified)*
| | |
|---|---|
| **Do** | Confirm SIM + dialled-number signals read correctly on the dual-SIM G84 |
| **Proven by** | Dialling +971 from the India SIM triggers the ask-first dialog |

### Phase 4 — Premium polish
| | |
|---|---|
| **Do** | Waveform scrubbing, per-call notes, export, retention rules |
| **Later / optional** | On-device transcription (a 190 MB+ model — collides with no-INTERNET, so user-initiated side-load, never default) |

### Phase 5 — Distribution
| | |
|---|---|
| **Do** | Signed APK, GitHub Releases, F-Droid (also sidesteps Restricted-Settings friction) |
| **Deadline** | Google developer verification — global 2027. Register before it bites. |

## Legal posture (settled — see docs/legal.md, PLAN.md §6)

- **Model:** safe for the developer; available to an informed user at their own risk.
- **The app never deceives** the OS or the other party — recording stays visible.
- **India-first**, one-party-ish, frictionless. Strict regions get warn-and-decide.
- **Nothing incriminating is ever written to disk** — no "accepted risk" flag, anywhere.
- **Cautious wording**, no legal claims → no lawyer needed now. Buy one hour of
  UAE-qualified time before any Gulf-specific legal text or Gulf marketing. Not before.

## The loop

This is not "come back when done." Each phase: I build → you test on the G84 → you
report → I fix. The faster that loop turns, the faster it lands. The one change that
speeds it most isn't a bigger model — it's letting the build compile in this environment
(the Android SDK host, blocked today), so errors are caught before they reach you.
