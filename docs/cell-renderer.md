# Cell Renderer — OpenFilesCellRenderer

## Purpose and Scope

`OpenFilesCellRenderer` is the `ListCellRenderer<Object>` for the main file list. Every row in list view passes through here. It handles the visual distinction between section headers and file rows, renders per-file metadata (tag colour, pin indicator, note indicator, active highlight, build-file style, hover close button), and exposes the close-button hit area width so mouse handlers in `OpenFilesTopComponent` can detect close-button clicks without a separate component.

## Key Classes / Files

| File | Role |
|------|------|
| `OpenFilesCellRenderer.java` | Renderer and `GroupHeader` data class — ~305 lines |
| `OpenFilesTopComponent.java` | Sets `hoveredRow`, calls `fileList.setFixedCellHeight()`, reads `CLOSE_BTN_WIDTH` |
| `PluginPrefs.java` | Source of tag colour, note text, pin state, build-file flag |

## Architecture Decisions

### Single reusable `JPanel` instance
The renderer returns `this` (the renderer itself, which extends `JPanel`) for file rows, and a freshly constructed `JLabel` for group headers. Reusing a single panel is the standard Swing renderer pattern — it avoids object allocation on every repaint pass. Because Swing never actually adds the returned component to a container (it just paints it), state carried over from one call to the next is safe as long as every field is explicitly set on each call.

### `GroupHeader` sentinel object
Section headers are stored in the list model as `GroupHeader` instances (a simple inner record with `name` and `count` fields) rather than as raw `String`s. This gives the mouse handlers a typed way to distinguish header rows from file rows without an `instanceof String` check that could accidentally match other strings.

### `CLOSE_BTN_WIDTH` constant
The close button occupies the leftmost `CLOSE_BTN_WIDTH` pixels of every row. `OpenFilesTopComponent.mousePressed` reads this constant to decide whether a click was on the close button or on the file label. Both the renderer and the event handler share the constant — do not change one without the other.

### Tag stripe
A 4 px wide solid-colour rectangle is painted immediately to the right of the close-button area using `Graphics.fillRect`. It is not a separate component; it is drawn directly in `paintComponent` after calling `super.paintComponent`. Using `fillRect` avoids the overhead of an additional child component per row.

## Non-Obvious Implementation Details

### Hover state (`hoveredRow`)
`hoveredRow` is a package-private `int` field on the renderer, set by a `MouseMotionListener` in `OpenFilesTopComponent.setupListView()`. When the mouse moves off the list, `mouseExited` sets it to `-1`. The renderer uses it to paint the close button in red (hover state) vs. muted grey (at-rest state). Because `hoveredRow` is written on the EDT (mouse events) and read on the EDT (paint), no synchronisation is needed.

### Active file highlight
The currently active `TopComponent` is tracked in `OpenFilesTopComponent.activeKey`. The renderer checks `PluginPrefs.keyFor(tc).equals(activeKey)` and applies a blue-tinted background when true. This check uses `PluginPrefs.keyFor()` on every paint call — it is cheap (string ops only) but do not make it more expensive.

### Build-file rendering
If `PluginPrefs.isBuildFile(tc)` returns `true`, the file name is rendered italic with a muted slate-blue foreground (`new Color(100, 120, 160)`) regardless of selection state. The file icon is also slightly dimmed.

### Subtitle mode
When `PluginPrefs.NOTE_SUBTITLE.equals(PluginPrefs.getNoteDisplay())`, the row height is 42 px (`fileList.setFixedCellHeight(42)`). The renderer paints the note text in a second line using a smaller, dimmer font. In all other note modes (tooltip, inline) the row is 24 px.

### Group header rendering
`GroupHeader` rows return a standalone `JLabel` (not `this`) with a slightly darkened background, bold font, and a `▶`/`▼` arrow prefix indicating collapse state. The label's background is computed by blending `Panel.background` with grey at 8 % opacity — it is intentionally subtle, just enough to distinguish header rows from file rows.

## Known Gotchas / Constraints

- **`fixedCellHeight` must match note mode**: If you add a new note display mode, update `fileList.setFixedCellHeight()` in both `setupListView()` and the settings-dialog OK handler.
- **Do not add child components to the renderer panel between calls**: Swing may call the renderer out-of-order (e.g. for painting a drag image). Always reconfigure all child components on every `getListCellRendererComponent` call.
- **`GroupHeader` is not `TopComponent`**: Any place in `OpenFilesTopComponent` that iterates `listModel` and casts to `TopComponent` must first check `instanceof TopComponent`. Many loops already do this; do not skip the check.
- **The tag stripe width (4 px) is hardcoded in `paintComponent`** — it is not a constant. If you change it, also update the left-padding in the text area of the row so they don't overlap.
