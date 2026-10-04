# Lanyue · 蓝阅

[中文](README.md) · [English](README.en.md)

> Ever tried to open a `.md` file on your phone, only to find no decent reader in the app store?
> Or got buried under those 100 MB "readers" that push a subscription before page one and drop an ad mid-sentence?
> **Lanyue exists to end exactly that.**

![APK](https://img.shields.io/badge/APK-481%20KB-blue)
![Permissions](https://img.shields.io/badge/permissions-none-brightgreen)
![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84)
![License](https://img.shields.io/badge/license-MIT-blue)

**481 KB. Zero permissions. Zero network.** And yet it has everything that matters:
math rendering, syntax highlighting, tables, images, table of contents, search, reading-position memory,
a lightweight editor, and PDF / long-image export.

How small is that? — **about one fifth of a random screenshot sitting in your phone.**

| | Size |
|---|---|
| Lanyue | **481 KB** |
| One screenshot from your phone | 1–3 MB (2–6× bigger) |
| A typical "all-in-one reader" | 30–120 MB (60–250× bigger) |

## Who should *not* use Lanyue

Let's get this out of the way first, so you don't install it for nothing:

- You want multi-tab, cloud sync, a plugin marketplace, AI autocomplete, collaborative comments → **none of that is here.**
- You want an online document editor → Lanyue only does one thing: show a local `.md` file properly.
- You will be annoyed by "few features" → true, it does one thing, but it does that thing thoroughly.

## What it actually does

- **Math**: KaTeX with the full font set plus mhchem, supporting all four delimiters (`$…$`, `$$…$$`, `\(…\)`, `\[…\]`),
  including `$$…$$` embedded mid-paragraph; `*` and `_` inside math are never eaten as emphasis.
- **Code**: Prism with 19 built-in languages (GLSL, Rust, Go, SQL, YAML, Bash, …), language labels,
  one-tap copy, and horizontal scrolling for long lines.
- **Full GFM**: tables (wide ones scroll), task lists, strikethrough, footnotes, autolinks,
  `==highlight==`, sub/superscript, and a collapsible front-matter card.
- **Images**: relative sibling paths, spaces and CJK in filenames; 4K screenshots are downsampled to
  screen width×2 (75 original images would otherwise eat ~500 MB of RAM); tap to open a viewer with
  pinch-zoom, panning and double-tap zoom.
- **Reading**: TOC drawer, full-text search with jump-to-hit, font scaling, reading-position memory,
  light / dark / follow-system themes.
- **Editing**: a native editor with a Markdown toolbar, autosave, unsaved-change prompts, and automatic
  "Save as" when the file is read-only.
- **Export**: PDF (via system print, A4 with adjustable margins), long image (auto-chunked to avoid a
  290 MB single bitmap), current viewport, or a selection — all shareable.
- **Encoding**: UTF-8 / UTF-16 / GB18030 auto-detection; always saved back as UTF-8.

## Install

Download the APK from [Releases](../../releases), copy it to your phone and tap it
(you'll need to allow "unknown sources" once).

After installing, take a look at the permission list — **it's empty**. No network permission, no storage
permission, and everything keeps working in flight mode: the parser, math engine, highlighter and fonts
are all packed inside the APK. It never needs the network.

## Build from source

```bash
bash build.sh          # one-shot, no Gradle (aapt2 + javac + d8 + zipalign + apksigner)
bash build.sh check    # audit an existing artifact (size / permissions / entries / signature / alignment)
bash build.sh clean
```

Output: `dist/lanyue-1.0.0-release.apk`

| Component | Requirement | Default probe path |
|---|---|---|
| JDK | 17+ (the d8 jar is class-file 55; JDK 8 will crash) | `C:\Program Files\Microsoft\jdk-17*` |
| Android SDK | build-tools 36.0.0 + platforms/android-36 | `D:\Android\Sdk` |
| python | only to fix backslashes in APK asset entry names (a Windows aapt2 quirk) | `PATH` |

Override with `JDK_HOME` / `SDK_ROOT` / `BUILD_TOOLS_VERSION` / `PLATFORM_DIR_NAME`.
On Linux/macOS, point the paths correctly and drop the `.exe` suffixes.

> The build script is full of hard-won comments: Windows `aapt2` writes nested asset entry names with
> backslashes (unfixed, your entire frontend 404s), `d8.bat` picks up Java 8 from `PATH`, injecting
> `classes.dex` requires `jar ufM -C`, and the keystore must not live in a directory that gets cleaned.

## Why it can be this small

Measured size breakdown:

| Part | In the APK | Share |
|---|---|---|
| KaTeX fonts (20 woff2 files) | 254 KB | **53%** |
| KaTeX JS/CSS + mhchem | 88 KB | 18% |
| Java code (`classes.dex`) | 36 KB | 7% |
| Hand-written UI (HTML/CSS/JS) | 26 KB | 5% |
| Prism + marked | 37 KB | 8% |
| Signature, resources, icon, licenses | 40 KB | 8% |

In other words: **53% of the size is math fonts, and everything else adds up to under 230 KB.**
Three trade-offs got it there:

1. **No AndroidX / Material / Kotlin** — the entire Java layer is 9 files with zero third-party runtime
   dependencies, and the whole UI is hand-written inside a WebView. This is also what makes the
   "zero permissions, zero network" promise believable.
2. **No Gradle** — plain `aapt2` + `javac` + `d8` + `apksigner`, about 300 lines of shell. No daemon,
   no dependency downloads, a cold build in seconds.
3. **No bundled browser engine** — it reuses the system WebView instead of shipping a 40 MB Chromium.

## Rendering war stories

Every one of these was discovered the hard way:

1. **Math needs placeholder tokens.** Handing `$…$` to marked is not enough: marked still treats `*` and
   `_` inside the formula as emphasis. In an article about black holes, `$Projection*Rotation*Translation$`
   came out as `<em>Rotation</em>`. So math is swapped for `%%LMDn%%` and restored afterwards.
2. **`\(…\)` / `\[…\]` lose their backslash** to marked's escape handling, so they are normalized to
   `$…$` / `$$…$$` before rendering.
3. **A mid-paragraph `$$…$$`** needs blank lines injected around it, or it shares a `<p>` with the prose.
4. **KaTeX does not trust `\color` by default**, so an author's `$\color{red}{…}$` highlight degrades into
   red error text. Fixed with a `trust` whitelist that allows color commands but still rejects `\href` / `\url`.
5. **`text-overflow: ellipsis` does nothing on an inline element** — a `<span>` with a long filename
   overflows its card. That was the "recent files" layout bug.
6. **Huge images break tile rasterization.** Feeding a 4K original to the WebView produced missing
   rectangular patches and stutter once zoomed, because the layer far exceeded the GPU texture limit.
   Fixed by capping the zoom tier, deriving the zoom limit from the texture budget, and promoting the
   image with `translate3d`.

## Verification status

This is not a "works on my machine" toy — every layer has a reproducible headless-Chromium assertion page
under `tools/rendertest/`:

| Layer | Method | Result |
|---|---|---|
| Render pipeline | `test.html`, 77 assertions | **77/77 pass** |
| Math & fonts | `formula.html` + `document.fonts` probe | 12/12 font families, colors correct, invalid math degrades gracefully |
| Code highlighting | `code.html` (asserts **computed colors**) | 10 distinct token colors; GLSL/JS/Python/JSON/YAML correct |
| Layout | `layout.html` (5 widths + home geometry) | no horizontal overflow at 320/360/393/412/600 px; long names truncate |
| Image viewer | `zoom.html` (synthetic PointerEvents) | **28/28 pass**: pinch anchor, clamping, double-tap, no jump on upgrade |
| Real document | an 80 KB GLSL black-hole article | 75 images / 37 code blocks / 52 formulas / 23 headings |
| Build artifact | `aapt2 dump badging` + `unzip -l` + `apksigner verify` | zero permissions, 51 assets, signature and alignment OK |
| Real device | Android 16 (HONOR 100), 3 test rounds | 8 field issues fixed; checklist in [`真机自查清单.md`](真机自查清单.md) |

## Known limitations (honest list)

- **Big5 / Shift_JIS are misdetected as GB18030** — the decoder is too permissive; such files render as mojibake.
- **Short GBK files may be treated as UTF-8.** The threshold is deliberately conservative: misdetecting
  UTF-8 as GB18030 garbles the entire document, whereas the opposite only turns a few characters into `�`.
- **32 MB per document**, read synchronously on the main thread (a 2 MB file costs ~10 ms).
- **PDF goes through the system print pipeline** — no built-in PDF generator (that would add megabytes).
  Choose "Save as PDF" in the print dialog.
- **The same file opened through two different entry points gets two different position keys**
  (`document/` vs an authorized `tree/` URI).
- **No mermaid** — diagrams degrade to code blocks (deliberately not bundled).
- **Overwriting a file truncates it first** — SAF has no atomic replace, so an interrupted write can
  leave a truncated file.

## Repository layout

```
app/
  AndroidManifest.xml          zero permissions + .md associations + ShareProvider
  java/com/dsh/mdreader/       9 Java files (no AndroidX, no Kotlin)
    MainActivity.java          single Activity: WebView reader + native editor + insets
    AppBridge.java             @JavascriptInterface bridge (window.Lanyue)
    AssetServer.java           intercepts https://appassets.local (assets / document / images)
    ImageLoader.java           tiered downsampling (display / zoom / export) + LRU cache
    FileGate.java              SAF open / save-as / folder authorization / relative image resolution
    CodecGate.java             UTF-8 / UTF-16 / GB18030 detection
    Exporter.java              PDF (PrintManager) / chunked long image / viewport / selection / share
    ShareProvider.java         minimal ContentProvider (exposes cacheDir/share only)
    Settings.java              SharedPreferences
  assets/web/                  HTML/CSS/JS rendering layer + vendor(marked/KaTeX/mhchem/Prism)
  res/                         vector icon, light/dark themes, zh/en strings
testdoc/                       acceptance document for on-device testing
tools/
  rendertest/                  headless self-check pages: assertions / math / code / layout / gestures
  INTERFACES.md                frozen Java module interfaces
SPEC.md                        design spec (size budget, root causes of field issues, fixes)
```

## Third-party components and licenses

No third-party runtime dependencies are shipped, but three front-end libraries are bundled as release
files, all MIT: [marked](https://github.com/markedjs/marked) 18.0.14,
[KaTeX](https://github.com/KaTeX/KaTeX) 0.19.0, [Prism](https://github.com/PrismJS/prism) 1.30.0.
Their license texts ship with the source in `app/assets/web/vendor/*/LICENSE`; see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

This repository itself is [MIT licensed](LICENSE).

---

**If it saves you a few hundred MB on your phone, a ⭐ is enough.**
