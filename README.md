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

- **List and Tree views** — flat alphabetical list or folder/component tree
- **Fuzzy search** — filter open files as you type — e.g. `CO` for `CustomerOrder`, `POL` for `PurchaseOrderLine`
- **Pins** — keep important files at the top
- **User groups** — organize files into named sections
- **Color tags** — Red / Orange / Green / Blue / Purple for visual status
- **Notes** — attach a short note to any file; display as tooltip, inline `✎` indicator, or subtitle below the filename
- **Recently Closed** — reopen accidentally closed files (last N, configurable)
- **IFS actions** — Generate Code, Generate & Deploy, Execute PL/SQL — from the panel
- **Build file detection** — generated files shown italic/grey, separated into "Generated" section
- **Quick File Search (`Ctrl+P`)** — fuzzy-search every file across all open projects without leaving the keyboard

---

### Quick File Search

Press **Ctrl+P** anywhere in IFS Developer Studio to open a floating search popup.  
Type any part of a filename — results update on every keystroke using fuzzy matching.  
Press **Enter** or click to open the file. **Escape** dismisses.

- Searches both the **customisation layer** (your project workspace) and the **IFS Core Files** checkout simultaneously
- Files are tagged with a colour badge so you always know which layer a result comes from:

| Badge | Colour | Meaning |
|-------|--------|---------|
| *(none)* | — | Your customisation file |
| `CORE` | Purple | IFS core file (`project.ccs.corefiles` path) |
| `GEN` | Orange | Generated / build output file |

- **Performance** — both caches survive dialog close and are held in memory for the entire IDE session:
  - **Core Files** — scanned once per session, never rescanned automatically (changes rarely)
  - **Customisation layer** — scanned once, then monitored via a `FileChangeListener` on the project workspace. The cache is only invalidated when files are actually created or deleted. The dialog always opens instantly showing the existing cache; any rebuild runs silently in the background
- The 🔍 button in the Open Files panel toolbar also opens the dialog

---

## Project structure

```
OpenFilesNavigator/
├── src/com/pardha/openfiles/
│   ├── OpenFilesTopComponent.java   # Main panel — all view logic
│   ├── OpenFilesCellRenderer.java   # List cell renderer
│   ├── PluginPrefs.java             # All persistence (java.util.prefs)
│   ├── FuzzyMatcher.java            # Fuzzy search utility
│   ├── OpenFilesAction.java         # Menu action entry point
│   ├── QuickFileSearchDialog.java   # Ctrl+P quick file search popup
│   ├── QuickFileSearchAction.java   # Action binding for Ctrl+P
│   ├── layer.xml                    # NetBeans layer registration
│   └── Bundle.properties            # Localisation strings
├── nbproject/
│   ├── project.xml                  # Module dependencies
│   └── platform.properties          # Points to IFS DS18 harness
├── libs/                            # Platform JARs for CI (see setup above)
├── .github/workflows/release.yml   # Auto-build and release on tag push
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