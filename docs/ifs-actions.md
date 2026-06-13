# IFS-Specific Actions and Module Dependencies

## Purpose and Scope

These components provide IFS Developer Studio–specific functionality that has no meaning outside of an IFS project: `FindApiFileAction`, `FormatApiNameAction`, `ModuleDependencyTreePanel`, and the inline "IFS: Add File Header Comment" action in the Open Files panel. They rely on IFS naming conventions, project property keys, and (in one case) reflection against loaded IFS classes.

## Key Classes / Files

| File | Role |
|------|------|
| `FindApiFileAction.java` | Converts selected `snake_case_api` text to `PascalCase` and opens Quick Search |
| `FormatApiNameAction.java` | Reformats selected text to `Snake_Case_API` in-editor |
| `ModuleDependencyTreePanel.java` | Displays static/dynamic IFS module dependencies via reflection |
| `OpenFilesTopComponent.java` | `addIfsHeaderComment()` — inline header-comment action (copies to clipboard) |
| `IfsHeaderCommentAction.java` | Inserts a header comment line at the cursor line in any editor (no dialog) |
| `IfsInlineCommentAction.java` | Wraps selected lines with `--(+) YYMMDD DevId CustId (START/FINISH)` markers in the editor |

---

## FindApiFileAction

### Purpose
In IFS, API files follow the convention `CustomerOrderApi.apv` while database identifiers use `CUSTOMER_ORDER_API`. A developer seeing `customer_order_api` in PL/SQL code can select it, invoke this action, and immediately jump to the file without manual conversion.

### Conversion algorithm
1. Strip a trailing `_api`, `_Api`, or `_API` suffix (case-insensitive).
2. Split the remainder on `_` and capitalise each segment.
3. Append `Api` (no underscore).

Example: `customer_order_api` → `CustomerOrder` + search opens with `CustomerOrder` pre-filled (finds `CustomerOrderApi.apv`, `CustomerOrderApi.plsvc`, etc.).

### Registration
Registered via `@ActionID` / `@ActionReference` at position 320 in the PlSQL editor popup menu (`Editors/text/x-plsql/Popup`). Only appears when text is selected in a PL/SQL editor.

---

## FormatApiNameAction

### Purpose
Formats selected database-style identifiers to the IFS mixed-case convention used in source file content. For example, `CUSTOMER_ORDER_API` → `Customer_Order_API`. This is used when writing IFS view or projection definitions where the mixed-case form is required by the IFS framework parser.

### Conversion algorithm
1. Split on `_`.
2. Capitalise the first character of each segment; lowercase the rest.
3. Re-join with `_`.

Example: `customer_order_api` → `Customer_Order_Api` (segments capitalised independently).

### Registration
Registered at position 322 in the same PlSQL popup menu, immediately after `FindApiFileAction`. Replaces the selected text in-place using the editor's `Document`.

---

## ModuleDependencyTreePanel

### Purpose
IFS applications are composed of modules that depend on each other at two levels: *static* (declared in module definitions) and *dynamic* (resolved at runtime from loaded configuration). This panel reads both levels and provides a tree + path-finder UI for dependency analysis during deploy-order debugging.

### Architecture

#### Reflection-based IFS API access
The panel uses `Class.forName()` and reflection to call:
- `DatabaseContentUtilitiesSqlLibrary.getStaticDependencies(moduleName)`
- `DatabaseContentManager.getDynamicDependecies(moduleName)` *(note: typo in the IFS API is preserved)*

This avoids any compile-time dependency on IFS modules. If the classes are absent (non-IFS IDE), the panel shows an error message instead of crashing.

#### Three tabs

| Tab | Data source | Notes |
|-----|------------|-------|
| Static | `getStaticDependencies()` via reflection | Shows compile-time declared deps |
| Dynamic | `getDynamicDependecies()` via reflection | Shows runtime-resolved deps |
| Static Path Finder | BFS over static deps | Finds all paths between two modules, capped at 20 |

#### BFS path finder
`findAllPaths(from, to, staticDeps)` runs a breadth-first search over the static dependency graph. It tracks visited nodes to avoid cycles and caps results at 20 paths to prevent memory explosions on highly connected graphs. Results are rendered as `A → B → C → D` strings in a `JTextArea`.

#### deploy.ini navigation
Right-clicking a module node shows "Open deploy.ini [MODULE]". The action resolves the file path using:
1. Open projects' root directories + `/workspace/<MODULE>/deploy.ini`
2. Core checkout root (`project.ccs.corefiles` property) + `/<MODULE>/deploy.ini`
Then opens it via `DataObject` / `OpenCookie`.

### Non-Obvious Details

- **Reflection call safety**: All reflection is wrapped in a try-catch. If the IFS class is loaded but the method signature has changed, the panel logs the error to `System.err` and shows the error in the tree rather than propagating a crash.
- **`openWindow()` static method**: Opens the panel in a standalone `JFrame`. This is intentional — the module dependency panel is a utility tool that should not dock into the main window system.
- **Module name normalisation**: The panel passes module names to the IFS API in uppercase. The tree displays them as returned by the API (mixed case in some IFS versions).

---

## IFS: Add File Header Comment

### Purpose
Prepends a correctly-formatted IFS change-history line to any non-generated file open in the panel. Format:

```
--  260420  PAYEIN XXXXXX: Add source file for C_BC_FREIGHT_SEQ.
```

(Or `//` prefix for Java / JS / TS / CS / CSS / SCSS files.)

### Configuration
Developer ID and Customization ID are stored via `PluginPrefs.getDeveloperId()` / `getCustomizationId()` (keys `developerId` / `customizationId` under the root preferences node). Both are set in the Settings dialog (⚙) under the "IFS — header comment identity" section. Values are auto-uppercased on save.

### Activation
Available as "IFS: Add File Header Comment…" in the right-click (RMB) context menu on any single non-generated file. Disabled for multi-selection and for build/generated files.

### Flow
1. If Developer ID or Customization ID is blank, a warning dialog is shown pointing to Settings.
2. A dialog prompts for the description text. A live preview below the field shows the exact line that will be produced, updating as you type.
3. On OK, the formatted line is copied to the system clipboard. The user pastes it wherever they need it.

### Known Constraint
The comment prefix (`--` or `//`) is determined by the target file's extension. Unrecognised extensions default to `--`.

---

## IFS: Insert Header Comment

### Purpose
Inserts a correctly-formatted IFS change-history comment line directly into the editor at the current cursor line — no dialog, no clipboard step. The line ends with `": "` so the user can type the description immediately.

Example output inserted before the cursor line:
```
--  260613  PAYEIN XXXXXX: 
```

### Format
`<prefix>  <YYMMDD>  <DevId> <CustId>: ` — double spaces around the date, colon+space at end. Prefix is `--` by default; `//` for Java / JS / TS / CS / CSS / SCSS / JSX / TSX files (detected from the file's extension via the document's `StreamDescriptionProperty`).

### Registration
`IfsHeaderCommentAction` — registered at position 322 in `Editors/Popup`, so it appears in every editor type's right-click menu.

### Behavior
1. Resolves DevId and CustId from `PluginPrefs`; shows a warning dialog if either is blank.
2. Detects `--` vs `//` prefix from the open file's extension.
3. Walks left from the caret to find the start of the current line.
4. Calls `Document.insertString(lineStart, line + "\n", null)` to push the comment in above the current line.
5. Moves the caret to the end of the inserted text (after `": "`, before `\n`) so the user can type the description immediately.

---

## IFS: Wrap with (+) inline code comment

### Purpose
Wraps the selected lines in an open editor with IFS-style start/finish change markers:

```
--(+) 260613 PAYEIN XXXXX (START)
   field CConnectPulse {
      visible = [false];
   }
--(+) 260613 PAYEIN XXXXX (FINISH)
```

### Format
`--(+) <YYMMDD> <DevId> <CustId> (START)` / `(FINISH)` — 6-digit YYMMDD date, Developer ID and Customization ID both required. Markers are indented to match the indentation of the first selected line.

### Registration
`IfsInlineCommentAction` — registered at position 324 in `Editors/Popup` (the generic editor popup), so it appears in the right-click menu of every file type. If Developer ID or Customization ID is blank the action shows a warning dialog pointing to Settings.

### Behavior
1. Gets the selection start and end offsets from the focused editor.
2. Expands both offsets to full-line boundaries (walks left/right to the nearest `\n`).
3. Reads leading whitespace from the first line and prepends it to both markers so they align with the code.
4. Replaces the expanded selection with `indent+startMarker\n<block>\nindent+finishMarker\n` in a single `replaceSelection` call — one undo step.

---

## Known Gotchas / Constraints

- **Reflection failures are silent to the user**: If `Class.forName` fails (IFS classes not on classpath), the tabs show empty trees rather than an error. Check `System.err` / IDE log if the panel appears empty.
- **`getDynamicDependecies` is a typo in the IFS API** — it is spelled with one `n`. Do not "fix" it; the reflection call must match the actual method name.
- **BFS cap of 20 paths**: On a large IFS installation with a highly interconnected module graph, BFS can find thousands of paths. The cap prevents UI freezes but means the path-finder may not show the shortest path if shorter paths are found after the 20th result.
- **`FindApiFileAction` strips only `_api` suffix variants**: It does not handle other IFS suffixes like `_tab`, `_seq`, etc. Extend the strip-list if needed for other file types.
