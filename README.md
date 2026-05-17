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


## Features

- **List and Tree views** — flat alphabetical list or folder/component tree
- **Fuzzy search** — filter open files as you type - Ex: CO for CustomerOrder, POL for PurchaseOrderLine ...etc
- **Pins** — keep important files at the top
- **User groups** — organize files into named sections
- **Color tags** — Red / Orange / Green / Blue / Purple for visual status
- **Notes** — attach a short note to any file
- **Recently Closed** — reopen accidentally closed files (last N, configurable)
- **IFS actions** — Generate Code, Generate & Deploy, Execute PL/SQL — from the panel
- **Build file detection** — generated files shown italic/grey, separated into "Generated" section

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

---
