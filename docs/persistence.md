# Persistence — PluginPrefs

## Purpose and Scope

`PluginPrefs` is the single gateway to all plugin state that survives IDE restarts. It uses the Java `Preferences` API, which on Windows maps to the registry under `HKCU\Software\JavaSoft\Prefs\com\pardha\openfiles`. The class is a pure utility class (no instances, all static methods) and handles serialisation, deserialisation, and trimming for every piece of persistent data.

## Key Classes / Files

| File | Role |
|------|------|
| `PluginPrefs.java` | All persistence — ~990 lines |

## Architecture Decisions

### Java `Preferences` API
Chosen over custom file I/O because it provides atomic writes, per-user isolation, and is available everywhere the NetBeans platform runs. On Windows it maps to the registry; on Linux/macOS it maps to `~/.java/.userPrefs`. The backing store is transparent to the plugin.

### Node layout

```
/com/pardha/openfiles            ← root: scalar settings
  viewMode, noteDisplay, buildGrouping, treeGrouping,
  listSort, closedHistoryLimit, closedStripOpen,
  autoScanOnStartup, indexExtensions, deployOrder,
  gitPath, gitStripOpen,
  developerId, customizationId

/com/pardha/openfiles/pins       ← ordered: 0=key, 1=key, …
/com/pardha/openfiles/notes      ← tcKey → note text
/com/pardha/openfiles/groups     ← groupName → \n-joined tcKey list
/com/pardha/openfiles/groupOrder ← ordered: 0=groupName, 1=groupName, …
/com/pardha/openfiles/collapsedGroups  ← sanitisedName → "true"
/com/pardha/openfiles/recentlyClosed   ← ordered: 0=entry, 1=entry, …
/com/pardha/openfiles/tags       ← tcKey → TagColor name
/com/pardha/openfiles/stashes    ← stashName → raw; _order = \x1F-joined names
```

### TC Key
`keyFor(TopComponent)` derives a stable string key:
1. Take the tooltip text (which is the full file path in NetBeans editors)
2. Normalise `\` → `/`, strip HTML tags, trim whitespace
3. If empty, fall back to `resolveDisplayNameStatic(tc)`
4. Truncate to 75 chars with a leading `…` if longer

The 75-char cap fits within the Preferences key-length limit (80 chars on most platforms) with room for a few extra bytes. Because the truncation keeps the *tail* (most unique part of the path), keys remain distinct for files with different names even when the directory prefix is long.

### Ordered collections via integer keys
Preferences nodes do not have a defined key order. Ordered sequences (pins, group order, closed history, stash order) are stored as `0=value`, `1=value`, … The reader sorts keys numerically before processing. This approach handles gaps (deleted entries) and reorders (drag-and-drop would be straightforward to add) without needing a separate "count" key.

### Separators
- `` (ASCII Unit Separator) — separates `name` and `path` within a single closed-history entry; also used as the stash-name order delimiter.
- `` (ASCII Record Separator) — separates `displayName` and `absolutePath` within a single stash item line.
- `\n` — joins multiple TC keys in a group value.

These control characters are chosen because they are legal in Preferences values and cannot appear in Windows file paths or NetBeans display names.

## Non-Obvious Implementation Details

### `sanitiseKey()`
Section names used as preference keys (e.g. `📌 Pinned`) may contain emoji. The Preferences API on some Windows JVMs rejects non-ASCII keys. `sanitiseKey()` replaces all characters outside the printable ASCII range (`0x20`–`0x7E`) with `_`. The stored key is therefore `_ Pinned` for the pinned section. This only affects storage; the display name is the original value.

### `setClosedHistoryLimit()` side-effect
Setting a new limit trims the stored history immediately if the current list is longer. This prevents ghost entries from accumulating when the user reduces the limit.

### `sortByDeployOrder()`
Takes a mutable list of `TopComponent`s and sorts them in-place by extension rank. Files whose extension is not in the configured order list are placed at the end, sorted alphabetically within that tail group (stable for equal-rank items).

### `isBuildFile()`
Returns `true` when the tooltip path contains `/build/` (case-insensitive). Used by `OpenFilesCellRenderer` to render generated files in a muted italic style and by `refreshList()` to decide whether to place a file under the "Generated" section.

### `flush()`
Every write method calls `node.flush()` to force the backing store to persist immediately. Without this, data can be lost if the IDE crashes before the JVM performs its normal shutdown flush.

## Known Gotchas / Constraints

- **`note.length() > 500` truncation**: Notes are silently truncated to 500 chars on save. The settings dialog does not enforce a character limit in the input field; the truncation happens silently in `setNote()`.
- **Stash serialisation uses `\n` as line separator within a stash value**, so display names and absolute paths are pre-sanitised by replacing `\n` with a space. Do not store multi-line values.
- **Groups are identified by name** (not by UUID). Renaming a group is implemented by rebuilding the entire `LinkedHashMap` with the new key in the same position. Two groups cannot have the same name — `createGroup()` silently no-ops if the name already exists, and `renameGroup()` no-ops if the new name is taken.
- **`collapsedGroups` node stores only collapsed sections** — a missing key means "expanded". `setGroupCollapsed(name, false)` removes the key rather than storing `false`. This keeps the node sparse.
- **`deployOrder` strips `\n` and `\r`** because the settings dialog uses a `JTextArea` which can introduce line breaks when the user types. Strip them before saving.
