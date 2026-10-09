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

The script resolves each face through the Google Fonts CSS API rather than from a
hardcoded upstream path. Both upstream repos have since moved their files, and
space-grotesk no longer publishes a static SemiBold at all — only a variable font —
so the weight the theme asks for has to be instanced. Sources remain
Space Grotesk: https://github.com/floriankarsten/space-grotesk ·
Fira Code: https://github.com/tonsky/FiraCode · both SIL Open Font License 1.1.

Android resource names allow only lowercase letters, digits and underscores, so the
filenames above are not negotiable. Nothing but `.ttf`, `.ttc`, `.otf` or `.xml` may
sit in `res/font/` — the resource merger fails the build on any other file, which is
why this note lives in `docs/` rather than next to the fonts.
