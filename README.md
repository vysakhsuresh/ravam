# Ravam

**രവം / रव — "sound, resonance."**

> Both sides of every call. Kept on your phone.

Ravam is an Android call recorder for people the existing ones forgot: Gulf and
South Asian families who live on BOTIM and WhatsApp, on mid-range phones, across
two SIMs and two legal systems.

It is free, open source, and has no accounts, no ads and no subscription.

---

## Status

**Pre-alpha.** The measurement engine and the recording path are written; 30
tests cover the parts that can be proved without a handset. Four experiments
still have to run on real hardware before the capture path is settled — they
are in [`PLAN.md`](PLAN.md), and any one of them can change the shape of the
project.

What works on paper and is **not yet proven on a device**: whether the far side
actually reaches the buffer, and whether a microphone foreground service can be
started for an *incoming* call on Android 14+. Treat both as open.

```
./gradlew :core:test      # 23 tests, no Android SDK needed, no device needed
./scripts/fetch-fonts.sh  # once, before building the app
```

`:core` is plain Kotlin and builds anywhere. `:app` is included in the build
only when an Android SDK is present, so the part worth testing is never gated
behind an SDK install.

## What it will do

- **Record both sides** of carrier calls and of app calls — BOTIM first, then
  WhatsApp, Telegram and Signal.
- **Save to the phone.** Nothing is uploaded, to anywhere, ever.
- **Tell you the truth about your phone.** On install, Ravam tests its own
  capture path and reports, per app, whether it will actually get both sides on
  *this* handset — before you rely on it.
- **Never hand you a silent file.** Every recording is verified for two voices
  while it is being made. If only your side is landing, you are told during the
  call, not a week later.
- **Know where you are.** In countries that require everyone's agreement to
  record, Ravam helps you ask for it and captures the answer — instead of
  quietly making you a criminal.

## What it will not do

- No cloud. No backup service. No sync. No account.
- No ads, no tracking, no analytics.
- No paid tier. "Premium" here means the quality, not the price.
- No pretending. If a feature does not work on your phone, Ravam says so.

## The honest part

Recording the other side of a call is not something Android wants to allow.
Every method that works needs you to grant a privilege the system would rather
you did not. Ravam will explain exactly what it is asking for and why, in plain
words, before it asks.

And recording a call is **illegal without the other person's agreement** in a
lot of places — the UAE among them, where it carries a prison term. Ravam tries
hard to keep you on the right side of that, but it is a tool, not a lawyer, and
the responsibility is yours. See [`docs/legal.md`](docs/legal.md).

## Prior art, credited

Ravam copies no one's code. It does stand on work others published first, and
those people deserve naming:

- **[BCR](https://github.com/chenxiaolong/BCR)** — the reference for doing this
  cleanly on rooted and system-app installs.
- **[ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder)** by
  kitsumed — the first FOSS non-root call recorder using Shizuku.
- **[CallVault](https://github.com/madkongo/CallVault)** — a fork of the above,
  re-architected over embedded ADB, and the most complete app in this category.

Ravam is not affiliated with or endorsed by any of them.

## Licence

To be decided, and genuinely open. Ravam is written from scratch and inherits no
licence from anyone — see [`PLAN.md`](PLAN.md) §0.

---

<sub>A [Layerbit Technologies](https://layerbit.co.in) project ·
[Support Layerbit](https://www.buymeacoffee.com/layerbit)</sub>
