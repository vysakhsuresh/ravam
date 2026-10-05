# Ravam — design system

Derived from the **Layerbit** web design system (`vysakhsuresh/layerbit-site`,
`css/base.css`) and translated for Jetpack Compose / Material 3.

The Layerbit look is dark, glassmorphic and precise — near-black base, a single
cold sky-blue accent, generous blur, and a geometric sans. It reads as a serious
instrument rather than a consumer toy, which is exactly right for an app whose
whole pitch is *"this one tells you the truth."*

---

## 1. Colour

Taken verbatim from `css/base.css :root` so the app and the site stay one brand.

```kotlin
// ui/theme/Color.kt
object RavamColors {
    // Surfaces
    val BgBase        = Color(0xFF0A0C10)   // --bg-base
    val BgElevated    = Color(0xFF1A1E2D)   // top of --bg-gradient
    val CardBg        = Color(0x9914161E)   // --card-bg      rgba(20,22,30,.6)
    val CardBorder    = Color(0x14FFFFFF)   // --card-border  rgba(255,255,255,.08)
    val CardHoverBg   = Color(0x991E2230)   // --card-hover-bg
    val CardHoverEdge = Color(0x6638BDF8)   // --card-hover-border

    // Text
    val TextMain      = Color(0xFFF8FAFC)   // --text-main
    val TextMuted     = Color(0xFF94A3B8)   // --text-muted
    val TextFaint     = Color(0xFF64748B)   // --log-timestamp

    // Accent
    val Accent        = Color(0xFF38BDF8)   // --accent-main
    val AccentGlow    = Color(0x8038BDF8)   // --accent-glow
    val NeonStart     = Color(0xFF00F2FE)   // --neon-gradient from
    val NeonEnd       = Color(0xFF4FACFE)   // --neon-gradient to

    // State
    val Success       = Color(0xFF22C55E)   // --success
    val Warning       = Color(0xFFEAB308)   // --warning
    val Danger        = Color(0xFFEF4444)   // --danger
    val SuccessBg     = Color(0x1A22C55E)
    val WarningBg     = Color(0x1AEAB308)
    val DangerBg      = Color(0x1AEF4444)

    // Support button (amber, from .premium-bmc-btn)
    val CoffeeEdge    = Color(0x4DFFC107)   // rgba(255,193,7,.3)
}

val NeonGradient = Brush.linearGradient(
    listOf(RavamColors.NeonStart, RavamColors.NeonEnd)
)

// Page background — radial, brighter at the top, matching --bg-gradient
val PageBackground = Brush.radialGradient(
    colors = listOf(RavamColors.BgElevated, RavamColors.BgBase),
    center = Offset(0.5f, 0f),
    radius = 1400f
)
```

**Dark only, deliberately.** Layerbit has no light theme and Ravam does not
need one — this is an app you open in a hurry, often at night, and a single
surface treatment is one less thing to get wrong.

### The three verification states

The most important colour decision in the app. These three words appear on
every recording and they must be legible at a glance:

| State | Colour | Label |
|---|---|---|
| Both voices present | `Success` #22C55E | **Both sides** |
| Only the local mic | `Warning` #EAB308 | **Your side only** |
| Not determinable | `TextMuted` #94A3B8 | **Couldn't tell** |

Never green unless two voices were actually measured. The whole product is that
badge being honest.

---

## 2. Type

Both faces are SIL OFL and ship inside the APK — no network fetch, consistent
with having no `INTERNET` permission.

| Role | Face | Weight |
|---|---|---|
| Display, headings, buttons | **Space Grotesk** | 600 / 700 |
| Body, labels | **Space Grotesk** | 400 / 500 |
| Durations, timestamps, file sizes, diagnostics | **Fira Code** | 400 |

```kotlin
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular,  FontWeight.Normal),
    Font(R.font.space_grotesk_medium,   FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold,     FontWeight.Bold),
)
val FiraCode = FontFamily(Font(R.font.fira_code_regular))
```

Fira Code for anything numeric that sits in a column — call durations and
timestamps in a list must not jitter as the digits change. That is the entire
reason it is here.

---

## 3. Surfaces

The Layerbit card is the one component everything else is built from: a
translucent dark panel, a hairline white border, and a real backdrop blur.

```kotlin
@Composable
fun RavamCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Column(
    modifier
        .clip(RoundedCornerShape(16.dp))
        .background(RavamColors.CardBg)
        .border(1.dp, RavamColors.CardBorder, RoundedCornerShape(16.dp))
        .padding(20.dp),
    content = content,
)
```

- Corner radius: **16dp** cards, **30dp** pills, **12dp** inputs.
- Border: always exactly **1dp**, always a low-alpha white. Never a solid line.
- Blur: use `Modifier.blur` / `RenderEffect` on API 31+; below that fall back to
  a flat `#14161E` — the layout must never depend on blur being available.
- Elevation: **none.** Depth comes from translucency and border, not shadow.
  The one exception is the support pill, which keeps Layerbit's
  `0 8px 25px rgba(0,0,0,.5)`.

---

## 4. Motion

Layerbit's signature easing, carried over:

```kotlin
val LayerbitEase = CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f)  // slight overshoot
val Quick   = tween<Float>(180, easing = FastOutSlowInEasing)
val Settle  = tween<Float>(400, easing = LayerbitEase)
```

Use the overshoot for things that *arrive* — a card appearing, a badge
resolving. Never for anything during a live call: the in-call surface must be
still. A recording indicator that bounces while someone is talking is a bug.

---

## 5. Standard furniture

### Support button
Lifted from `.premium-bmc-btn`. Lives in Settings, not floating over the app.

> ☕ **Support Layerbit** → `https://www.buymeacoffee.com/layerbit`

Glass pill: `CardBg` fill, `CoffeeEdge` 1dp border, 30dp radius, Space Grotesk
600, blur 12. Shown once, in one place. Never on the recording screen, never
near a call, never as an interstitial.

### Get help
> **Get help** → `https://layerbit.com/contact.html`

### Powered by
Footer of the About screen, mirroring the site:

> © 2025–2026 **Layerbit Technologies**
> About · Privacy · Terms · Contact

### Fork attribution — required, not optional
If Ravam builds on CallVault, its licence requires a **prominent** fork notice
and a visible link. Put it in About, above the Layerbit footer, in `TextMain`
not `TextMuted`:

> Ravam is a fork of **CallVault**, which is itself a fork of
> **ShizuCallRecorder** by kitsumed. Not affiliated with or endorsed by either.
> → github.com/madkongo/CallVault · github.com/kitsumed/ShizuCallRecorder

### Icons
**Lucide**, matching the site. `com.composables:lucide-icons` or the SVGs
imported as vector drawables. Stroke 1.5–2dp, never filled.

---

## 6. Layout rules

- Side gutter **20dp** phone, **32dp** tablet.
- Touch targets **≥48dp**. This app gets used one-handed, mid-call, in a hurry.
- Tested at 360dp, 412dp and 800dp wide. The reference phone is 412dp.
- Text contrast **≥4.5:1** against its actual surface — `TextMuted` #94A3B8 on
  `BgBase` #0A0C10 is 7.4:1 and passes; do not let it drift darker.
- One accent per screen. If two things are blue, neither is important.

---

## 7. Tone

The words are part of the design, and they follow the same rule as the colour:
**never claim more than was measured.**

| Don't | Do |
|---|---|
| "Recording saved!" | "Both sides recorded · 4:12" |
| "Something went wrong" | "Only your voice was captured on this call" |
| "Enable accessibility to continue" | "Android only lets a few kinds of app hear a call. Here's the one setting that allows it, and what it means." |
| "Recording is illegal in your country" | "In the UAE, everyone on the call has to agree first. Here's what to say." |

No exclamation marks. No emoji in product strings. English only for v1 —
the same reasoning as Festa: a half-translated legal warning is worse than an
untranslated one.
