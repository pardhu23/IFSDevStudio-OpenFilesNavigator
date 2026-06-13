# Quick File Search — Ctrl+P / Alt+P

## Purpose and Scope

`QuickFileSearchDialog` provides a fast, keyboard-driven file-finder across three distinct file populations in an IFS workspace: customer workspace files, generated build files, and core-checkout files. It mirrors the feel of VS Code's Ctrl+P but is tuned for IFS project structures with thousands of model files.

## Key Classes / Files

| File | Role |
|------|------|
| `QuickFileSearchDialog.java` | Dialog, three-tier cache, file watcher, disk persistence |
| `QuickFileSearchAction.java` | NetBeans `Action` that opens the dialog; registered at `Alt+P` / `Ctrl+P` |
| `FuzzyMatcher.java` | Scoring engine used to rank and filter results |

## Architecture Decisions

### Three-Tier Cache

| Tier | Root | Rebuild trigger |
|------|------|-----------------|
| `custCache` | `<project>/workspace/` | Incremental: file watcher adds/removes entries; dirty flag triggers full rebuild on next open |
| `buildCache` | `<project>/build/` | Debounced 3 000 ms after any file-change event (IFS code-gen creates many files in a burst) |
| `coreCache` | Path from `project.ccs.corefiles` property | Rebuilt when the set of open projects changes |

All three caches are `List<FileEntry>` where each entry holds `name`, `relativePath`, `absolutePath`, and `source`. Merged via `mergedCache`: when any cache completes, `scheduleMergedRebuild` starts a 150 ms debounce timer; on fire, a `QuickFileSearch-MergeBuilder` background thread concatenates the three lists under `CACHE_LOCK`, then hands the result back to the EDT via `invokeLater`.

### Disk Cache (custCache and coreCache only)
Binary format: 4-byte magic `0xCAFEF11E` + 4-byte version hash (XOR of sorted extension hashes) + UTF-8 entries. On load, both values are validated; any mismatch triggers a full rebuild. Cache files live in `System.getProperty("user.home")/.openfilesnavigator/`.

The dirty flag for `custCache` is a zero-byte sentinel file `cust_dirty` in the same directory. Written by the file-watcher when an incremental update overflows or on shutdown; checked at the next dialog open.

### Parallel Walk
`ForkJoinPool` with `parallelism = min(6, availableProcessors)` walks directory trees. Each `WalkTask` recursively forks sub-directories and collects files matching the configured extension set. Results are merged via `ConcurrentLinkedQueue`.

### Query Pipeline
```
User keystroke
  → DocumentListener (EDT)
  → scheduleQueryUpdate() — 80 ms debounce via ScheduledExecutorService
  → snapshot mergedCache on EDT
  → filterAndSort() on background thread
  → BatchListModel.replaceAll() on EDT (single ListDataEvent for ≤100 results)
```

`BatchListModel` avoids O(n) individual `addElement` calls that each fire a `ListDataEvent`. A single `fireContentsChanged(0, size-1)` repaint is dramatically faster for large result sets.

### Ranking
`filterAndSort()` produces at most 100 results, ordered by:
1. Exact filename match
2. Filename / base-name prefix match
3. Tier score: `-cust` variants (1010) > plain PROJECT files (510) > `-base` variants (20) > GENERATED files (11); `+10` for prefix within tier
4. Base name alphabetically, then name alphabetically within the same tier/prefix bucket

Scores are computed into a local `ScoredEntry` wrapper — `FileEntry` objects in the shared cache are never mutated. A size-100 min-heap keeps only the top 100 matches during the scan, avoiding a full sort of the complete match set.

## Non-Obvious Implementation Details

### `seedInitialCoreRoots()`
Iterates open `Project`s, reads the `project.ccs.corefiles` property from `AuxiliaryProperties`, and populates `coreRoots`. Must run off-EDT because `OpenProjects.getOpenProjects()` can block. Called from `componentOpened()` when auto-scan is enabled, or lazily when the dialog first opens.

### Extension Set Versioning
`getIndexExtensionSet()` is called when computing the disk-cache version hash. Changing the extension list in Settings invalidates the disk cache (different hash), forcing a fresh walk. `markCustCacheStale()` is called from the settings dialog OK handler when extensions change.

### `onOpenProjectsChanged()`
Static method called from the `projectListener` in `OpenFilesTopComponent`. Clears `coreRoots` and schedules a core-cache rebuild. Must be called on EDT because it touches `SwingUtilities.invokeLater`.

### File Watcher (`custWatcher`)
Registered as a `FileChangeListener` on the `workspace/` `FileObject`. Only `custCache` has a watcher; build and core caches rely on full rebuilds because their change patterns are too coarse for incremental tracking. The watcher normalises paths to forward slashes before adding to `custCache`.

### Dialog Singleton
`QuickFileSearchDialog` is created once per IDE session and reused. `showDialog()` just makes it visible and clears the text field. Caches survive across show/hide cycles.

## FuzzyMatcher

`FuzzyMatcher.matches(pattern, text)` is a standard in-order character scan: every character of `pattern` must appear in `text` in order, but gaps are allowed. Returns `false` immediately on any missing character. Used in `OpenFilesTopComponent` for the panel's inline filter.

`FuzzyMatcher.score(pattern, text)` returns an `int` used for ranking:
- **+100** prefix bonus (text starts with query)
- **+50** substring bonus (text contains query)
- **+2** per consecutive matched character, **+1** per non-consecutive match
- **−1** per gap character skipped between matches

`filterAndSort` in `QuickFileSearchDialog` uses its own substring/prefix logic with tier scores rather than `FuzzyMatcher.score()`. Do not add allocation inside the score loop.

## Known Gotchas / Constraints

- **`CACHE_LOCK`** is a plain `Object` used with `synchronized` blocks (not `ReentrantLock`). It guards incremental `custCache` mutations from watcher callbacks and is also acquired by the `QuickFileSearch-MergeBuilder` thread while it copies the three lists into the new `mergedCache`. Forgetting the lock causes data races that are hard to reproduce under low concurrency.
- **`buildCache` debounce (3 000 ms)** is intentionally longer than `custCache` because IFS code generation emits dozens of files in a short burst. Lowering this can cause repeated full-rebuilds mid-generation.
- The disk cache stores **absolute paths**. Moving the project directory invalidates both `custCache` and `coreCache` on disk, triggering a full rebuild on next open — this is correct and intended.
- **`indexingEverStarted` flag**: set to `true` after the first cache build completes. The dialog checks this flag before deciding whether to show a "Scanning…" placeholder. Do not reset it without also flushing all caches.
- The `100-result cap` is enforced inside `filterAndSort` via the min-heap, not in the caller. For empty queries the first 100 entries of `mergedCache` are returned directly. Raising the cap degrades EDT repaint performance noticeably above ~300.
