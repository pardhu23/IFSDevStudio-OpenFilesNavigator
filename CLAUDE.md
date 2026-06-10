# OpenFilesNavigator — Project Guide for Claude

## Project Overview

A NetBeans Platform plugin module (`com.pardha.openfiles`) for IFS Developer Studio 18. It adds a dockable "Open Files Navigator" panel with fast file navigation, grouping, tagging, notes, stash/restore, Quick File Search (Ctrl+P), embedded git status, and IFS-specific deploy/generate actions. It is not a standalone application — it runs inside IFS Developer Studio (a NetBeans-based IDE).

## Tech Stack and Environment

- **Language**: Java 11 (NetBeans module, `release="11"` in `build.xml`)
- **Build system**: Apache Ant via NetBeans harness (`nbproject/`)
- **Platform**: NetBeans Platform (IFS Developer Studio 18 at `C:\Program Files\IFS\DeveloperStudio18`)
- **UI**: Swing (no JavaFX)
- **Persistence**: Java `Preferences` API (Windows registry under `HKCU\Software\JavaSoft\Prefs`)
- **Git integration**: `ProcessBuilder` running `git` directly — no NetBeans VCS API

## Build and Run

```powershell
# Build (from project root)
& "C:\Program Files\IFS\DeveloperStudio18\extide\ant\bin\ant.bat" clean jar

# Output jar
build\cluster\modules\com-pardha-openfiles.jar
```

To install after build, copy/symlink the jar into the IFS Developer Studio `modules` directory or use the NetBeans module installation mechanism. No hot-reload — restart the IDE after replacing the jar.

**Important**: PowerShell's `WriteAllText` writes UTF-8 with BOM by default. Always use `New-Object System.Text.UTF8Encoding $false` when writing Java source files from PowerShell — Java does not accept a BOM at the start of a `.java` file.

## Coding Conventions

- **No comments explaining what code does** — only add a comment when the *why* is non-obvious (hidden constraint, workaround, invariant).
- **No trailing summaries or change-log comments** — what changed belongs in git history.
- **EDT safety**: All Swing mutations on the EDT. All file I/O, git commands, and cache walks on background threads. Use `SwingUtilities.invokeLater` to return to EDT; use `RequestProcessor` or plain `Thread` for background work.
- **No feature flags or backwards-compat shims** — just change the code.
- **No premature abstractions** — three similar blocks are fine; extract only when there are four or more and a clear stable interface.
- **Unicode escapes in string literals** — follow the existing pattern (`"▼ "` not the literal `▼` character) for consistency. The entire codebase uses escape sequences for all non-ASCII characters in string literals.
- **`PluginPrefs` is the only persistence gateway** — do not write to files or other Preferences nodes directly from UI classes.
- **Preference keys are defined as `private static final String KEY_*`** constants at the top of `PluginPrefs`. Add new keys there; never hardcode key strings elsewhere.

## Docs Index

| File | Covers |
|------|--------|
| [`docs/main-panel.md`](docs/main-panel.md) | Main dockable panel, list/tree views, groups, pins, tags, notes, stash, recently-closed strip, settings dialog, JSplitPane layout |
| [`docs/quick-search.md`](docs/quick-search.md) | Ctrl+P file search, three-tier cache, disk persistence, fuzzy matching, file watcher, query debouncing |
| [`docs/git-integration.md`](docs/git-integration.md) | Embedded git status strip, JSplitPane collapse/expand, git executable discovery, porcelain parsing |
| [`docs/persistence.md`](docs/persistence.md) | PluginPrefs: all preference nodes, TC key derivation, separators, ordered storage, stash and closed-history serialisation |
| [`docs/cell-renderer.md`](docs/cell-renderer.md) | List row renderer, group headers, close button, tag stripe, hover state, build-file styling, subtitle mode |
| [`docs/ifs-actions.md`](docs/ifs-actions.md) | IFS-specific actions (FindApiFile, FormatApiName), module dependency tree panel, reflection-based IFS API access |

## Rules for Claude Code

- Before starting any task, read the relevant /docs file for that feature 
  area. Do not explore source files to re-derive context already documented.
- After completing any task, update the relevant /docs file to reflect 
  changes — new classes, changed behavior, removed logic, etc.
- If a change affects overall architecture or conventions, update this 
  CLAUDE.md as well.
- Treat docs as part of the task. A task is not done until docs are current.

This applies to:
- Adding or removing features → update the relevant docs file
- Changing layout or architecture → update affected docs files
- Adding new preference keys → update `persistence.md`
- Adding new source files → add header comment + update docs index above
- Removing code referenced in docs → correct or remove the reference
