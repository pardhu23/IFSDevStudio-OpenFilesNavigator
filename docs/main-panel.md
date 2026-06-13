# Main Panel — OpenFilesTopComponent

## Purpose and Scope

`OpenFilesTopComponent` is the root dockable panel registered under the NetBeans Window System. It is the single entry point for all open-file navigation: it owns the file list, the tree view, group/pin/tag/note management, the stash system, the recently-closed strip, the embedded git status strip, and the settings dialog.

## Key Classes / Files

| File | Role |
|------|------|
| `OpenFilesTopComponent.java` | Everything — ~3 250 lines |
| `OpenFilesCellRenderer.java` | Custom renderer for every row in the list view |
| `PluginPrefs.java` | All persistence (groups, pins, tags, notes, collapse state, …) |
| `layer.xml` | Registers the window, shortcuts (`Alt+Shift+O`), and menu entry |

## Toolbar

Six icon-only buttons sit in a `WrapLayout` panel (`northPanel` NORTH):

| Button | Icon | Tooltip |
|--------|------|---------|
| List view | `≡` | List view |
| Tree view | `⊞` | Tree view (grouped by folder) |
| Quick Search | `🔍` | Quick File Search (Ctrl+P) |
| Stash | `🗃` | Stash / Restore open files |
| Settings | `⚙` | Settings |
| Module Dependencies | `🔗` | Module Dependencies |
| Sort | `⇅` | Sort order (shows popup) |

### Sort order
The `⇅` button opens a popup with four modes (persisted via `PluginPrefs.setListSort`):
- **Name A→Z** — alphabetical by display name (default)
- **Open order** — order files were opened in the session
- **Recently activated** — most recently focused files first; list re-sorts on every activation event
- **File type** — grouped by extension, then alphabetical within each extension

Activation timestamps are recorded in a `ConcurrentHashMap<String, Long>` keyed by `PluginPrefs.keyFor(tc)` and updated inside `updateActiveHighlight()`. In "Recently activated" mode, `updateActiveHighlight()` also triggers `refreshList()` so the list re-orders live as you switch files.

### Git strip branch display
The git strip header EAST slot holds `gitBranchLabel`, a `JLabel` showing `⎇ branchName`. It is populated after each `GitStatusPanel.refresh()` via the `onCountsChanged` callback. Clicking the label opens a branch-switch popup (local branches from `git branch`, checked = current, disabled = current). Selecting a branch shows a confirmation dialog then runs `git checkout <branch>` in a background thread and calls `refresh()` on completion. The branch label has its own `MouseListener` so clicks on it do not toggle the git strip collapse.

`WrapLayout` is a private static inner class that extends `FlowLayout` and overrides `preferredLayoutSize` / `minimumLayoutSize`. It measures each row against the container's current allocated width and wraps onto a new row when a button would overflow. When wrapping changes the row count, it calls `parent.revalidate()` so `northPanel`'s height grows to accommodate the extra row. This lets the docked window resize to any width without enforcing a minimum. The "Open Files" heading label was removed to free horizontal space; the NetBeans tab title already shows the window name.

## Architecture Decisions

### Singleton via `getInstance()`
The component is kept as a static singleton and also participates in NetBeans window-system serialization via `readResolve()`. Both paths converge on the same instance.

### Dual-view CardLayout
`centerPanel` is a `CardLayout` holding two cards (`"list"` / `"tree"`). Switching view mode just calls `CardLayout.show()` — the opposite view's model is kept alive and up to date so switching is instant.

### JSplitPane for the git strip
The file-list card and the git strip wrapper are the two halves of a vertical `JSplitPane` that occupies `BorderLayout.CENTER`. `setResizeWeight(1.0)` means the file list absorbs window growth. The recently-closed strip remains in `BorderLayout.SOUTH` (fixed height).

When the git strip is **collapsed** the divider is placed at `totalHeight − headerHeight − dividerSize` so the header (22 px) stays visible. When **expanded** the divider is placed at `totalHeight − GIT_BODY_H − dividerSize`. Both moves happen via `SwingUtilities.invokeLater` so the component has a real height before the call.

### List model uses `Object` items
`DefaultListModel<Object>` holds a mix of `TopComponent` instances (actual files) and `OpenFilesCellRenderer.GroupHeader` sentinel objects (section headers). The renderer branches on `instanceof`. Mouse and keyboard handlers do the same.

### TC key
Every file is identified by a stable string key derived from its tooltip path (normalised to forward slashes) truncated to 75 chars with a leading `…` if needed. This key is used as the map key in every preference node. See `PluginPrefs.keyFor()`.

### Refresh coalescing
`scheduleDelayedRefresh()` posts a `RequestProcessor.Task` with a 1 500 ms delay and cancels any already-pending task. The registry `PROP_OPENED` listener fires a lot during IDE startup; coalescing prevents dozens of rebuilds in the first two seconds.

## Non-Obvious Implementation Details

### `allEditors` vs `openOrder`
`allEditors` is rebuilt from `TopComponent.Registry.getOpened()` filtered by `isEditorTC()` on every `refreshList()`. `openOrder` (a `LinkedHashSet`) tracks insertion order across the session so that `LIST_SORT_OPEN_ORDER` can sort by first-open time. Entries are never removed from `openOrder`; stale keys are silently ignored during sort.

### `isEditorTC()` heuristic
A `TopComponent` is treated as an editor file if it is in an `"editor"` or `"multiview"` mode **and** is not the IDE start page **and** its tooltip looks like a file path. This heuristic can misclassify unusual TCs — widen the check with care.

### Group collapse state storage
Collapse state is stored per named section in `PluginPrefs` (node `/com/pardha/openfiles/collapsedGroups`). Section names that contain non-ASCII chars (e.g. `📌 Pinned`) are sanitised by `sanitiseKey()` before being used as preference keys because the Preferences API limits key characters on some platforms.

### Settings dialog
The settings dialog (`openSettingsDialog()`) is a modal `JDialog` built with a `BoxLayout` vertical panel. Sections, top to bottom:
- Quick Search indexed file extensions (CSV)
- Note display / Tree grouping / Generated files / List sort (2-column grid)
- Generate & Deploy extension order
- **IFS header comment identity** — two `JTextField`s for Developer ID and Customization ID, persisted via `PluginPrefs.setDeveloperId` / `setCustomizationId`
- Git executable path
- Recently-closed limit spinner + Auto-scan checkbox
- Manage groups (list + add/rename/delete)

### IFS "Generate / Deploy" actions
`runIfsAction()` resolves IFS-specific action IDs at runtime via `Actions.forID()` and wraps them as `ContextAwareAction` with a lookup built from the selected `TopComponent`s. This avoids a hard compile-time dependency on IFS modules. If the action is absent (non-IFS IDE), the menu item is simply absent.

### Auto-dock on first open
`componentOpened()` checks whether the component ended up in an editor or multiview mode (can happen if the window system serialised it there) and re-docks it into `commonpalette` (right side panel). This only runs once per open because `registerListeners()` is guarded by `listenersRegistered`.

### Stash serialisation
Each stash is stored as a single preference string: lines of `displayNameabsolutePath`, with stash names in an `_order` key delimited by ``. The separator `` (ASCII Record Separator) cannot appear in file paths or display names.

## Known Gotchas / Constraints

- **EDT only**: `refreshList()`, `rebuildClosedStrip()`, and all UI mutations must be called on the EDT. The registry listener already defers via `invokeLater`; do not call these from background threads directly.
- **`fileList.setFixedCellHeight()`** must be updated whenever the note-display mode changes (subtitle mode requires 42 px; all others 24 px). It is set in both the settings-dialog OK handler and at startup.
- **`openOrder` grows forever** during a long IDE session. It is never persisted and is rebuilt empty on restart, so open-order sort only reflects files opened in the current session.
- **`dumpActionIds` was removed** — if you need to debug NetBeans action IDs again, re-add temporarily and remove before committing.
- The `BoxLayout` removed from the south panel — the git strip and recently-closed strip are no longer stacked in a `BoxLayout`. The git strip is now the bottom half of `mainSplit`; the recently-closed strip is in `SOUTH`. Do not revert this layout without understanding `applySplitDivider`.
