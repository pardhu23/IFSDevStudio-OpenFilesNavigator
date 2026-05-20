# IFSDevStudio-OpenFilesNavigator
A dockable **Open Files Navigator** panel for **IFS Developer Studio 18** (NetBeans 18).  
Replaces the built-in tab strip with a resizable, searchable, collapsible panel with
pinning, grouping, color tags, recently-closed history, and IFS-aware actions.

---
List View:

<img width="437" height="567" alt="image" src="https://github.com/user-attachments/assets/11c24440-a7c4-4d4f-be21-2c2cf5c6d39a" />

Tree View:

<img width="442" height="572" alt="image" src="https://github.com/user-attachments/assets/98553e36-b5b0-4ea1-9176-97187356b926" />

RMB Options:

<img width="637" height="465" alt="image" src="https://github.com/user-attachments/assets/3c144f0f-4263-48ce-aff5-baecf0f2a106" />

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
- **Build file detection** — generated files (paths containing `/build/`) shown italic in slate-blue; optionally grouped under a separate "Generated" section
- **Close button** — × button on every file row in both list and tree views

### Quick File Search (`Ctrl+P`)
Press **Ctrl+P** anywhere in IFS Developer Studio to open a floating search popup.  
Type any part of a filename — results update on every keystroke.  
Press **Enter** or click to open the file. **Escape** dismisses.  
The 🔍 button in the Open Files panel toolbar also opens the dialog.

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

#### Source badges
Files are tagged with a colour badge so you always know which layer a result comes from:

| Badge | Colour | Meaning |
|-------|--------|---------|
| *(none)* | — | Your customisation file (`-Cust`) |
| `CORE` | Purple | IFS core file (`project.ccs.corefiles` path) |
| `GEN` | Orange | Generated / build output file |

#### Indexed file extensions
Configurable via **Settings → Quick Search — indexed file extensions**.  
Default set covers all standard IFS source types:
`entity`, `plsql`, `storage`, `views`, `utility`, `projection`, `plsvc`, `client`, `svc`, `fragment`, `rdf`, `report`, `cre`, `api`, `apy`, `apv`, `ddlsource`, `dmlsource`, `xetrigger`, `clnsource`

Add or remove extensions at any time — the project cache rebuilds automatically on the next search.

#### Performance & caching
Both caches are held in memory for the entire IDE session and survive dialog close/reopen:

- **Project files** — scanned once at IDE startup (background), then monitored via a `FileChangeListener`. Cache is only invalidated when files are actually created or deleted. The dialog always opens instantly showing the existing cache; any rebuild runs silently in the background with a progress indicator in the IDE status bar.
- **Core files** — scanned once per session, never rescanned automatically (core files change rarely). Multiple projects pointing to the same core checkout are deduplicated — each unique path is scanned exactly once per session. Progress shown in the IDE status bar during the one-time scan.
- Excluded from indexing: `server/` directories directly under any `workspace/<module>/` or `checkout/<module>/` path (lobby, configuration and report related .xsd, .rdl ..etc files)

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
| **Quick Search extensions** | Comma-separated list | File extensions to index for Ctrl+P search |
| **Note display** | Tooltip / Inline `✎` / Subtitle | How file notes appear in the list |
| **Generated files** | Inline / Grouped | Show build files inline or under a "Generated" section |
| **Tree grouping** | Folder path / IFS component | How ungrouped files are grouped in tree view |
| **List sort order** | Alphabetical / Open order | Sort open files alphabetically or by the order they were opened |
| **Recently closed history** | 1–20 files | How many recently closed files to remember |
| **Manage groups** | Add / Rename / Delete | Create and manage named file groups |

---

## Project structure

```
OpenFilesNavigator/
├── src/com/pardha/openfiles/
│   ├── OpenFilesTopComponent.java   # Main panel — all view logic
│   ├── OpenFilesCellRenderer.java   # List cell renderer
│   ├── PluginPrefs.java             # All persistence (java.util.prefs)
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
