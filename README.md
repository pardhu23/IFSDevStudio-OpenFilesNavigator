# OpenFilesNavigator
A dockable **Open Files Navigator** panel for **IFS Developer Studio 18** (NetBeans 18).  
Replaces the built-in tab strip with a resizable, searchable, collapsible panel with
pinning, grouping, color tags, stash/restore, recently-closed history, and IFS-aware actions.

---

<img width="952" height="102" alt="image" src="https://github.com/user-attachments/assets/d4443ef2-11f3-4121-91c8-a12158adb4d4" />


List View:

<img width="530" height="497" alt="image" src="https://github.com/user-attachments/assets/f8e03e55-06a5-487a-8a65-5ecdf35ad23a" />

Tree View:

<img width="527" height="513" alt="image" src="https://github.com/user-attachments/assets/db6b6df8-11d5-4ef1-b627-26e2afe1a19d" />


RMB Options:

<img width="535" height="745" alt="image" src="https://github.com/user-attachments/assets/995e1804-eedb-44ed-8332-fb69c31a65c4" />


---

## Features

### Open Files Panel
- **List and Tree views** — flat list or folder/component tree, switchable from the toolbar
- **Filter bar** — fuzzy filter open files as you type directly in the panel — e.g. `CO` for `CustomerOrder`, `POL` for `PurchaseOrderLine`
- **Pins** — keep important files pinned at the top across IDE restarts
- **User groups** — organize files into named collapsible sections; rename, delete, and reorder groups from the Settings dialog
- **Color tags** — Red / Orange / Green / Blue / Purple for visual status; shown as a stripe in list view and a dot in tree view
- **Notes** — attach a short note to any file; configurable display as tooltip, inline `✎` indicator, or subtitle row below the filename
- **Recently Closed** — reopen accidentally closed files; auto-expands on close, auto-collapses after 5 seconds; history size configurable (1–20)
- **IFS actions** — Generate Code, Generate & Deploy, Generate & Deploy with Dependents, Execute PL/SQL — triggered directly from the panel context menu
- **Ordered multi-file deploy** — when deploying multiple files, they are processed sequentially in a configurable extension order (e.g. `.cre` → `.api` → `.plsql`); same order applies to Execute PL/SQL on multiple build files
- **Customize This** — right-click a core file to create a `-Cust` customisation layer directly from the panel
- **Build file detection** — generated files (paths containing `/build/`) shown italic in slate-blue; optionally grouped under a separate "Generated" section
- **Close button** — × button on every file row in both list and tree views

### Quick File Search (`Ctrl+P`)
Press **Ctrl+P** anywhere in IFS Developer Studio to open a floating search popup.  
Type any part of a filename — results update on every keystroke.  
Press **Enter** or click to open the file. **Escape** dismisses.  
The 🔍 button in the Open Files panel toolbar also opens the dialog.  
The ↻ button inside the dialog manually refreshes all caches.

#### Search behaviour
- **Strict substring/prefix matching** — no fuzzy noise; only files whose base name starts with or contains your query are shown
- Results are ranked by IFS naming convention:

| Rank | File type | Example |
|------|-----------|---------|
| 1st | Your `-Cust` files | `CustomerOrder-Cust.entity` |
| 2nd | Plain core files (no suffix) | `CustomerOrder.entity` `CORE` |
| 3rd | `-Base` files | `CustomerOrder-Base.plsql` |
| 4th | Generated / build output | `CustomerOrder.api` `GEN` |

- Within each tier, **prefix matches rank above contains matches**
- Results are **grouped by base name** — all `CustomerOrder` variants appear together before similar variants like `CustomerOrderLine`
- The `-Cust` / `-Base` suffixes and file extensions are stripped before matching, so searching `CustomerOrder` finds `CustomerOrder-Cust.entity`, `CustomerOrder-Base.plsql`, etc.
- Result rows show filename (bold) and relative path on two lines; full absolute path shown in tooltip on hover

#### Source badges
Files are tagged with a colour badge so you always know which layer a result comes from:

| Badge | Colour | Meaning |
|-------|--------|---------|
| *(none)* | — | customisation file (`-Cust`) |
| `CORE` | Purple | IFS core file (`project.ccs.corefiles` path) |
| `GEN` | Orange | Generated / build output file |

#### Indexed file extensions
Configurable via **Settings → Quick Search — indexed file extensions**.  
Default set covers all standard IFS source types:
`entity`, `plsql`, `storage`, `views`, `utility`, `projection`, `plsvc`, `client`, `svc`, `fragment`, `rdf`, `report`, `cre`, `api`, `apy`, `apv`, `ddlsource`, `dmlsource`, `xetrigger`, `clnsource`

Add or remove extensions at any time — the project cache rebuilds automatically on the next search.

#### Performance & caching
Three independent caches are held in memory for the entire IDE session and survive dialog close/reopen.
Indexing starts automatically in the background when the panel opens — **Ctrl+P is instant on first press**.

| Cache | Source | Invalidation |
|-------|--------|-------------|
| **Project** | `workspace/` files | Incremental: single entry added/removed on file create/delete. No full rebuild unless project changes. |
| **Build** | `build/` generated files | Debounced: one rebuild fires 3 seconds after IFS code-gen bursts finish, not once per generated file. |
| **Core** | `checkout/` core files | Rebuilt only when the set of open project core roots changes (project open/close). Switching from one IFS version to another correctly triggers a full core rescan. |

Additional details:
- Directory walk runs in parallel using a shared `ForkJoinPool` (up to 4 threads)
- File-system watchers are scoped to `workspace/` and `build/` only — never the full project root, avoiding expensive OS-level watch setup on thousands of directories
- Any open NetBeans project without a `workspace/` subdirectory (plain Java projects, plugin projects, etc.) is excluded from indexing and watching entirely
- Multiple projects pointing to the same core checkout are deduplicated — each unique path is walked exactly once
- Excluded from indexing: `server/` directories directly under any `workspace/<module>/` or `checkout/<module>/` path (lobby, configuration and report related `.xsd`, `.rdl` etc.)

### Stash / Restore
Press the 🗃 toolbar button to snapshot your current open files, close them all, and restore them later — like `git stash` for your editor tabs.
- **Stash current files** — name the stash (defaults to timestamp), confirm the file list, then all files are closed and saved
- **Stash selected files** — right-click any selection → *Stash selected files…* to stash only those files
- **Restore** — reopen all files from a named stash; missing files are silently skipped with a summary
- **Peek** — inspect the file list inside a stash before committing to restore
- **Delete** — remove a stash when no longer needed
- Multiple named stashes persist across IDE restarts

### Module Dependency Explorer

Open via the **Module Links** button in the Open Files panel toolbar.  
Displays live module dependency data fetched directly from `MODULE_DEPENDENCY_TAB` in the connected IFS database.

> Requires an active IFS server connection. Data is read from the `DatabaseContentManager` cache — open a project and connect to the database before opening the explorer.

#### Static dependencies tab
Shows all modules and their static dependencies as a flat expandable tree.  
Each module node shows the number of direct dependencies in parentheses.  
Expand a module to see which modules it statically depends on.

#### Dynamic dependencies tab
Same view as Static, but for dynamic dependencies.

#### Static Path Finder tab
Find the dependency chain between any two modules.

| Field | Description |
|-------|-------------|
| **From** | The starting module (e.g. `REQMGT`) |
| **To** | The target module (e.g. `WO`) |

- Press **Find Path** or hit **Enter** in either field to search
- Searches only static dependencies
- Uses Breadth-First Search (BFS) to find all paths between the two modules (capped at 20)
- Each path is shown as a single expandable node: `REQMGT → SERCAT → WO`
- Expanding a path node shows each individual hop: `REQMGT --[STATIC]--> SERCAT`
- If no path exists, a message is shown

**Example:**

<img width="800" height="397" alt="image" src="https://github.com/user-attachments/assets/7d50a194-230f-4bb9-822e-c9f27c22e2a6" />

- Data is read from `DatabaseContentManager.getStaticDependencies()` and `getDynamicDependecies()` via reflection — no compile-time dependency on IFS internal JARs required
- The explorer reads from the in-memory cache populated at DB connect time — no additional DB queries are fired when the window opens
- If the cache is still loading after a fresh connect, close and reopen the explorer after a few seconds

#### Find File from editor (RMB)
Right-click selected text in any PL/SQL or PLSVC file to access two actions:

**Find File** — converts a selected API/SYS package name and opens Quick Search pre-filled:
- `CUSTOMER_ORDER_API` → searches `CustomerOrder`
- `CLIENT_SYS` → searches `Client`
- Works on any snake_case selection even without `_API`/`_SYS` suffix

**Format API Name** — converts and replaces the selected text in the editor:
- `customer_order_api` → `Customer_Order_API`
- `client_sys` → `Client_SYS`
- Each word capitalised, `_API` / `_SYS` suffix kept in full uppercase
- Only activates when selection ends with `_API` or `_SYS`; silently ignored otherwise

---

## Settings

Open via the ⚙ button in the Open Files panel toolbar.

| Setting | Options | Description |
|---------|---------|-------------|
| **Quick Search extensions** | Comma-separated list (wrapping text area) | File extensions to index for Ctrl+P search |
| **Note display** | Tooltip / Inline `✎` / Subtitle | How file notes appear in the list |
| **Generated files** | Inline / Grouped | Show build files inline or under a "Generated" section |
| **Tree grouping** | Folder path / IFS component | How ungrouped files are grouped in tree view |
| **List sort order** | Alphabetical / Open order | Sort open files alphabetically or by the order they were opened |
| **Generate & Deploy order** | Comma-separated extension list (wrapping text area) | Extension order for sequential multi-file deploy and Execute PL/SQL; unlisted extensions go last (A–Z). Default: `cre,cdb,api,apv,apy,entity,utility,ins,projection,fragment,plsvc,client` |
| **Recently closed history** | 1–20 files | How many recently closed files to remember |
| **Manage groups** | Add / Rename / Delete | Create and manage named file groups |

---

## Project structure

```
OpenFilesNavigator/
├── src/com/pardha/openfiles/
│   ├── OpenFilesTopComponent.java   # Main panel — all view logic, stash, context menus
│   ├── OpenFilesCellRenderer.java   # List cell renderer
│   ├── PluginPrefs.java             # All persistence (java.util.prefs) — includes stash, deploy order
│   ├── FuzzyMatcher.java            # Fuzzy search utility (used by panel filter)
│   ├── OpenFilesAction.java         # Menu action entry point
│   ├── QuickFileSearchDialog.java   # Ctrl+P quick file search popup
│   ├── QuickFileSearchAction.java   # Action binding for Ctrl+P
│   ├── FindApiFileAction.java       # RMB "Find File" in PL/SQL editors
│   ├── FormatApiNameAction.java     # RMB "Format API Name" in PL/SQL editors
│   ├── layer.xml                    # NetBeans layer registration
│   └── Bundle.properties            # Localisation strings
├── nbproject/
│   ├── project.xml                  # Module dependencies
│   └── platform.properties          # Points to IFS DS18 harness
├── .gitignore
├── build.xml
└── manifest.mf
```

## Installation

1. Download `com-pardha-openfiles.nbm` from the [latest release](../../releases/latest)
2. In IFS Developer Studio: **Tools → Plugins → Downloaded → Add Plugins**
3. Select the NBM and click **Install**
4. Restart the IDE
5. Open via **Window → Open Files Navigator** or `Ctrl+Shift+O`
6. Use **Ctrl+P** to open Quick File Search at any time
