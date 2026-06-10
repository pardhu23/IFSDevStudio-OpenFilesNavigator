# Git Integration — GitStatusPanel

## Purpose and Scope

`GitStatusPanel` runs `git status --porcelain -u` directly via `ProcessBuilder`, bypasses the NetBeans Git plugin entirely, and shows staged / unstaged / untracked changes in under a second. It is embedded as the lower half of the main panel's `JSplitPane` and collapses to a 22 px header bar when not in use.

## Key Classes / Files

| File | Role |
|------|------|
| `GitStatusPanel.java` | All git logic, UI, and embedded panel |
| `PluginPrefs.java` | `isGitStripOpen` / `setGitStripOpen` (collapse state), `getGitPath` / `setGitPath` (executable path) |
| `OpenFilesTopComponent.java` | Hosts the git strip: `gitStripWrapper`, `gitStripHeader`, `gitStripBody`, `mainSplit`, `setupGitStrip()`, `toggleGitStrip()`, `applySplitDivider()` |

## Architecture Decisions

### Direct `ProcessBuilder` instead of NetBeans Git API
The NetBeans Git plugin can take several seconds to respond on large repos because it maintains a full status model. Running `git status --porcelain -u` directly in a `Thread` (not RequestProcessor) gives results in < 1 s and keeps the implementation free of any NetBeans VCS module dependency.

### Embedded strip vs standalone window
The git panel was originally a separate floating window opened by a toolbar button. It was redesigned to live inside the main panel as a collapsible section (like Recently Closed). The standalone `openWindow()` method was removed when the button was removed.

### Lazy initialisation
`gitPanel` (the `GitStatusPanel` instance) is null until the first time the user expands the strip. `ensureGitPanel()` creates it on demand. This avoids running `git status` at IDE startup when the section is collapsed.

### JSplitPane divider management
The git strip wrapper is the **bottom** component of `mainSplit` (`JSplitPane.VERTICAL_SPLIT`). The divider position is managed entirely by `applySplitDivider(boolean)`:

- **Expanded**: divider at `totalHeight − GIT_BODY_H − dividerSize` (leaves 220 px for the body)
- **Collapsed**: divider at `totalHeight − headerHeight − dividerSize` (leaves exactly 22 px for the header)

Setting the divider to `totalHeight` (full) would squash the header to 0 px, making it invisible. `applySplitDivider` always calls `mainSplit.revalidate()` + `repaint()` immediately after so the change paints without waiting for an unrelated event.

`applySplitDivider` is always called via `SwingUtilities.invokeLater` so the split pane has a valid height by the time the call runs.

### Branch tracking
During each `refresh()` the background thread records the first `gitRoot` found and calls:
- `fetchCurrentBranch(gitRoot)` — runs `git rev-parse --abbrev-ref HEAD`
- `fetchLocalBranches(gitRoot)` — runs `git branch`, strips `*` marker, returns list

Results are stored in `volatile` fields `currentBranch`, `currentRepoRoot`, `localBranches` assigned on the EDT inside `SwingUtilities.invokeLater`. `checkoutBranch(branch)` runs `git checkout <branch>` in a background thread then calls `refresh()`.

A new `runGitOutput(File, String...)` helper reads the process stdout as a string (used for branch commands). The existing `runGit(String, String...)` helper runs a command without a trailing file path argument (distinct from `runGitCommandSilent` which always appends a file path).

### Git executable discovery
`findGitExecutable()` checks in order:
1. `PluginPrefs.getGitPath()` — user-configured path (set via Settings dialog)
2. `C:\Program Files\Git\bin\git.exe`
3. `C:\Program Files (x86)\Git\bin\git.exe`
4. `%LOCALAPPDATA%\Programs\Git\bin\git.exe`
5. `%ProgramFiles%\Git\bin\git.exe`
6. `"git"` — relies on system `PATH`

The method is package-private (`static`) so it can be reached from tests or other classes in the package.

## Non-Obvious Implementation Details

### Porcelain format parsing
Each line is: `XY PATH` or `XY ORIG -> DEST` (for renames). The two-char `XY` code encodes staged (`X`) and unstaged (`Y`) status independently. The parser reads:
- `filePath` = the last segment after ` -> ` if present, or the entire `rest`
- Strips surrounding quotes that Git adds for paths containing spaces
- Normalises separators to `/`

`GitEntry.isStaged()` returns `true` when `X != ' '` and `X != '?'`. `isUntracked()` is the special case `xy.equals("??")`.

### Multi-project support
`refresh()` iterates all open `Project`s. For each, it first checks for a `workspace/.git` (IFS project layout) before walking up with `findGitRoot()`. This means IFS projects that keep Git at the workspace level are found immediately without tree-walking.

### Section headers in the list model
The list model mixes `GitEntry` objects and `String` section headers (`"Staged  (N)"`, `"Unstaged  (N)"`, `"Untracked  (N)"`). The cell renderer branches on `value instanceof String` to draw headers with a tinted background. Mouse handlers skip String items.

### Count summary in header and status bar
`applyFilter()` categorises every displayed entry into four buckets using `countTypes()`:
- **A** (added/new): untracked (`??`) or any `A` in XY
- **M** (modified): everything else (includes `UU` conflicts)
- **D** (deleted): any `D` in XY
- **R** (renamed): any `R` in XY

`formatCounts()` turns the counts into `"A 21  M 2  D 1"` (zero-count letters are omitted).

The status bar shows the filtered-entry counts. `getCountSummary()` returns counts over `allEntries` (unfiltered) for the header label. After each `applyFilter()` the `onCountsChanged` callback fires, which calls `OpenFilesTopComponent.updateGitStripHeaderLabel()` so the header updates whether the strip is expanded or collapsed. The header reads: `▼ Git Status  —  A 21  M 2  D 1`.

### Compact single-line cell renderer
Each file row is 26 px tall (down from 42). The layout is a horizontal `BoxLayout`:
`[66 px badge] 7px gap [bold filename] 6px gap [dimmed directory path]`
The badge uses 9f bold font. The path label shows only the directory portion of the repo-relative path (everything before the last `/`), leaving the filename prominent on the left. On selection, the path label colour switches to `selectionForeground` for readability.

### Context menu git operations
All git commands (`git add`, `git restore`, `git restore --staged`) run in a separate `Thread` (not the EDT), then call `SwingUtilities.invokeLater(() -> refresh())` on completion. The `git restore` (discard changes) path shows a `JOptionPane.YES_NO_OPTION` confirmation before executing.

### Duplicate DocumentListener removed
An earlier version accidentally registered the filter `DocumentListener` twice. The duplicate was removed — `applyFilter()` now fires exactly once per text change.

## Known Gotchas / Constraints

- **Windows path separators**: All absolute paths returned by git (which uses `/`) are normalised to the OS separator via `replace('/', File.separatorChar)` before passing to `new File(...)`. The `absPath` stored in `GitEntry` always uses `/` internally.
- **`loading` flag**: `refresh()` is a no-op if `loading == true`. This prevents double-refreshes when the refresh button is clicked mid-run. The flag is reset on the EDT after the background thread finishes.
- **`showInProject` loads the action from the config FS** via `FileUtil.getConfigFile("Actions/Window/SelectDocumentNode/org-netbeans-modules-project-ui-SelectInProjects.instance")` because `SelectInProjects` is registered as an instance file, not via `@ActionID`, so `Actions.forID` cannot find it. The action is fired through a 200 ms `javax.swing.Timer` to give `targetTc.requestActive()` time to propagate the global selection before the action runs.
- **`GIT_BODY_H = 220`** is the default body height used by `applySplitDivider` when expanding. The user can drag the JSplitPane divider to any size; the constant only controls the position on first open or re-open after collapse.
- The git strip body height is **not persisted** across IDE restarts. It always resets to `GIT_BODY_H` when re-expanded.
