# Fonts

Ravam uses two SIL OFL faces, bundled in the APK rather than fetched at runtime —
the app has no `INTERNET` permission and never will.

These binaries are **not committed**. Run `scripts/fetch-fonts.sh` once, or drop them
in by hand with exactly these names:

| File | Face | Weight |
|---|---|---|
| `space_grotesk_regular.ttf`  | Space Grotesk | 400 |
| `space_grotesk_medium.ttf`   | Space Grotesk | 500 |
| `space_grotesk_semibold.ttf` | Space Grotesk | 600 |
| `space_grotesk_bold.ttf`     | Space Grotesk | 700 |
| `fira_code_regular.ttf`      | Fira Code     | 400 |

Sources — Space Grotesk: https://github.com/floriankarsten/space-grotesk ·
Fira Code: https://github.com/tonsky/FiraCode · both SIL Open Font License 1.1.

Android resource names allow only lowercase letters, digits and underscores, so the
filenames above are not negotiable.
