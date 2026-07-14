# Grid Mode for Voice Access

## Context

Numbered labels require clickable accessibility nodes; many surfaces (games, canvas apps, web views) expose none. Grid mode adds a coordinate-based fallback: a chess-notation grid over the whole screen lets the user tap **any** pixel region by voice, with a 3×3 refinement step for precision. Requested behavior:

- "show grid" / "hide grid" toggles it; grid mode and numbered labels are **mutually exclusive** (enabling one disables the other).
- Chess layout: letters (columns) at top, numbers (rows) on the side. **8 columns (a–h)**, square cells → ~18 rows on 1080×2424.
- "press a2" → tap the cell center immediately. Bare "a2" → show a 3×3 sub-grid inside that cell (letters a–c top, 1–3 side); a following ref ("b3") taps the sub-cell and dismisses the sub-grid (main grid stays).
- Letters spoken plain (a–h) **and** as NATO synonyms ("bravo 2" = b2) — reuse the first 8 `va_pin_words` (Alpha…Hotel / Anton…Heinrich).
- Dotted light-grey lines; **separate opacity setting** (new proto field), not the label opacity.

Work on a **new branch** off `vosk-pin-grammar` (e.g. `grid-mode`).

## New files

1. **`voiceaccess/GridGeometry.kt`** — pure math. `cols=8`, `cellSize = screenWidth/8f`, `rows = ceil(screenHeight/cellSize)`. `cellRect(col,row)` (bottom row clamped to screen), `cellCenter`, `subCellRect/subCellCenter(cell, subCol, subRow)` (3×3 split of the cell rect). All in screen coordinates (same space as `dispatchTapAt`). Constructed fresh on demand from real display size (API 30+: `windowManager.currentWindowMetrics.bounds`; else `defaultDisplay.getRealMetrics`) so rotation self-heals.

2. **`voiceaccess/GridOverlayView.kt`** — plain `View` modeled on `LabelOverlayView.kt` (copy its `getLocationOnScreen` screen→view coordinate mapping). Paints: `linePaint` STROKE 1dp with `DashPathEffect(dp(4), dp(4))`, color `argb(alpha, 0xCC,0xCC,0xCC)` (light grey, alpha from opacity setting); `textPaint` same grey, ~12sp, slight dark shadow layer for visibility on white. API: `applyOpacity(Float)`, `setState(geometry, subGridCell: Pair<Int,Int>?)`. onDraw: dashed inner grid lines, column letters a–h ~14dp below top edge (status bar), row numbers 1..N inside left edge; when sub-grid active: solid border around the chosen cell + dashed 3×3 dividers + small a–c / 1–3 headers.

3. **`skills/grid/GridInfo.kt`** — `object : SkillInfo("grid")`, icon `Icons.Default.Grid4x4`, builds `GridSkill` from `Sentences.Grid[lang]`.

4. **`skills/grid/GridSkill.kt`** — `StandardRecognizerSkill<Grid>` with PinKeySkill-style gating (`app/src/main/kotlin/org/stypox/dicio/skills/pin_key/PinKeySkill.kt` is the template):
   - `score()`: Show/Hide always pass through. Cell sentences: `AlwaysWorstScore` unless `service.isGridActive()` and NOT `service.isPinModeActive()` (PIN wins — shares NATO words, security surface). Parse tokens: optional leading verb (EN press/tap; DE tipp/tippe/drück/drücke/wähl/wähle → sets `explicitPress`), one letter token (single char a–h, or `service.pinSlotForWord(word)` for NATO — accept any slot 0..9 so "india 2" is claimed and answered with out-of-range instead of leaking), remaining tokens → row via shared spoken-number parser. Strict shape or return `AlwaysWorstScore` (so scroll/back/etc. keep working while grid is up). Stash result in `@Volatile pendingCell`, claim with `AlwaysBestScore`.
   - `generateOutput()`: Show → `service.showGrid()`; Hide → `service.hideGrid()`; cell → `service.handleGridCell(col, row, explicitPress)` returning a result enum mapped to `GridOutput`.

5. **`skills/grid/GridOutput.kt`** — like `LabelsOutput`: SHOWN, HIDDEN, TAPPED(cell), SUB_SHOWN(cell), OUT_OF_RANGE(cell), NOT_UNDERSTOOD, SERVICE_DISABLED.

6. **`util/SpokenNumberParser.kt`** — extract `ClickNumberSkill`'s private `parseNumber` + `fixTeenTensMishearing` (digit fast path, dicio-numbers for EN, `GermanNumberParser` for DE) into a shared object; move `GermanNumberParser` to `util/` too. `ClickNumberSkill` delegates (behavior unchanged).

7. **`sentences/en/grid.yml`**:
   ```yaml
   show:
     - show|display grid
   hide:
     - hide grid
   press:
     - press|tap .cell.
   bare:
     - .cell.
   ```
   **`sentences/de/grid.yml`** (verbs/das/ein/aus already in DE grammar):
   ```yaml
   show:
     - zeig<e?>|aktivier<e?> das? raster|gitter
     - raster|gitter anzeigen|einblenden|aktivieren
   hide:
     - versteck<e?>|deaktivier<e?> das? raster|gitter
     - blend<e?> das? raster|gitter aus
     - raster|gitter ausblenden|verstecken|deaktivieren
   press:
     - tipp<e?>|drück<e?>|wähl<e?> .cell.
   bare:
     - .cell.
   ```

## Modified files

- **`sentences/skill_definitions.yml`** — add `grid` skill (specificity high): show, hide, press (capture `cell`, string), bare (capture `cell`, string). Must match both ymls exactly.
- **`voiceaccess/VoiceAccessService.kt`**:
  - State: `gridOverlay: GridOverlayView?`, `@Volatile gridVisible` (user intent, persists across sessions like `labelsVisible`), `@Volatile subGridCell: Pair<Int,Int>?` (session-scoped), `@Volatile gridOpacity` (default 70%).
  - `isGridActive() = gridVisible && sessionActive`.
  - `showGrid()`: `gridVisible=true; labelsVisible=false; labeledNodes=emptyList(); subGridCell=null`; if session active → `refreshLabels()` + add grid overlay. `hideGrid()`: clear state + remove overlay.
  - `showLabels()`: add `if (gridVisible) hideGrid()` (mutual exclusion both ways).
  - `handleGridCell(col, row, explicitPress): GridCellResult`:
    - sub-grid open && col<3 && row≤3 → tap sub-cell center (existing `dispatchTapAt`), clear sub-grid → TAPPED. Refs outside a–c/1–3 re-anchor on the main grid.
    - validate col<8 && row in 1..geo.rows else OUT_OF_RANGE.
    - explicitPress → tap main-cell center, clear sub-grid → TAPPED; bare → `subGridCell = col to row-1`, update overlay → SUB_SHOWN.
  - Overlay plumbing: `addGridOverlay()/removeGridOverlay()` copying label-overlay try/catch, reusing `labelOverlayParams()`.
  - Session hooks: `showListening()` re-adds grid if `gridVisible`; `hideListening()` removes overlay + clears `subGridCell` but keeps `gridVisible`; `cleanup()` removes overlay.
  - Settings collector (like `collectLabelStyle()`): map `it.gridOpacity` (0 → default 70) → `gridOverlay?.applyOpacity(...)`.
- **`proto/user_settings.proto`** — `int32 grid_opacity = 19;` (0 = unset → default, existing convention).
- **`settings/Definitions.kt`** — `gridOpacity()` IntSetting slider min 20 max 100 (copy `labelOpacity()`).
- **`settings/MainSettingsViewModel.kt`** — `setGridOpacity`.
- **`settings/MainSettingsScreen.kt`** — render item after `labelContrast()`.
- **`eval/SkillHandler.kt`** — register `GridInfo`.
- **`res/values/arrays.xml`** — add letters `a`–`h` to `va_command_grammar` ("press/tap/show/hide/grid/one..twenty" already present).
- **`res/values-de/arrays.xml`** — add `a`–`h` + `raster` + `gitter`.
- **`res/values/strings.xml` + `values-de/strings.xml`** — pref title/description, skill name/example, spoken outputs (shown/hidden/tapped %s/refining %s/out-of-range %s/not understood).
- **`skills/click_number/ClickNumberSkill.kt`** — delegate to `SpokenNumberParser`.

## Implementation order

1. Branch. 2. Proto + settings slice. 3. GridGeometry + GridOverlayView. 4. Service changes. 5. SpokenNumberParser extraction. 6. ymls + skill_definitions (build here — compiler validates). 7. Skill trio + registration. 8. Grammar arrays. 9. Build + install + verify.

## Risks

- **Vosk vocab**: single letters may be missing from the model (silently dropped, logged) or confusable (b/d/e/g). NATO synonyms are the tested fallback and already in the grammar via `va_pin_words`. Check `adb logcat | grep -i vosk` for vocabulary warnings.
- **"a" as English article** in the closed grammar may absorb noise; parseCell's strict letter+number shape limits damage. If recognition degrades, drop plain letters from the grammar (skill already accepts NATO).
- **Bare-sentence competition** with click_number's bare `.number.`: GridSkill claims only on strict parse success while grid active, so no conflict.

## Verification

`./gradlew :app:assembleDebug` → `adb -s 192.168.178.29:42987 install -r app/build/outputs/apk/debug/app-debug.apk`. Enable touch visualization: `adb shell settings put system pointer_location 1`.

1. "show grid" → dotted grey grid, a–h top, 1–18 left; numbered labels gone.
2. "show labels" → grid gone; "show grid" → labels gone (both directions).
3. "press a two" / "tap hotel eighteen" → immediate taps at centers.
4. Bare "b three" → 3×3 sub-grid in b3; "c one" → taps sub-cell, sub-grid dismissed, main grid stays.
5. "bravo two" ≡ "b two".
6. Sub-grid open: "e five" re-anchors; "press e five" taps main cell.
7. "a nineteen" / "india two" → spoken out-of-range, no tap.
8. Session restart → grid restores (intent kept), sub-grid doesn't.
9. PIN pad → phonetic labels win, "alpha" = PIN key not grid.
10. Grid opacity slider changes live, independent of label opacity.
11. Rotation → grid re-fits.
12. German: "zeige raster", "tippe bravo zwei", etc.
