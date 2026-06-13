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
- **Sort** (`⇅` toolbar button, leftmost) — four sort modes: **Name A→Z**, **Open order**, **Recently activated** (re-ranks live as you switch files), **File type** (grouped by extension)
- **Recently Closed** — reopen accidentally closed files; auto-expands on close, auto-collapses after 5 seconds; history size configurable (1–20)
- **IFS actions** — Generate Code, Generate & Deploy, Generate & Deploy with Dependents, Execute PL/SQL — triggered directly from the panel context menu
- **Ordered multi-file deploy** — when deploying multiple files, they are processed sequentially in a configurable extension order (e.g. `.cre` → `.api` → `.plsql`); same order applies to Execute PL/SQL on multiple build files
- **Customize This** — right-click a core file to create a `-Cust` customisation layer directly from the panel
- **Build file detection** — generated files (paths containing `/build/`) shown italic in slate-blue; optionally grouped under a separate "Generated" section
- **Close button** — × button on every file row in both list and tree views

#### Toolbar order (left → right)
`⇅ Sort` · `≡ List` · `⊞ Tree` · `🔍 Search` · `🗃 Stash` · `Module Links` · `⚙ Settings`

### Customization ID Strip

An always-visible inline-editable strip pinned at the very bottom of the panel, below the Recently Closed section.

- Shows the current **Customization ID** at all times
- Edit in place and press **Enter** or click away — saves instantly to preferences
- Value is preserved exactly as typed (no case conversion)
- Used automatically by the IFS comment actions below

### Quick File Search (`Alt+P` / `Ctrl+P`)
Press **Alt+P** or **Ctrl+P** anywhere in IFS Developer Studio to open a floating search popup.  
Type any part of a filename — results update on every keystroke.  
Press **Enter** or click to open the file. **Escape** dismisses.  
The 🔍 button in the Open Files panel toolbar also opens the dialog. 
The ⇄ button converts a pasted `_API` / `_SYS` / `_SVC` package name to PascalCase inline — e.g. type `customer_order_api` and press ⇄ to replace it with `CustomerOrder`. 
The ↻ button inside the dialog manually refreshes all caches.

#### Search behaviour
- **Fuzzy in-order matching** — every character in your query must appear in the filename in order, but gaps are allowed; `CO` matches `CustomerOrder`, `POL` matches `PurchaseOrderLine`
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
Indexing starts automatically in the background when the panel opens — **Alt+P is instant on first press**.

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

### Git Status Strip

An embedded collapsible strip at the bottom of the Open Files panel. Click the `▼ Git Status` header to expand or collapse it.

- **Staged / Unstaged / Untracked** — changed files grouped into three sections with per-section counts
- **Count summary** — header shows `A N  M N  D N  R N` (added, modified, deleted, renamed) even when collapsed
- **Filter** — type to narrow files within the strip
- **Context menu** — **Stage** (`git add`), **Unstage** (`git restore --staged`), **Discard changes** (`git restore`), **Show in Project**
- **Branch switching** — click the `⎇ branchName` label to open a local-branch popup; selecting a branch runs `git checkout` and refreshes the status
- **Multi-project** — aggregates status across all open IFS projects in the workspace

The strip runs `git status --porcelain` directly via `ProcessBuilder` (not the NetBeans Git plugin) for sub-second response times. If Git is not on your `PATH`, set the executable path in **Settings → Git executable**.

---

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

#### Right-click a module node — Open deploy.ini
Right-click any module in the Static or Dynamic tab to open its `deploy.ini` directly in the IDE editor.

Resolution order:
1. `{projectRoot}/workspace/{MODULE}/deploy.ini` — from current workspace path
2. `{coreFilesRoot}/{MODULE}/deploy.ini` — from IFS core path  (path read from `project.ccs.corefiles`)

If neither file is found, a dialog lists the paths that were checked.

### IFS Developer Identity

Developer ID and Customization ID are stored in the plugin's preferences and used by all IFS comment actions.

| Field | Where to set | Behaviour |
|-------|-------------|-----------|
| **Developer ID** | Settings dialog → *IFS — header comment identity* | Saved as typed; used in all generated comment lines |
| **Customization ID** | Bottom strip in the panel | Always visible; edit inline and press Enter or click away to save instantly |

Both fields are stored as typed — no uppercase conversion is applied.

---

### IFS Comment Actions

#### IFS: Insert Header Comment *(editor right-click — all file types)*

Inserts a correctly-formatted IFS change-history comment line directly at the cursor line in the editor. No dialog is shown. The line ends with `": "` so you can type the description immediately.

```
--  260613  PAYEIN TEST_CRIM1: 
```

- Prefix is `//` for `.java`, `.entity`, `.utility`, `.enumeration` files; `--` for everything else
- Caret is placed after the colon+space — start typing the description
- Registered in **Editors/Popup** so it appears in every file type's right-click menu (position 322)
- If Developer ID or Customization ID is blank, a warning dialog is shown pointing to Settings

#### IFS: Copy Header Comment *(panel right-click — single non-generated files)*

Opens a small dialog with a description field and a live preview. On **OK**, copies the formatted header comment line to the clipboard — paste it wherever you need it.

```
--  260613  PAYEIN TEST_CRIM1: Add source file for C_BC_FREIGHT_SEQ.
```

#### IFS: Wrap with (+) inline code comment *(editor right-click — all file types)*

Wraps the selected lines in the editor with IFS-style `(+)` start/finish change markers. The markers are indented to match the first line of the selection.

```
   --(+) 260613 PAYEIN TEST_CRIM1 (START)
   field Test {
      visible = [false];
   }
   --(+) 260613 PAYEIN TEST_CRIM1 (FINISH)
```

- Select one or more lines, then right-click → **IFS: Wrap with (+) inline code comment**
- Selection is automatically expanded to full-line boundaries
- The wrap is a single undo step
- Registered in **Editors/Popup** at position 324 — available in all file types
- If Developer ID or Customization ID is blank, a warning dialog is shown

---

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
| **Quick Search extensions** | Comma-separated list (wrapping text area) | File extensions to index for Alt+P search |
| **Note display** | Tooltip / Inline `✎` / Subtitle | How file notes appear in the list |
| **Generated files** | Inline / Grouped | Show build files inline or under a "Generated" section |
| **Tree grouping** | Folder path / IFS component | How ungrouped files are grouped in tree view |
| **List sort order** | Name A→Z / Open order / Recently activated / File type | Sort open files; "Recently activated" re-ranks live as you switch files |
| **Generate & Deploy order** | Comma-separated extension list (wrapping text area) | Extension order for sequential multi-file deploy and Execute PL/SQL; unlisted extensions go last (A–Z). Default: `cre,cdb,api,apv,apy,entity,utility,ins,projection,fragment,plsvc,client` |
| **Recently closed history** | 1–20 files | How many recently closed files to remember |
| **Manage groups** | Add / Rename / Delete | Create and manage named file groups |
| **Git executable** | Path string | Override the `git` executable path if it is not on the system `PATH` |
| **Developer ID** | Free text | Your IFS developer ID used in all generated comment lines (e.g. `PAYEIN`) |

> **Customization ID** is not in the Settings dialog — set it in the always-visible strip at the bottom of the panel.

---

## Project structure

```
OpenFilesNavigator/
├── src/com/pardha/openfiles/
│   ├── OpenFilesTopComponent.java      # Main panel — all view logic, stash, context menus
│   ├── OpenFilesCellRenderer.java      # List cell renderer
│   ├── GitStatusPanel.java             # Embedded git status strip (staged/unstaged/untracked)
│   ├── PluginPrefs.java                # All persistence (java.util.prefs) — includes stash, deploy order
│   ├── FuzzyMatcher.java               # Fuzzy search utility (used by panel filter and quick search)
│   ├── ModuleDependencyTreePanel.java  # Module dependency explorer window
│   ├── OpenFilesAction.java            # Menu action entry point
│   ├── QuickFileSearchDialog.java      # Alt+P quick file search popup
│   ├── QuickFileSearchAction.java      # Action binding for Alt+P / Ctrl+P
│   ├── FindApiFileAction.java          # RMB "Find File" in PL/SQL editors
│   ├── FormatApiNameAction.java        # RMB "Format API Name" in PL/SQL editors
│   ├── IfsHeaderCommentAction.java     # Editor RMB "IFS: Insert Header Comment" (all file types)
│   ├── IfsInlineCommentAction.java     # Editor RMB "IFS: Wrap with (+) inline code comment" (all file types)
│   ├── layer.xml                       # NetBeans layer registration
│   └── Bundle.properties               # Localisation strings
├── nbproject/
│   ├── project.xml                     # Module dependencies
│   └── platform.properties             # Points to IFS DS18 harness
├── .gitignore
├── build.xml
└── manifest.mf
```

## Installation

1. Download `com-pardha-openfiles.nbm` from the [latest release](../../releases/latest)
2. In IFS Developer Studio: **Tools → Plugins → Downloaded → Add Plugins**
3. Select the NBM and click **Install**
4. Restart the IDE
5. Open via **Window → Open Files Navigator** or `Alt+Shift+O`
6. Use **Alt+P** or **Ctrl+P** to open Quick File Search at any time
