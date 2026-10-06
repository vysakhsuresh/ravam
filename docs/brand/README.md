# Ravam — the mark

![Ravam](ravam-512.png)

**Two parties, and the part they share.**

Two equal rings overlap. The lens where they meet is filled — that is the
recording: the thing that only exists because both sides were there. Equal rings
because neither voice matters more than the other, and the whole product is the
promise that both of them made it into the file.

It is deliberately not a waveform, not an equalizer and not a signal fan. Those
read as "audio app". This reads as *both sides*, which is the actual claim.

## Grammar

Drawn in the Layerbit visual language so it sits beside the Layerbit logo as a
sibling rather than a stranger:

| | |
|---|---|
| Canvas | 64 × 64 source, baked to 108 × 108 for Android |
| Stroke | 4 units at source scale, round joins |
| Mark | `#F8FAFC` |
| Accent | `#00F2FE` — exactly one element, as in the Layerbit badge |
| Ground | `#0A0C10` |

## Files

| File | Use |
|---|---|
| `ravam-mark.svg` | source of truth — site, README, anywhere vector |
| `ravam-512.png` | raster preview |
| `../../app/src/main/res/drawable/ic_launcher_foreground.xml` | adaptive icon foreground |
| `../../app/src/main/res/drawable/ic_launcher_monochrome.xml` | Android 13+ themed icon |

The Android coordinates are **baked**, not group-transformed: source coordinates
are scaled ×1.35 and offset +10.8 into the 108 viewport. The furthest point of
the mark sits 31.05 from centre against a 36 safe radius, so it survives every
launcher mask — circle, squircle and rounded square alike. Verified by rendering
the exact committed path data through a circular clip, not by trusting the
arithmetic.

## Rules

- The accent colour appears **once**. Never recolour the rings.
- Never add a drop shadow to the mark inside the app. The site's logo glow is a
  web treatment; on a launcher it just muddies the edges.
- The monochrome layer is tinted by the system. Do not add colour to it.
