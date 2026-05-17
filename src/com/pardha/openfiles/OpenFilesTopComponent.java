package com.pardha.openfiles;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;
import org.openide.awt.Actions;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectNotFoundException;
import org.openide.util.ContextAwareAction;
import org.openide.util.Lookup;
import org.openide.util.RequestProcessor;
import org.openide.util.actions.SystemAction;
import org.openide.util.lookup.ProxyLookup;
import org.openide.windows.*;

/**
 * Dockable "Open Files Navigator" panel — List and Tree views.
 *
 * <h3>List view layout (top → bottom)</h3>
 * <ol>
 *   <li>📌 Pinned   — collapsible header + pinned file rows</li>
 *   <li>⊡ GroupA   — collapsible header + files in that group</li>
 *   <li>Ungrouped  — collapsible header + all other non-build files</li>
 *   <li>Generated  — collapsible header + build files
 *                    (only shown when buildGrouping = GROUPED; otherwise
 *                     build files appear inline in their natural sections)</li>
 * </ol>
 *
 * <h3>Build files</h3>
 * Detected by {@code /build/} in the tooltip path.  Rendered italic +
 * slate-blue in both views.  Their context menu shows only
 * "Execute PL/SQL Command" — the generate/deploy actions are hidden.
 *
 * <h3>Execute PL/SQL Command</h3>
 * Activates the TC, then fires the editor action named
 * {@code "Execute PLSQL Command"} (bound to OS-E) via the focused
 * component's ActionMap.
 */
public final class OpenFilesTopComponent extends TopComponent {

    private static OpenFilesTopComponent instance;
    private static final RequestProcessor RP = new RequestProcessor("OpenFilesRefresh", 1);

    // ── IFS action IDs ────────────────────────────────────────────────────
    private static final String CAT_IFS           = "IFS-Models";
    private static final String CAT_PLSQL_IMPL    = "PlSqlImpl";
    private static final String CAT_PLSQL_TOOLBAR = "PlSqlImplToolbar";
    private static final String ACT_GENERATE_CODE =
            "ifs.dev.nb.model.config.action.GenerateCodeAction";
    private static final String ACT_GENERATE_DEPLOY =
            "org.netbeans.modules.plsql.execution.actions.GenerateCodeAndDeployNodeAction";
    private static final String ACT_GENERATE_DEPLOY_DEPENDENTS =
            "ifs.dev.nb.project.ui.explorer.version2.actions.GenCodeDeployWithDependentsAction";

    /** Editor action name for executing a PL/SQL file — kept for reference only. */
    // private static final String EDITOR_ACT_EXECUTE_PLSQL = "Execute PLSQL Command";

    // ── View mode ─────────────────────────────────────────────────────────
    private enum ViewMode { LIST, TREE }
    private ViewMode viewMode = ViewMode.LIST;

    // ── Master list ───────────────────────────────────────────────────────
    private final java.util.List<TopComponent> allEditors = new ArrayList<>();

    /**
     * Tracks the order in which editor TCs were first seen, oldest-first.
     * Used for LIST_SORT_OPEN_ORDER. Entries are added on PROP_OPENED and
     * pruned when a TC is no longer in the opened set during refreshList().
     */
    private final java.util.LinkedHashSet<TopComponent> openOrder =
            new java.util.LinkedHashSet<>();

    // ── List view ─────────────────────────────────────────────────────────
    private final DefaultListModel<Object> listModel   = new DefaultListModel<>();
    private final JList<Object>            fileList    = new JList<>(listModel);
    private final JScrollPane              listScroll  = new JScrollPane(fileList);
    private final OpenFilesCellRenderer    listRenderer = new OpenFilesCellRenderer();

    // ── Tree view ─────────────────────────────────────────────────────────
    private final DefaultTreeModel treeModel  =
            new DefaultTreeModel(new DefaultMutableTreeNode("root"));
    private final JTree            fileTree   = new JTree(treeModel);
    private final JScrollPane      treeScroll = new JScrollPane(fileTree);

    // ── Shared ────────────────────────────────────────────────────────────
    private final JTextField    searchField = new JTextField();
    private final JToggleButton listBtn     = new JToggleButton("\u2261"); // ≡
    private final JToggleButton treeBtn     = new JToggleButton("\u229E"); // ⊞
    private final JPanel        centerPanel = new JPanel(new CardLayout());

    private static final String CARD_LIST = "list";
    private static final String CARD_TREE = "tree";

    // ── Recently-closed strip ─────────────────────────────────────────────
    /** Outer wrapper added to BorderLayout.SOUTH — always present in the layout. */
    private final JPanel          closedStripWrapper  = new JPanel(new BorderLayout());
    /** The header row: toggle arrow + label + clear button. Always visible. */
    private final JPanel          closedStripHeader   = new JPanel(new BorderLayout());
    /** The body: one row per closed file. Hidden when strip is collapsed. */
    private final JPanel          closedStripBody     = new JPanel();
    /** Row height for closed-file entries (matches fileList.fixedCellHeight). */
    private static final int      CLOSED_ROW_H        = 24;
    /** Auto-collapse timer: collapses the strip N ms after a file is closed. */
    private javax.swing.Timer     closedAutoCollapseTimer;

    // ── Refresh coalescing ────────────────────────────────────────────────
    private RequestProcessor.Task pendingRefresh;
    private boolean listenersRegistered = false;

    // ── Registry listener ─────────────────────────────────────────────────
    private final PropertyChangeListener windowListener = evt -> {
        String prop = evt.getPropertyName();
        if (TopComponent.Registry.PROP_OPENED.equals(prop)) {
            Set<TopComponent> nowOpen = TopComponent.getRegistry().getOpened();

            // Detect files that were in allEditors but are no longer open —
            // these were just closed. Capture their info before they go away.
            // Guard: skip files opened from the OS temp directory — these are
            // versioning system copies (diff/view) that can't be reopened.
            String tempDir = System.getProperty("java.io.tmpdir", "")
                               .replace('\\', '/').toLowerCase(Locale.ROOT);
            for (TopComponent tc : allEditors) {
                if (!nowOpen.contains(tc)) {
                    String tip  = tc.getToolTipText();
                    String path = "";
                    if (tip != null && !tip.trim().isEmpty()) {
                        path = tip.replace('\\', '/').trim()
                                  .replaceAll("<[^>]+>", "").trim();
                        // Strip trailing versioning annotations from the path.
                        // IFS appends e.g. " (read-only)", " [r/o]", " [-/M]"
                        // after the actual file path in the tooltip text.
                        // Remove any trailing " (...)" or " [...]" groups.
                        path = path.replaceAll("\\s+[\\(\\[][^\\)\\]]*[\\)\\]]\\s*$", "").trim();
                    }
                    // Skip temp-dir files — versioning system artefacts
                    if (!path.isEmpty()
                            && path.toLowerCase(Locale.ROOT).startsWith(tempDir)) {
                        continue;
                    }
                    String name = resolveDisplayNameStatic(tc);
                    if (name == null) name = tc.getName();
                    // Strip versioning suffixes from name too.
                    name = name.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]\\s*$", "").trim();
                    if (name.isEmpty()) name = resolveDisplayNameStatic(tc);
                    PluginPrefs.pushClosedEntry(new PluginPrefs.ClosedEntry(name, path));
                }
            }

            // Record newly opened editor TCs into the open-order set.
            for (TopComponent tc : nowOpen) {
                if (isEditorTC(tc)) openOrder.add(tc);
            }

            // Track whether any file was actually closed this cycle
            final boolean anyClosed = allEditors.stream().anyMatch(tc -> !nowOpen.contains(tc));

            SwingUtilities.invokeLater(() -> {
                refreshList();
                if (anyClosed) rebuildClosedStrip();
                if (anyClosed) autoExpandClosedStrip();
            });
            scheduleDelayedRefresh();
        } else if (TopComponent.Registry.PROP_ACTIVATED.equals(prop)) {
            SwingUtilities.invokeLater(this::updateActiveHighlight);
        }
    };

    // ── Singleton ─────────────────────────────────────────────────────────
    public static synchronized OpenFilesTopComponent getInstance() {
        if (instance == null) instance = new OpenFilesTopComponent();
        return instance;
    }

    private OpenFilesTopComponent() {
        setName("Open Files");
        setToolTipText("Lists all currently open editor files");
        putClientProperty("TopComponentAllowDockAnywhere", Boolean.TRUE);
        viewMode = PluginPrefs.VIEW_TREE.equals(PluginPrefs.getViewMode())
                ? ViewMode.TREE : ViewMode.LIST;
        initComponents();
        refreshList();
    }

    private void scheduleDelayedRefresh() {
        if (pendingRefresh != null) pendingRefresh.cancel();
        pendingRefresh = RP.post(() -> SwingUtilities.invokeLater(this::refreshList), 1500);
    }

    // =========================================================================
    // UI construction
    // =========================================================================

    private void initComponents() {
        setLayout(new BorderLayout());

        JPanel northPanel = new JPanel(new BorderLayout(0, 2));
        northPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
        northPanel.setBackground(UIManager.getColor("Panel.background"));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        JLabel header = new JLabel("Open Files");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 11f));
        titleRow.add(header, BorderLayout.WEST);

        ButtonGroup bg = new ButtonGroup();
        bg.add(listBtn); bg.add(treeBtn);
        listBtn.setToolTipText("List view");
        treeBtn.setToolTipText("Tree view (grouped by folder)");
        styleModeButton(listBtn);
        styleModeButton(treeBtn);
        (viewMode == ViewMode.TREE ? treeBtn : listBtn).setSelected(true);
        listBtn.addActionListener(e -> switchView(ViewMode.LIST));
        treeBtn.addActionListener(e -> switchView(ViewMode.TREE));

        JButton settingsBtn = new JButton("\u2699"); // ⚙
        settingsBtn.setFocusable(false);
        settingsBtn.setMargin(new Insets(1, 4, 1, 4));
        settingsBtn.setFont(settingsBtn.getFont().deriveFont(12f));
        settingsBtn.setToolTipText("Settings");
        settingsBtn.addActionListener(e -> openSettingsDialog());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(listBtn); btnPanel.add(treeBtn);
        btnPanel.add(Box.createHorizontalStrut(4));
        btnPanel.add(settingsBtn);
        titleRow.add(btnPanel, BorderLayout.EAST);
        northPanel.add(titleRow, BorderLayout.NORTH);

        searchField.setToolTipText("Fuzzy filter files (Esc to clear)");
        searchField.putClientProperty("JTextField.placeholderText", "Search\u2026");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        searchField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) searchField.setText("");
                else if (e.getKeyCode() == KeyEvent.VK_DOWN
                        || e.getKeyCode() == KeyEvent.VK_ENTER) focusCurrentView();
            }
        });
        northPanel.add(searchField, BorderLayout.SOUTH);
        add(northPanel, BorderLayout.NORTH);

        setupListView();
        setupTreeView();
        centerPanel.add(listScroll, CARD_LIST);
        centerPanel.add(treeScroll, CARD_TREE);
        add(centerPanel, BorderLayout.CENTER);

        setupClosedStrip();
        add(closedStripWrapper, BorderLayout.SOUTH);

        CardLayout cl = (CardLayout) centerPanel.getLayout();
        cl.show(centerPanel, viewMode == ViewMode.TREE ? CARD_TREE : CARD_LIST);
    }

    private void styleModeButton(JToggleButton btn) {
        btn.setFocusable(false);
        btn.setMargin(new Insets(1, 5, 1, 5));
        btn.setFont(btn.getFont().deriveFont(12f));
    }

    // =========================================================================
    // List view setup
    // =========================================================================

    private void setupListView() {
        fileList.setCellRenderer(listRenderer);
        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileList.setFixedCellHeight(24);
        fileList.setBorder(new EmptyBorder(2, 2, 2, 2));

        fileList.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = fileList.locationToIndex(e.getPoint());
                if (row >= 0) {
                    Rectangle cell = fileList.getCellBounds(row, row);
                    if (cell == null || !cell.contains(e.getPoint())) row = -1;
                }
                if (listRenderer.hoveredRow != row) {
                    listRenderer.hoveredRow = row;
                    fileList.repaint();
                }
            }
        });

        fileList.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                if (listRenderer.hoveredRow != -1) {
                    listRenderer.hoveredRow = -1;
                    fileList.repaint();
                }
            }

            // mousePressed fires exactly once per physical click, with no
            // ambiguity about click-count accumulation.  We use it for actions
            // that must respond immediately: header collapse toggle and close
            // button.  File activation is left to mouseClicked (count==1) so
            // that Swing's built-in drag-selection still works.
            @Override public void mousePressed(MouseEvent e) {
                int idx = fileList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                Object item = listModel.getElementAt(idx);

                if (SwingUtilities.isRightMouseButton(e)) {
                    if (!fileList.isSelectedIndex(idx)) fileList.setSelectedIndex(idx);
                    SwingUtilities.invokeLater(() -> showListContextMenu(e.getX(), e.getY()));
                    return;
                }

                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (item instanceof OpenFilesCellRenderer.GroupHeader) {
                        // Clear any existing selection so the previously
                        // highlighted file row doesn't stay blue after clicking
                        // a header. Headers are not selectable list items.
                        fileList.clearSelection();
                        // Toggle collapse — deferred so the press event finishes first.
                        final String secName =
                                ((OpenFilesCellRenderer.GroupHeader) item).name;
                        SwingUtilities.invokeLater(() -> toggleCollapse(secName));
                        // Consume so JList doesn't try to select the header row.
                        e.consume();
                        return;
                    }
                    // Close button zone: left-edge of the cell
                    Rectangle cell = fileList.getCellBounds(idx, idx);
                    if (cell != null) {
                        if (e.getX() < cell.x + OpenFilesCellRenderer.CLOSE_BTN_WIDTH
                                && item instanceof TopComponent) {
                            ((TopComponent) item).close();
                            e.consume();
                            return;
                        }
                    }
                }
            }

            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                int idx = fileList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                Object item = listModel.getElementAt(idx);
                // Headers are handled in mousePressed; skip them here.
                if (item instanceof OpenFilesCellRenderer.GroupHeader) return;
                // Activate on single-click with single selection only.
                if (e.getClickCount() == 1 && fileList.getSelectedIndices().length == 1)
                    activateFromList();
            }
        });

        fileList.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int idx = fileList.getSelectedIndex();
                if (e.getKeyCode() == KeyEvent.VK_ENTER
                        || e.getKeyCode() == KeyEvent.VK_SPACE) {
                    if (idx >= 0 && listModel.getElementAt(idx)
                            instanceof OpenFilesCellRenderer.GroupHeader) {
                        // Use invokeLater so the key event finishes before
                        // the model is rebuilt (same fix as mouse double-click).
                        final String secName = ((OpenFilesCellRenderer.GroupHeader)
                                listModel.getElementAt(idx)).name;
                        SwingUtilities.invokeLater(() -> toggleCollapse(secName));
                    } else {
                        activateFromList();
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    searchField.setText("");
                    searchField.requestFocusInWindow();
                } else if (!e.isActionKey()
                        && e.getKeyCode() != KeyEvent.VK_BACK_SPACE
                        && !e.isControlDown() && !e.isMetaDown()
                        && !e.isShiftDown()) {
                    searchField.requestFocusInWindow();
                    searchField.dispatchEvent(e);
                }
            }
        });

        listScroll.setBorder(BorderFactory.createEmptyBorder());
    }

    // =========================================================================
    // Tree view setup
    // =========================================================================

    private void setupTreeView() {
        fileTree.setRootVisible(false);
        fileTree.setShowsRootHandles(true);
        fileTree.setCellRenderer(new OpenTreeCellRenderer());
        fileTree.getSelectionModel().setSelectionMode(
                TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);

        fileTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());

                if (SwingUtilities.isRightMouseButton(e)) {
                    if (path == null) { fileTree.clearSelection(); e.consume(); return; }
                    if (!fileTree.isPathSelected(path)) fileTree.setSelectionPath(path);
                    e.consume();
                    return;
                }

                if (!SwingUtilities.isLeftMouseButton(e)) return;
                if (path == null) return;

                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) path.getLastPathComponent();

                // ── Folder node ───────────────────────────────────────────────
                // Consume the event so JTree's UI delegate does not ALSO toggle
                // expand/collapse (which would fight our persisted state).
                // We toggle manually and persist the new state.
                if (node.getUserObject() instanceof String) {
                    // Only toggle on the node label area, not the expand handle.
                    // The expand handle is to the left of the bounds rect; clicks
                    // there have no bounds returned by getPathBounds, so this
                    // block is never reached for handle clicks — JTree handles
                    // those natively, which is fine.
                    e.consume();
                    String sectionName = (String) node.getUserObject();
                    if (sectionName.startsWith("⊞ ")) {
                        sectionName = sectionName.substring(2);
                    }
                    final String finalName = sectionName;
                    if (fileTree.isExpanded(path)) {
                        fileTree.collapsePath(path);
                        PluginPrefs.setGroupCollapsed(finalName, true);
                    } else {
                        fileTree.expandPath(path);
                        PluginPrefs.setGroupCollapsed(finalName, false);
                    }
                    return;
                }

                // ── File node ─────────────────────────────────────────────────
                if (e.isControlDown() || e.isShiftDown()) return; // multi-select: let JTree handle

                // Close button zone (left edge of the cell bounds)
                Rectangle bounds = fileTree.getPathBounds(path);
                if (bounds != null) {
                    if (e.getX() < bounds.x + OpenFilesCellRenderer.CLOSE_BTN_WIDTH) {
                        if (node.getUserObject() instanceof TopComponent) {
                            ((TopComponent) node.getUserObject()).close();
                            e.consume();
                            return;
                        }
                    }
                }

                if (node.getUserObject() instanceof TopComponent)
                    ((TopComponent) node.getUserObject()).requestActive();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) return;
                TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());
                if (path != null) showTreeContextMenu(e.getX(), e.getY());
            }
        });

        fileTree.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) activateFromTree();
                else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    searchField.setText(""); searchField.requestFocusInWindow();
                } else if (!e.isActionKey() && e.getKeyCode() != KeyEvent.VK_BACK_SPACE
                        && !e.isControlDown() && !e.isMetaDown()) {
                    searchField.requestFocusInWindow(); searchField.dispatchEvent(e);
                }
            }
        });

        treeScroll.setBorder(BorderFactory.createEmptyBorder());
    }

    // =========================================================================
    // Recently-closed strip
    // =========================================================================

    private void setupClosedStrip() {
        // ── Wrapper ───────────────────────────────────────────────────────
        closedStripWrapper.setOpaque(false);
        closedStripWrapper.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                UIManager.getColor("Separator.foreground") != null
                        ? UIManager.getColor("Separator.foreground")
                        : new Color(180, 180, 180)));

        // ── Header row ────────────────────────────────────────────────────
        closedStripHeader.setOpaque(true);
        Color hdrBg = UIManager.getColor("Panel.background");
        if (hdrBg == null) hdrBg = new Color(240, 240, 240);
        // Slightly tinted so it reads as a footer, not part of the list.
        closedStripHeader.setBackground(blend(hdrBg, new Color(128, 128, 128), 0.06f));
        closedStripHeader.setBorder(new EmptyBorder(2, 6, 2, 4));
        closedStripHeader.setPreferredSize(new Dimension(0, 22));

        JLabel headerLabel = new JLabel();
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 11f));
        updateClosedStripHeaderLabel(headerLabel);
        closedStripHeader.add(headerLabel, BorderLayout.CENTER);

        JButton clearBtn = new JButton("\u00D7"); // ×
        clearBtn.setFocusable(false);
        clearBtn.setMargin(new Insets(0, 4, 0, 4));
        clearBtn.setFont(clearBtn.getFont().deriveFont(11f));
        clearBtn.setToolTipText("Clear recently-closed history");
        clearBtn.addActionListener(e -> {
            PluginPrefs.clearClosedHistory();
            rebuildClosedStrip();
        });
        closedStripHeader.add(clearBtn, BorderLayout.EAST);

        // Toggle expand/collapse on header click
        closedStripHeader.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                // Ignore clicks on the clear button itself
                if (e.getSource() == clearBtn) return;
                toggleClosedStrip();
            }
        });
        // Also let label clicks toggle
        headerLabel.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { toggleClosedStrip(); }
        });

        // ── Body panel ────────────────────────────────────────────────────
        closedStripBody.setLayout(new BoxLayout(closedStripBody, BoxLayout.Y_AXIS));
        closedStripBody.setOpaque(false);

        // ── Assemble ──────────────────────────────────────────────────────
        closedStripWrapper.add(closedStripHeader, BorderLayout.NORTH);
        closedStripWrapper.add(closedStripBody,   BorderLayout.CENTER);

        // Restore persisted open/closed state
        boolean open = PluginPrefs.isClosedStripOpen();
        closedStripBody.setVisible(open);

        // Auto-collapse timer — fires 5 s after the last file was closed
        closedAutoCollapseTimer = new javax.swing.Timer(5000, e -> {
            if (PluginPrefs.isClosedStripOpen()) toggleClosedStrip();
        });
        closedAutoCollapseTimer.setRepeats(false);

        rebuildClosedStrip();
    }

    private void toggleClosedStrip() {
        boolean nowOpen = !closedStripBody.isVisible();
        closedStripBody.setVisible(nowOpen);
        PluginPrefs.setClosedStripOpen(nowOpen);
        // Update arrow in header
        JLabel lbl = (JLabel) closedStripHeader.getComponent(0);
        updateClosedStripHeaderLabel(lbl);
        closedStripWrapper.revalidate();
        closedStripWrapper.repaint();
    }

    private void updateClosedStripHeaderLabel(JLabel lbl) {
        java.util.List<PluginPrefs.ClosedEntry> history = PluginPrefs.getClosedHistory();
        boolean open = closedStripBody.isVisible();
        String arrow = open ? "\u25BC " : "\u25B6 "; // ▼ or ▶
        int count = history.size();
        lbl.setText(arrow + "\uD83D\uDD52 Recently Closed"   // 🕒
                + (count > 0 ? "  (" + count + ")" : ""));
    }

    /**
     * Rebuilds the body of the recently-closed strip from persisted history.
     * Called after every close event and after settings changes.
     */
    private void rebuildClosedStrip() {
        closedStripBody.removeAll();

        java.util.List<PluginPrefs.ClosedEntry> history = PluginPrefs.getClosedHistory();

        for (PluginPrefs.ClosedEntry entry : history) {
            JPanel row = buildClosedEntryRow(entry);
            closedStripBody.add(row);
        }

        // Keep body height tight — exactly as many rows as there are entries
        int bodyH = history.size() * CLOSED_ROW_H;
        closedStripBody.setPreferredSize(new Dimension(0, bodyH));

        // Update the count in the header label
        if (closedStripHeader.getComponentCount() > 0
                && closedStripHeader.getComponent(0) instanceof JLabel) {
            updateClosedStripHeaderLabel((JLabel) closedStripHeader.getComponent(0));
        }

        closedStripWrapper.revalidate();
        closedStripWrapper.repaint();
    }

    private JPanel buildClosedEntryRow(PluginPrefs.ClosedEntry entry) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, CLOSED_ROW_H));
        row.setPreferredSize(new Dimension(0, CLOSED_ROW_H));
        row.setBorder(new EmptyBorder(0, 4, 0, 4));

        // ↩ reopen button on the left (matches the × close button position)
        JButton reopenBtn = new JButton("\u21A9"); // ↩
        reopenBtn.setFocusable(false);
        reopenBtn.setMargin(new Insets(0, 3, 0, 3));
        reopenBtn.setFont(reopenBtn.getFont().deriveFont(11f));
        reopenBtn.setToolTipText("Reopen " + entry.name);
        reopenBtn.addActionListener(e -> reopenClosedEntry(entry));
        row.add(reopenBtn, BorderLayout.WEST);

        // File name label
        JLabel nameLabel = new JLabel(entry.name);
        nameLabel.setBorder(new EmptyBorder(0, 6, 0, 0));
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 11f));
        if (entry.hasPath()) nameLabel.setToolTipText(entry.path);
        // Clicking the label also reopens
        nameLabel.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) reopenClosedEntry(entry);
            }
        });
        row.add(nameLabel, BorderLayout.CENTER);

        return row;
    }

    /**
     * Reopens a previously-closed file using the NetBeans DataObject API.
     *
     * <p>Strategy (in order):
     * <ol>
     *   <li>Resolve path → {@link FileObject} via {@link FileUtil#toFileObject}.
     *       {@link FileUtil#normalizeFile} is called first so mixed-separator or
     *       relative paths resolve correctly on Windows.</li>
     *   <li>Find the {@link DataObject} for the file.</li>
     *   <li>Open via {@link OpenCookie} — the most reliable path for all IFS
     *       file types (entity, projection, plsql, …).</li>
     *   <li>Fall back to {@code DataObject.getNodeDelegate().getPreferredAction()}
     *       if no {@link OpenCookie} is registered.</li>
     * </ol>
     *
     * <p>The history entry is removed by the {@code PROP_OPENED} listener once
     * the file actually appears in the open set — NOT here.  This avoids a race
     * where the entry is deleted before the async open completes, making it look
     * like the reopen never happened.
     */
    private void reopenClosedEntry(PluginPrefs.ClosedEntry entry) {
        if (!entry.hasPath()) return;

        // Remove from history immediately — don't wait for PROP_OPENED.
        // If the open fails the error dialog tells the user; the entry is gone
        // either way since there's nothing actionable to do with a broken entry.
        PluginPrefs.removeClosedEntry(entry.path);
        rebuildClosedStrip();

        try {
            // Defensively strip any trailing versioning annotation that may have
            // been stored in earlier sessions before the suffix-strip fix.
            // e.g. "E:/…/CSiffScaleIntLog.cre (read-only)" → "E:/…/CSiffScaleIntLog.cre"
            String cleanPath = entry.path
                    .replaceAll("\\s+[\\(\\[][^\\)\\]]*[\\)\\]]\\s*$", "").trim();

            java.io.File file = FileUtil.normalizeFile(
                    new java.io.File(cleanPath.replace('/', java.io.File.separatorChar)));

            System.err.println("[OpenFilesNavigator] Reopening: " + file.getAbsolutePath()
                    + " | exists=" + file.exists());

            if (!file.exists()) {
                showReopenError(entry.name);
                return;
            }

            // toFileObject() can return null if the file's parent directory
            // hasn't been scanned by the NetBeans filesystem yet.
            // Refreshing the parent forces the VFS to pick it up.
            FileObject parentFo = FileUtil.toFileObject(file.getParentFile());
            if (parentFo != null) parentFo.refresh();

            FileObject fo = FileUtil.toFileObject(file);
            if (fo == null) {
                // Last resort: refresh the entire filesystem root and retry.
                FileUtil.getConfigRoot().getFileSystem().refresh(false);
                fo = FileUtil.toFileObject(file);
            }
            if (fo == null) {
                System.err.println("[OpenFilesNavigator] toFileObject returned null for "
                        + file.getAbsolutePath() + " (file exists: " + file.exists() + ")");
                showReopenError(entry.name);
                return;
            }

            DataObject dob = DataObject.find(fo);

            OpenCookie oc = dob.getLookup().lookup(OpenCookie.class);
            if (oc != null) {
                oc.open();
            } else {
                javax.swing.Action action = dob.getNodeDelegate().getPreferredAction();
                if (action instanceof ContextAwareAction) {
                    action = ((ContextAwareAction) action)
                            .createContextAwareInstance(dob.getLookup());
                }
                if (action != null && action.isEnabled()) {
                    action.actionPerformed(
                            new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ""));
                } else {
                    showReopenError(entry.name);
                }
            }
        } catch (DataObjectNotFoundException ex) {
            System.err.println("[OpenFilesNavigator] DataObject not found for "
                    + entry.name + ": " + ex);
            showReopenError(entry.name);
        } catch (Exception ex) {
            System.err.println("[OpenFilesNavigator] Reopen failed for "
                    + entry.name + ": " + ex);
            showReopenError(entry.name);
        }
    }

    private void showReopenError(String fileName) {
        JOptionPane.showMessageDialog(this,
                "Could not reopen \u201C" + fileName + "\u201D.\n"
                + "The file may have been moved or deleted.",
                "Reopen Failed", JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Auto-expands the strip for 5 seconds when a new file is closed,
     * then collapses it again.  Call after every {@code pushClosedEntry}.
     */
    private void autoExpandClosedStrip() {
        if (!closedStripBody.isVisible()) {
            toggleClosedStrip();
        }
        // Restart the auto-collapse timer each time a file is closed.
        closedAutoCollapseTimer.restart();
    }

    private static Color blend(Color a, Color b, float t) {
        int r  = Math.round(a.getRed()   + t * (b.getRed()   - a.getRed()));
        int g  = Math.round(a.getGreen() + t * (b.getGreen() - a.getGreen()));
        int bl = Math.round(a.getBlue()  + t * (b.getBlue()  - a.getBlue()));
        return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, bl)));
    }

    private void openSettingsDialog() {
        JDialog dlg = new JDialog(
                (Frame) SwingUtilities.getWindowAncestor(this),
                "Open Files Navigator \u2014 Settings", true);
        dlg.setLayout(new BorderLayout(8, 8));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(12, 16, 8, 16));

        // ── Note display ──────────────────────────────────────────────────
        addSectionLabel(content, "Note display:");
        ButtonGroup noteBg   = new ButtonGroup();
        JRadioButton tooltipRb = new JRadioButton("Tooltip only (hover to see note)");
        JRadioButton inlineRb  = new JRadioButton("Inline indicator (\u270E suffix on row)");
        noteBg.add(tooltipRb); noteBg.add(inlineRb);
        boolean noteInline = PluginPrefs.NOTE_INLINE.equals(PluginPrefs.getNoteDisplay());
        tooltipRb.setSelected(!noteInline); inlineRb.setSelected(noteInline);
        tooltipRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        inlineRb .setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(tooltipRb); content.add(inlineRb);
        content.add(Box.createVerticalStrut(12));

        // ── Build file display ─────────────────────────────────────────────
        addSectionLabel(content, "Generated (build) files in tree view:");
        ButtonGroup buildBg   = new ButtonGroup();
        JRadioButton buildInlineRb  = new JRadioButton(
                "Show inline in their folder (greyed/italic)");
        JRadioButton buildGroupedRb = new JRadioButton(
                "Group under a \u201CGenerated\u201D folder at the bottom");
        buildBg.add(buildInlineRb); buildBg.add(buildGroupedRb);
        boolean buildGrouped = PluginPrefs.BUILD_GROUPED.equals(PluginPrefs.getBuildGrouping());
        buildInlineRb .setSelected(!buildGrouped);
        buildGroupedRb.setSelected(buildGrouped);
        buildInlineRb .setAlignmentX(Component.LEFT_ALIGNMENT);
        buildGroupedRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(buildInlineRb); content.add(buildGroupedRb);
        content.add(Box.createVerticalStrut(12));

        // ── Tree grouping ──────────────────────────────────────────────────
        addSectionLabel(content, "Tree view \u2014 group ungrouped files by:");
        ButtonGroup treeGrpBg  = new ButtonGroup();
        JRadioButton treeFolderRb    = new JRadioButton(
                "Folder path (parent two folders, e.g. \u201Cdatabase / shpmnt\u201D)");
        JRadioButton treeComponentRb = new JRadioButton(
                "IFS component (segment after \u201Cworkspace/\u201D, e.g. \u201Cshpmnt\u201D)");
        treeGrpBg.add(treeFolderRb); treeGrpBg.add(treeComponentRb);
        boolean byComponent = PluginPrefs.TREE_GROUP_COMPONENT.equals(PluginPrefs.getTreeGrouping());
        treeFolderRb   .setSelected(!byComponent);
        treeComponentRb.setSelected(byComponent);
        treeFolderRb   .setAlignmentX(Component.LEFT_ALIGNMENT);
        treeComponentRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(treeFolderRb); content.add(treeComponentRb);
        content.add(Box.createVerticalStrut(12));

        // ── List sort order ────────────────────────────────────────────────
        addSectionLabel(content, "List view \u2014 sort files by:");
        ButtonGroup sortBg      = new ButtonGroup();
        JRadioButton sortAlphaRb     = new JRadioButton("Alphabetical order");
        JRadioButton sortOpenOrderRb = new JRadioButton(
                "Open order (first opened at top, most recently opened at bottom)");
        sortBg.add(sortAlphaRb); sortBg.add(sortOpenOrderRb);
        boolean openOrderSort = PluginPrefs.LIST_SORT_OPEN_ORDER.equals(PluginPrefs.getListSort());
        sortAlphaRb    .setSelected(!openOrderSort);
        sortOpenOrderRb.setSelected(openOrderSort);
        sortAlphaRb    .setAlignmentX(Component.LEFT_ALIGNMENT);
        sortOpenOrderRb.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(sortAlphaRb); content.add(sortOpenOrderRb);
        content.add(Box.createVerticalStrut(12));

        // ── Recently-closed history ────────────────────────────────────────
        addSectionLabel(content, "Recently-closed history:");
        JPanel closedLimitRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        closedLimitRow.setOpaque(false);
        closedLimitRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        SpinnerNumberModel spinModel = new SpinnerNumberModel(
                PluginPrefs.getClosedHistoryLimit(), // current
                1,                                   // min
                PluginPrefs.CLOSED_LIMIT_MAX,        // max
                1);                                  // step
        JSpinner limitSpinner = new JSpinner(spinModel);
        limitSpinner.setPreferredSize(new Dimension(55, limitSpinner.getPreferredSize().height));
        JLabel spinLabel = new JLabel(" files  (1\u2013" + PluginPrefs.CLOSED_LIMIT_MAX + ")");
        closedLimitRow.add(new JLabel("Keep last "));
        closedLimitRow.add(limitSpinner);
        closedLimitRow.add(spinLabel);
        content.add(closedLimitRow);
        content.add(Box.createVerticalStrut(12));
        addSectionLabel(content, "Manage groups:");
        DefaultListModel<String> gListModel = new DefaultListModel<>();
        PluginPrefs.getGroupOrder().forEach(gListModel::addElement);
        JList<String> gList = new JList<>(gListModel);
        gList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gList.setFixedCellHeight(22);
        JScrollPane gScroll = new JScrollPane(gList);
        gScroll.setPreferredSize(new Dimension(260, 90));
        gScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(gScroll);
        content.add(Box.createVerticalStrut(4));

        JPanel gBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        gBtns.setOpaque(false);
        gBtns.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton addGBtn = new JButton("+ New");
        JButton renGBtn = new JButton("Rename");
        JButton delGBtn = new JButton("Delete");
        gBtns.add(addGBtn); gBtns.add(renGBtn); gBtns.add(delGBtn);
        content.add(gBtns);

        addGBtn.addActionListener(ev -> {
            String name = JOptionPane.showInputDialog(dlg, "Group name:", "New Group",
                    JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.trim().isEmpty()) {
                name = name.trim();
                PluginPrefs.createGroup(name);
                gListModel.addElement(name);
            }
        });
        renGBtn.addActionListener(ev -> {
            String sel = gList.getSelectedValue();
            if (sel == null) return;
            String newName = JOptionPane.showInputDialog(dlg, "New name:", sel);
            if (newName != null && !newName.trim().isEmpty()
                    && !newName.trim().equals(sel)) {
                PluginPrefs.renameGroup(sel, newName.trim());
                gListModel.set(gListModel.indexOf(sel), newName.trim());
                refreshList();
            }
        });
        delGBtn.addActionListener(ev -> {
            String sel = gList.getSelectedValue();
            if (sel == null) return;
            int c = JOptionPane.showConfirmDialog(dlg,
                    "Delete group \u201C" + sel + "\u201D?\nFiles become ungrouped.",
                    "Delete Group", JOptionPane.YES_NO_OPTION);
            if (c == JOptionPane.YES_OPTION) {
                PluginPrefs.deleteGroup(sel);
                gListModel.removeElement(sel);
                refreshList();
            }
        });

        dlg.add(content, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok     = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        btnRow.add(cancel); btnRow.add(ok);
        dlg.add(btnRow, BorderLayout.SOUTH);

        ok.addActionListener(ev -> {
            PluginPrefs.setNoteDisplay(inlineRb.isSelected()
                    ? PluginPrefs.NOTE_INLINE : PluginPrefs.NOTE_TOOLTIP);
            PluginPrefs.setBuildGrouping(buildGroupedRb.isSelected()
                    ? PluginPrefs.BUILD_GROUPED : PluginPrefs.BUILD_INLINE);
            PluginPrefs.setTreeGrouping(treeComponentRb.isSelected()
                    ? PluginPrefs.TREE_GROUP_COMPONENT : PluginPrefs.TREE_GROUP_FOLDER);
            PluginPrefs.setListSort(sortOpenOrderRb.isSelected()
                    ? PluginPrefs.LIST_SORT_OPEN_ORDER : PluginPrefs.LIST_SORT_ALPHA);
            PluginPrefs.setClosedHistoryLimit((Integer) limitSpinner.getValue());
            refreshList();
            rebuildClosedStrip();
            dlg.dispose();
        });
        cancel.addActionListener(ev -> dlg.dispose());

        dlg.getRootPane().setDefaultButton(ok);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private static void addSectionLabel(JPanel panel, String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(lbl);
        panel.add(Box.createVerticalStrut(4));
    }

    // =========================================================================
    // Collapse / expand helpers (list view)
    // =========================================================================

    private void toggleCollapse(String sectionName) {
        PluginPrefs.setGroupCollapsed(sectionName, !PluginPrefs.isGroupCollapsed(sectionName));
        // Post rebuild via invokeLater so the current mouse/key event finishes
        // before the list model is mutated — prevents index-out-of-bounds and
        // stale-selection bugs when clicking a header row.
        SwingUtilities.invokeLater(this::applyFilter);
    }

    private void selectGroupChildren(int headerIdx) {
        java.util.List<Integer> indices = new ArrayList<>();
        for (int i = headerIdx + 1; i < listModel.getSize(); i++) {
            Object item = listModel.getElementAt(i);
            if (item instanceof OpenFilesCellRenderer.GroupHeader) break;
            indices.add(i);
        }
        if (indices.isEmpty()) { fileList.setSelectedIndex(headerIdx); return; }
        int[] arr = indices.stream().mapToInt(Integer::intValue).toArray();
        fileList.setSelectedIndices(arr);
        fileList.ensureIndexIsVisible(arr[0]);
    }

    // =========================================================================
    // Context menus
    // =========================================================================

    private void showListContextMenu(int x, int y) {
        int idx = fileList.locationToIndex(new Point(x, y));
        if (idx < 0) return;
        Object item = listModel.getElementAt(idx);

        if (item instanceof OpenFilesCellRenderer.GroupHeader) {
            showGroupHeaderContextMenu(
                    ((OpenFilesCellRenderer.GroupHeader) item).name, x, y);
        } else {
            java.util.List<TopComponent> targets = getSelectedListTCs();
            if (!targets.isEmpty()) showFileContextMenu(fileList, targets, x, y);
        }
    }

    private void showGroupHeaderContextMenu(String groupName, int x, int y) {
        java.util.List<TopComponent> sectionTCs = getTCsInSection(groupName);
        JPopupMenu menu = new JPopupMenu();

        boolean isPseudo = groupName.equals(PluginPrefs.SECTION_PINNED)
                        || groupName.equals(PluginPrefs.SECTION_UNGROUPED)
                        || groupName.equals(PluginPrefs.SECTION_GENERATED);

        if (!isPseudo) {
            JMenuItem renameItem = new JMenuItem("Rename group\u2026");
            renameItem.addActionListener(e -> {
                String newName = JOptionPane.showInputDialog(this, "New name:", groupName);
                if (newName != null && !newName.trim().isEmpty()
                        && !newName.trim().equals(groupName)) {
                    PluginPrefs.renameGroup(groupName, newName.trim());
                    refreshList();
                }
            });
            menu.add(renameItem);

            JMenuItem deleteItem = new JMenuItem("Delete group");
            deleteItem.addActionListener(e -> {
                int c = JOptionPane.showConfirmDialog(this,
                        "Delete group \u201C" + groupName + "\u201D?\nFiles become ungrouped.",
                        "Delete Group", JOptionPane.YES_NO_OPTION);
                if (c == JOptionPane.YES_OPTION) {
                    PluginPrefs.deleteGroup(groupName);
                    refreshList();
                }
            });
            menu.add(deleteItem);
            menu.addSeparator();
        }

        boolean collapsed = PluginPrefs.isGroupCollapsed(groupName);
        menu.add(menuItem(collapsed ? "Expand" : "Collapse",
                e -> toggleCollapse(groupName)));
        menu.add(menuItem("Collapse all sections",
                e -> { PluginPrefs.setAllGroupsCollapsed(true);  applyFilter(); }));
        menu.add(menuItem("Expand all sections",
                e -> { PluginPrefs.setAllGroupsCollapsed(false); applyFilter(); }));

        if (!sectionTCs.isEmpty()) {
            menu.addSeparator();
            addFileActionsToMenu(menu, sectionTCs, groupName);
        }

        menu.show(fileList, x, y);
    }

    private void showTreeContextMenu(int x, int y) {
        java.util.List<TopComponent> targets = getSelectedTreeTCs();
        if (!targets.isEmpty()) showFileContextMenu(fileTree, targets, x, y);
    }

    private void showFileContextMenu(JComponent source,
            java.util.List<TopComponent> targets, int x, int y) {
        boolean single = targets.size() == 1;
        String label = single
                ? resolveDisplayName(targets.get(0))
                : targets.size() + " files selected";

        JPopupMenu menu = new JPopupMenu();
        JMenuItem titleItem = new JMenuItem(label);
        titleItem.setEnabled(false);
        titleItem.setFont(titleItem.getFont().deriveFont(Font.BOLD));
        menu.add(titleItem);
        menu.addSeparator();
        addFileActionsToMenu(menu, targets, null);
        menu.show(source, x, y);
    }

    /**
     * Appends file-level actions to {@code menu}.
     *
     * <p>Build files get only: Close + Execute PL/SQL Command.
     * Normal files get the full set: Close, IFS generate actions, Pin, Note, Group.
     */
    private void addFileActionsToMenu(JPopupMenu menu,
            java.util.List<TopComponent> targets, String sectionNameHint) {

        boolean single = targets.size() == 1;

        // Determine if ALL selected targets are build files
        boolean allBuild = targets.stream().allMatch(PluginPrefs::isBuildFile);

        // ── Close ──────────────────────────────────────────────────────────
        JMenuItem closeItem = new JMenuItem(
                sectionNameHint != null ? "Close all in section"
                : single ? "Close" : "Close selected");
        closeItem.addActionListener(e -> targets.forEach(TopComponent::close));
        menu.add(closeItem);

        // ── Execute PL/SQL Command (build files only) ──────────────────────
        // For a single build file: use PlsqlMultipleExecuteAction.getPopupPresenter()
        // which gives the connection-picker submenu natively.
        // For multiple build files: build the same connection submenu from the
        // first file, then execute each file sequentially when a connection is chosen.
        if (allBuild) {
            menu.addSeparator();
            JMenuItem execItem = single
                    ? buildExecutePlsqlMenuItem(targets.get(0))
                    : buildExecutePlsqlMenuItemMulti(targets);
            if (execItem != null) menu.add(execItem);
            // Build files get no further actions (pin/group/note not relevant)
            return;
        }

        // ── IFS generate actions (non-build only) ──────────────────────────
        if (!allBuild) {
            // Filter to non-build targets only for generate actions
            java.util.List<TopComponent> nonBuildTargets = new ArrayList<>();
            for (TopComponent tc : targets) {
                if (!PluginPrefs.isBuildFile(tc)) nonBuildTargets.add(tc);
            }

            menu.addSeparator();
            JMenuItem genCode = new JMenuItem("Generate Code");
            genCode.addActionListener(e ->
                    runActionOnTargets(CAT_IFS, ACT_GENERATE_CODE, nonBuildTargets));
            menu.add(genCode);

            JMenuItem genDeploy = new JMenuItem("Generate Code and Deploy");
            genDeploy.addActionListener(e ->
                    runActionOnTargets(CAT_PLSQL_IMPL, ACT_GENERATE_DEPLOY, nonBuildTargets));
            menu.add(genDeploy);

            JMenuItem genDeployDep = new JMenuItem(
                    "Generate Code and Deploy with Dependents\u2026");
            boolean singleEntityOrProj = nonBuildTargets.size() == 1
                    && (resolveExtension(nonBuildTargets.get(0)).equals("entity")
                     || resolveExtension(nonBuildTargets.get(0)).equals("projection"));
            genDeployDep.setEnabled(singleEntityOrProj);
            genDeployDep.addActionListener(e ->
                    runActionOnEDT(CAT_PLSQL_TOOLBAR, ACT_GENERATE_DEPLOY_DEPENDENTS,
                            nonBuildTargets));
            menu.add(genDeployDep);
        }

        menu.addSeparator();

        // ── Determine section context ──────────────────────────────────────
        // Suppresses irrelevant actions: no "Pin all" when already in Pinned,
        // no "Add to group X" when files are already in group X.
        final boolean inPinnedSection;
        final String  inGroupSection; // null if not in a named user group

        if (sectionNameHint != null) {
            inPinnedSection = PluginPrefs.SECTION_PINNED.equals(sectionNameHint);
            boolean isPseudo = PluginPrefs.SECTION_PINNED.equals(sectionNameHint)
                    || PluginPrefs.SECTION_UNGROUPED.equals(sectionNameHint)
                    || PluginPrefs.SECTION_GENERATED.equals(sectionNameHint);
            inGroupSection = isPseudo ? null : sectionNameHint;
        } else if (single) {
            String k = PluginPrefs.keyFor(targets.get(0));
            inPinnedSection = PluginPrefs.isPinned(k);
            inGroupSection  = PluginPrefs.groupOf(k);
        } else {
            boolean allPinned = targets.stream()
                    .allMatch(tc -> PluginPrefs.isPinned(PluginPrefs.keyFor(tc)));
            inPinnedSection = allPinned;
            String firstGroup = PluginPrefs.groupOf(PluginPrefs.keyFor(targets.get(0)));
            boolean allSameGroup = firstGroup != null && targets.stream().allMatch(
                    tc -> firstGroup.equals(PluginPrefs.groupOf(PluginPrefs.keyFor(tc))));
            inGroupSection = allSameGroup ? firstGroup : null;
        }

        // ── Pin ────────────────────────────────────────────────────────────
        if (inPinnedSection) {
            // Already in Pinned section — only offer Unpin.
            if (single) {
                String tcKey = PluginPrefs.keyFor(targets.get(0));
                menu.add(menuItem("Unpin", e -> {
                    PluginPrefs.removePin(tcKey); refreshList();
                }));
            } else {
                menu.add(menuItem("Unpin all", e -> {
                    targets.forEach(tc -> PluginPrefs.removePin(PluginPrefs.keyFor(tc)));
                    refreshList();
                }));
            }
        } else {
            if (single) {
                String tcKey   = PluginPrefs.keyFor(targets.get(0));
                boolean pinned = PluginPrefs.isPinned(tcKey);
                menu.add(menuItem(pinned ? "Unpin" : "Pin to top", e -> {
                    if (pinned) {
                        PluginPrefs.removePin(tcKey);
                        refreshList();
                    } else {
                        String existingGroup = PluginPrefs.groupOf(tcKey);
                        if (existingGroup != null) {
                            String fileName = resolveDisplayName(targets.get(0));
                            int res = JOptionPane.showConfirmDialog(this,
                                    "<html><b>" + escapeHtml(fileName) + "</b> is in group \u201C"
                                    + escapeHtml(existingGroup) + "\u201D.<br>"
                                    + "Pin it to top and remove it from that group?</html>",
                                    "Pin File", JOptionPane.YES_NO_OPTION,
                                    JOptionPane.QUESTION_MESSAGE);
                            if (res != JOptionPane.YES_OPTION) return;
                            PluginPrefs.removeFromGroup(existingGroup, tcKey);
                        }
                        PluginPrefs.addPin(tcKey);
                        refreshList();
                    }
                }));
            } else {
                menu.add(menuItem("Pin all", e -> {
                    java.util.List<String> conflicts = new ArrayList<>();
                    for (TopComponent tc : targets) {
                        String k = PluginPrefs.keyFor(tc);
                        String g = PluginPrefs.groupOf(k);
                        if (g != null) conflicts.add(resolveDisplayName(tc) + " \u2192 \u201C" + g + "\u201D");
                    }
                    if (!conflicts.isEmpty()) {
                        String list = String.join("<br>", conflicts.stream()
                                .map(OpenFilesTopComponent::escapeHtml)
                                .toArray(String[]::new));
                        int res = JOptionPane.showConfirmDialog(this,
                                "<html>The following files are in groups and will be removed from them:<br><br>"
                                + list + "<br><br>Pin all and remove from groups?</html>",
                                "Pin Files", JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE);
                        if (res != JOptionPane.YES_OPTION) return;
                        for (TopComponent tc : targets) {
                            String k = PluginPrefs.keyFor(tc);
                            String g = PluginPrefs.groupOf(k);
                            if (g != null) PluginPrefs.removeFromGroup(g, k);
                        }
                    }
                    targets.forEach(tc -> PluginPrefs.addPin(PluginPrefs.keyFor(tc)));
                    refreshList();
                }));
            }
        }

        // ── Note (single non-build only) ───────────────────────────────────
        if (single && !PluginPrefs.isBuildFile(targets.get(0))) {
            TopComponent tc = targets.get(0);
            String tcKey    = PluginPrefs.keyFor(tc);
            String existing = PluginPrefs.getNote(tcKey);
            boolean hasNote = existing != null && !existing.trim().isEmpty();

            menu.add(menuItem(hasNote ? "Edit note\u2026" : "Add note\u2026", e -> {
                JTextArea area = new JTextArea(existing != null ? existing : "", 4, 30);
                area.setLineWrap(true); area.setWrapStyleWord(true);
                JScrollPane sp = new JScrollPane(area);
                sp.setPreferredSize(new Dimension(320, 90));
                JPanel p = new JPanel(new BorderLayout(4, 4));
                p.add(new JLabel("Note for " + resolveDisplayName(tc) + ":"),
                        BorderLayout.NORTH);
                p.add(sp, BorderLayout.CENTER);
                int res = JOptionPane.showConfirmDialog(this, p,
                        "File Note", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE);
                if (res == JOptionPane.OK_OPTION) {
                    PluginPrefs.setNote(tcKey, area.getText().trim());
                    refreshList();
                }
            }));

            if (hasNote) {
                menu.add(menuItem("Clear note", e -> {
                    PluginPrefs.removeNote(tcKey);
                    refreshList();
                }));
            }
        }

        // ── Color tag ──────────────────────────────────────────────────────
        // Available for all files (build and non-build), single and multi.
        {
            String currentTagName = single
                    ? PluginPrefs.getTag(PluginPrefs.keyFor(targets.get(0))).name()
                    : null; // mixed tags possible in multi-select — don't pre-check
            JMenu tagMenu = new JMenu("Tag");

            for (PluginPrefs.TagColor tag : PluginPrefs.TagColor.values()) {
                String label;
                switch (tag) {
                    case NONE:   label = "\u2205  None";   break; // ∅
                    case RED:    label = "\u25CF  Red";    break; // ●
                    case ORANGE: label = "\u25CF  Orange"; break;
                    case GREEN:  label = "\u25CF  Green";  break;
                    case BLUE:   label = "\u25CF  Blue";   break;
                    case PURPLE: label = "\u25CF  Purple"; break;
                    default:     label = tag.name();
                }
                JMenuItem tagItem = new JMenuItem(label);
                // Color the ● to match the tag (plain text for NONE)
                if (tag.color != null) tagItem.setForeground(tag.color);
                // Checkmark on the currently active tag (single-select only)
                if (tag.name().equals(currentTagName)) {
                    tagItem.setFont(tagItem.getFont().deriveFont(Font.BOLD));
                    tagItem.setText(label + "  \u2713"); // ✓
                }
                final PluginPrefs.TagColor chosen = tag;
                tagItem.addActionListener(e -> {
                    targets.forEach(tc ->
                            PluginPrefs.setTag(PluginPrefs.keyFor(tc), chosen));
                    refreshList();
                });
                tagMenu.add(tagItem);
            }
            menu.add(tagMenu);
        }

        // ── Group assignment ────────────────────────────────────────────────
        menu.addSeparator();
        Map<String, java.util.List<String>> groups = PluginPrefs.getGroups();

        // "Add to group" submenu — exclude the group the files are already in.
        java.util.List<String> eligibleGroups = new ArrayList<>();
        for (String gName : groups.keySet()) {
            if (!gName.equals(inGroupSection)) eligibleGroups.add(gName);
        }
        if (!eligibleGroups.isEmpty()) {
            JMenu addToGroupMenu = new JMenu(
                    single ? "Add to group" : "Add all to group");
            for (String gName : eligibleGroups) {
                addToGroupMenu.add(menuItem(gName, e -> {
                    // Check if any target is pinned — pinned + grouped = duplicate display
                    java.util.List<TopComponent> pinnedTargets = new ArrayList<>();
                    for (TopComponent tc : targets) {
                        if (PluginPrefs.isPinned(PluginPrefs.keyFor(tc))) pinnedTargets.add(tc);
                    }
                    if (!pinnedTargets.isEmpty()) {
                        String detail = single
                                ? "<html><b>" + escapeHtml(resolveDisplayName(pinnedTargets.get(0)))
                                  + "</b> is pinned.<br>Move it to group \u201C"
                                  + escapeHtml(gName) + "\u201D and unpin it?</html>"
                                : "<html>" + pinnedTargets.size() + " of the selected files are pinned.<br>"
                                  + "Move them to group \u201C" + escapeHtml(gName)
                                  + "\u201D and unpin them?</html>";
                        int res = JOptionPane.showConfirmDialog(this, detail,
                                "Add to Group", JOptionPane.YES_NO_OPTION,
                                JOptionPane.QUESTION_MESSAGE);
                        if (res != JOptionPane.YES_OPTION) return;
                        pinnedTargets.forEach(tc -> PluginPrefs.removePin(PluginPrefs.keyFor(tc)));
                    }
                    targets.forEach(tc ->
                            PluginPrefs.addToGroup(gName, PluginPrefs.keyFor(tc)));
                    refreshList();
                }));
            }
            menu.add(addToGroupMenu);
        }

        // "Remove from group" — only when files are in a named user group.
        if (inGroupSection != null) {
            if (single) {
                menu.add(menuItem(
                        "Remove from group \u201C" + inGroupSection + "\u201D", e -> {
                    PluginPrefs.removeFromGroup(inGroupSection,
                            PluginPrefs.keyFor(targets.get(0)));
                    refreshList();
                }));
            } else {
                menu.add(menuItem(
                        "Remove all from group \u201C" + inGroupSection + "\u201D", e -> {
                    targets.forEach(tc -> PluginPrefs.removeFromGroup(
                            inGroupSection, PluginPrefs.keyFor(tc)));
                    refreshList();
                }));
            }
        }

        menu.add(menuItem("Add to new group\u2026", e -> {
            String name = JOptionPane.showInputDialog(this, "Group name:", "New Group",
                    JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.trim().isEmpty()) {
                final String gName = name.trim();
                // Check if any target is pinned
                java.util.List<TopComponent> pinnedTargets = new ArrayList<>();
                for (TopComponent tc : targets) {
                    if (PluginPrefs.isPinned(PluginPrefs.keyFor(tc))) pinnedTargets.add(tc);
                }
                if (!pinnedTargets.isEmpty()) {
                    String detail = (pinnedTargets.size() == 1)
                            ? "<html><b>" + escapeHtml(resolveDisplayName(pinnedTargets.get(0)))
                              + "</b> is pinned.<br>Move it to group \u201C"
                              + escapeHtml(gName) + "\u201D and unpin it?</html>"
                            : "<html>" + pinnedTargets.size() + " of the selected files are pinned.<br>"
                              + "Move them to group \u201C" + escapeHtml(gName)
                              + "\u201D and unpin them?</html>";
                    int res = JOptionPane.showConfirmDialog(this, detail,
                            "Add to New Group", JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    if (res != JOptionPane.YES_OPTION) return;
                    pinnedTargets.forEach(tc -> PluginPrefs.removePin(PluginPrefs.keyFor(tc)));
                }
                PluginPrefs.createGroup(gName);
                targets.forEach(tc ->
                        PluginPrefs.addToGroup(gName, PluginPrefs.keyFor(tc)));
                refreshList();
            }
        }));
    }

    // ── Execute PL/SQL Command ─────────────────────────────────────────────

    /**
     * Builds a menu item for "Execute PL/SQL Command" by obtaining the
     * {@code PlsqlMultipleExecuteAction} singleton and calling its
     * {@code getPopupPresenter()} which returns a connection-picker submenu.
     *
     * <p>The action's class is loaded via the DataObject's classloader so the
     * module classloader chain is correct.  If anything fails we fall back to
     * sending the {@code Alt+Shift+E} keybinding via Robot.
     */
    private JMenuItem buildExecutePlsqlMenuItem(TopComponent tc) {
        try {
            String tip = tc.getToolTipText();
            if (tip == null || tip.trim().isEmpty()) return fallbackExecItem(tc);
            String path = tip.replace('\\', '/').trim().replaceAll("<[^>]+>", "").trim();
            if (path.isEmpty()) return fallbackExecItem(tc);

            java.io.File file = FileUtil.normalizeFile(
                    new java.io.File(path.replace('/', java.io.File.separatorChar)));
            FileObject fo = FileUtil.toFileObject(file);
            if (fo == null) return fallbackExecItem(tc);

            DataObject dob = DataObject.find(fo);

            // Load the action class via the DataObject's module classloader.
            @SuppressWarnings("unchecked")
            Class<? extends SystemAction> cls =
                    (Class<? extends SystemAction>) Class.forName(
                        "org.netbeans.modules.plsql.execution.PlsqlMultipleExecuteAction",
                        true, dob.getClass().getClassLoader());

            // SystemAction.get() returns the module-managed singleton.
            SystemAction action = SystemAction.get(cls);

            // getPopupPresenter() is declared on NodeAction (public method).
            // It returns a JMenu containing one item per available DB connection.
            // We activate the TC first so the action's global context lookup
            // finds the correct DataObject when the user picks a connection.
            tc.requestActive();
            java.lang.reflect.Method presenter =
                    action.getClass().getMethod("getPopupPresenter");
            Object result = presenter.invoke(action);
            if (result instanceof JMenuItem) return (JMenuItem) result;

            return fallbackExecItem(tc);

        } catch (DataObjectNotFoundException ex) {
            System.err.println("[OpenFilesNavigator] Execute PL/SQL: DataObject not found: " + ex);
            return fallbackExecItem(tc);
        } catch (Exception ex) {
            System.err.println("[OpenFilesNavigator] Execute PL/SQL: " + ex);
            return fallbackExecItem(tc);
        }
    }

    /**
     * Fallback when the action cannot be loaded: activate the TC and send
     * Alt+Shift+E (the keybinding shown in the IFS editor right-click menu).
     */
    private JMenuItem fallbackExecItem(TopComponent tc) {
        JMenuItem item = new JMenuItem("Execute PL/SQL Command");
        item.addActionListener(e -> {
            tc.requestActive();
            javax.swing.Timer t = new javax.swing.Timer(350, ev -> {
                try {
                    Robot robot = new Robot();
                    robot.keyPress(KeyEvent.VK_ALT);
                    robot.keyPress(KeyEvent.VK_SHIFT);
                    robot.keyPress(KeyEvent.VK_E);
                    robot.keyRelease(KeyEvent.VK_E);
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                    robot.keyRelease(KeyEvent.VK_ALT);
                } catch (AWTException ex) {
                    System.err.println("[OpenFilesNavigator] Execute PL/SQL Robot fallback: " + ex);
                }
            });
            t.setRepeats(false);
            t.start();
        });
        return item;
    }

    /**
     * Builds an "Execute PL/SQL Command" submenu for multiple build files.
     *
     * <p>Uses the first file to obtain the connection list from
     * {@code PlsqlMultipleExecuteAction.getPopupPresenter()}, then replaces
     * each connection item's action with one that executes ALL selected files
     * sequentially — activate TC → wait 350ms → send Alt+Shift+E → wait 800ms
     * → next file.
     */
    private JMenuItem buildExecutePlsqlMenuItemMulti(
            java.util.List<TopComponent> targets) {

        // Get the native connection submenu from the first file.
        // The connection list is project-wide so any file gives the same result.
        JMenuItem nativePresenter = buildExecutePlsqlMenuItem(targets.get(0));

        // If the native presenter is a JMenu we can clone its connection labels
        // and wire them to our own multi-file action.
        if (nativePresenter instanceof JMenu) {
            JMenu nativeMenu = (JMenu) nativePresenter;
            JMenu ourMenu = new JMenu("Execute PL/SQL Command (" + targets.size() + " files)");

            for (int i = 0; i < nativeMenu.getItemCount(); i++) {
                JMenuItem src = nativeMenu.getItem(i);
                if (src == null) { ourMenu.addSeparator(); continue; }

                // Copy the label, then replace the action with our sequential runner.
                String label = src.getText();
                JMenuItem copy = new JMenuItem(label);
                copy.addActionListener(e -> executeSequentially(targets));
                ourMenu.add(copy);
            }
            return ourMenu;
        }

        // Fallback: single JMenuItem (no connection submenu available) —
        // just run all files sequentially using Alt+Shift+E.
        JMenuItem item = new JMenuItem(
                "Execute PL/SQL Command (" + targets.size() + " files)");
        item.addActionListener(e -> executeSequentially(targets));
        return item;
    }

    /**
     * Executes each build file in {@code targets} sequentially by activating
     * the TC and sending Alt+Shift+E, with an 800ms gap between files so the
     * IFS execution engine can finish one before the next starts.
     */
    private void executeSequentially(java.util.List<TopComponent> targets) {
        RP.post(() -> {
            for (int i = 0; i < targets.size(); i++) {
                final TopComponent tc = targets.get(i);
                if (!tc.isOpened()) continue;
                SwingUtilities.invokeLater(tc::requestActive);
                try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                SwingUtilities.invokeLater(() -> {
                    try {
                        Robot robot = new Robot();
                        robot.keyPress(KeyEvent.VK_ALT);
                        robot.keyPress(KeyEvent.VK_SHIFT);
                        robot.keyPress(KeyEvent.VK_E);
                        robot.keyRelease(KeyEvent.VK_E);
                        robot.keyRelease(KeyEvent.VK_SHIFT);
                        robot.keyRelease(KeyEvent.VK_ALT);
                    } catch (AWTException ex) {
                        System.err.println("[OpenFilesNavigator] Execute sequential: " + ex);
                    }
                });
                if (i < targets.size() - 1) {
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                }
            }
        });
    }

    /** Delegates to {@link OpenFilesCellRenderer#escapeHtml} for use in HTML dialog messages. */
    private static String escapeHtml(String s) {
        return OpenFilesCellRenderer.escapeHtml(s);
    }

    private static JMenuItem menuItem(String label, ActionListener al) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(al);
        return item;
    }

    // =========================================================================
    // Selection helpers
    // =========================================================================

    private java.util.List<TopComponent> getSelectedListTCs() {
        java.util.List<TopComponent> result = new ArrayList<>();
        for (int idx : fileList.getSelectedIndices()) {
            Object item = listModel.getElementAt(idx);
            if (item instanceof TopComponent) result.add((TopComponent) item);
        }
        return result;
    }

    private java.util.List<TopComponent> getTCsInSection(String sectionName) {
        java.util.List<TopComponent> result = new ArrayList<>();
        boolean inSection = false;
        for (int i = 0; i < listModel.getSize(); i++) {
            Object item = listModel.getElementAt(i);
            if (item instanceof OpenFilesCellRenderer.GroupHeader) {
                inSection = ((OpenFilesCellRenderer.GroupHeader) item).name.equals(sectionName);
            } else if (inSection && item instanceof TopComponent) {
                result.add((TopComponent) item);
            }
        }
        return result;
    }

    private java.util.List<TopComponent> getSelectedTreeTCs() {
        LinkedHashSet<TopComponent> result = new LinkedHashSet<>();
        TreePath[] paths = fileTree.getSelectionPaths();
        if (paths != null) {
            for (TreePath path : paths) {
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getUserObject() instanceof TopComponent) {
                    result.add((TopComponent) node.getUserObject());
                } else if (node.getUserObject() instanceof String) {
                    for (int i = 0; i < node.getChildCount(); i++) {
                        DefaultMutableTreeNode child =
                                (DefaultMutableTreeNode) node.getChildAt(i);
                        if (child.getUserObject() instanceof TopComponent)
                            result.add((TopComponent) child.getUserObject());
                    }
                }
            }
        }
        return new ArrayList<>(result);
    }

    // =========================================================================
    // View switching
    // =========================================================================

    private void switchView(ViewMode mode) {
        viewMode = mode;
        PluginPrefs.setViewMode(mode == ViewMode.TREE
                ? PluginPrefs.VIEW_TREE : PluginPrefs.VIEW_LIST);
        CardLayout cl = (CardLayout) centerPanel.getLayout();
        cl.show(centerPanel, mode == ViewMode.TREE ? CARD_TREE : CARD_LIST);
        applyFilter();
    }

    private void focusCurrentView() {
        if (viewMode == ViewMode.LIST) {
            fileList.requestFocusInWindow();
            if (fileList.getSelectedIndex() < 0) {
                // Select first TC row, skipping any GroupHeader rows.
                for (int i = 0; i < listModel.getSize(); i++) {
                    if (listModel.getElementAt(i) instanceof TopComponent) {
                        fileList.setSelectedIndex(i);
                        fileList.ensureIndexIsVisible(i);
                        break;
                    }
                }
            }
        } else {
            fileTree.requestFocusInWindow();
        }
    }

    // =========================================================================
    // Listeners
    // =========================================================================

    private void registerListeners() {
        if (!listenersRegistered) {
            TopComponent.getRegistry().addPropertyChangeListener(windowListener);
            listenersRegistered = true;
        }
    }

    private void unregisterListeners() {
        if (listenersRegistered) {
            TopComponent.getRegistry().removePropertyChangeListener(windowListener);
            listenersRegistered = false;
        }
    }

    // =========================================================================
    // Data / refresh
    // =========================================================================

    public static String resolveDisplayNameStatic(TopComponent tc) {
        String name = tc.getDisplayName();
        if (name == null || name.trim().isEmpty()) name = tc.getHtmlDisplayName();
        if (name == null || name.trim().isEmpty()) name = tc.getName();
        if (name == null || name.trim().isEmpty()) return null;
        name = name.replaceAll("<[^>]+>", "").trim();
        return name.isEmpty() ? null : name;
    }

    private String resolveDisplayName(TopComponent tc) {
        return resolveDisplayNameStatic(tc);
    }

    private String resolveExtension(TopComponent tc) {
        String name = resolveDisplayName(tc);
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private String resolveFolder(TopComponent tc) {
        String tip = tc.getToolTipText();
        if (tip != null && !tip.trim().isEmpty()) {
            tip = tip.replace('\\', '/').trim();
            tip = tip.replaceAll("<[^>]+>", "").trim();
            int lastSlash = tip.lastIndexOf('/');
            if (lastSlash > 0) {
                String parentPath = tip.substring(0, lastSlash);
                int parentSlash   = parentPath.lastIndexOf('/');
                String folderName = parentPath.substring(parentSlash + 1);
                if (parentSlash > 0) {
                    String grandPath  = parentPath.substring(0, parentSlash);
                    int    grandSlash = grandPath.lastIndexOf('/');
                    String grandName  = grandPath.substring(grandSlash + 1);
                    if (grandName.length() >= 2) return grandName + " / " + folderName;
                }
                return folderName;
            }
        }
        String name = resolveDisplayName(tc);
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                switch (ext) {
                    case "plsql": case "plsvc": case "plsvt": return "PL/SQL";
                    case "entity": case "projection": case "client":
                    case "utility": case "fragment":          return "Model";
                    case "java":  return "Java";
                    case "xml":   return "XML";
                    case "rdf":   case "report": return "Reports";
                    default:      return ext.toUpperCase(Locale.ROOT);
                }
            }
        }
        return "Other";
    }

    /**
     * Extracts the IFS component name from the TC's tooltip path.
     *
     * <p>IFS workspace paths follow the convention:
     * <pre>
     *   …/workspace/&lt;component&gt;/&lt;source|model|…&gt;/…
     * </pre>
     * The component is the path segment immediately after the {@code workspace}
     * segment (case-insensitive match).  For example:
     * <pre>
     *   E:/…/workspace/shpmnt/source/shpmnt/database/ShipmentLine.plsql  → "shpmnt"
     *   E:/…/workspace/shpmnt/model/shpmnt/ShipmentLine.entity           → "shpmnt"
     * </pre>
     * If no {@code workspace} segment is found the method falls back to the
     * two-folder label produced by {@link #resolveFolder}.
     */
    private String resolveIfsComponent(TopComponent tc) {
        String tip = tc.getToolTipText();
        if (tip != null && !tip.trim().isEmpty()) {
            tip = tip.replace('\\', '/').trim();
            tip = tip.replaceAll("<[^>]+>", "").trim();
            String[] parts = tip.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("workspace".equalsIgnoreCase(parts[i]) && parts[i + 1].length() >= 2) {
                    return parts[i + 1];
                }
            }
        }
        // Fallback: use the two-folder label so the tree still makes sense for
        // files that live outside the workspace directory structure.
        return resolveFolder(tc);
    }

    private void refreshList() {
        Set<TopComponent> opened = TopComponent.getRegistry().getOpened();

        // Seed openOrder with any editors that were open before we registered
        // (e.g. at startup). New entries are appended to the end, preserving
        // the order in which they were already present.
        for (TopComponent tc : opened) {
            if (isEditorTC(tc)) openOrder.add(tc);
        }
        // Prune TCs that are no longer open.
        openOrder.removeIf(tc -> !opened.contains(tc));

        allEditors.clear();
        for (TopComponent tc : opened) {
            if (isEditorTC(tc)) allEditors.add(tc);
        }

        boolean openOrderSort = PluginPrefs.LIST_SORT_OPEN_ORDER.equals(PluginPrefs.getListSort());
        if (openOrderSort) {
            // Sort allEditors to match the openOrder insertion sequence.
            java.util.List<TopComponent> ordered = new ArrayList<>(openOrder);
            ordered.retainAll(new java.util.HashSet<>(allEditors));
            // Append any editors not yet in openOrder (shouldn't normally happen,
            // but defensive — add them alphabetically at the end).
            java.util.List<TopComponent> remainder = new ArrayList<>(allEditors);
            remainder.removeAll(new java.util.HashSet<>(ordered));
            remainder.sort(Comparator.comparing(
                    tc -> { String n = resolveDisplayName(tc); return n != null ? n : ""; },
                    String.CASE_INSENSITIVE_ORDER));
            allEditors.clear();
            allEditors.addAll(ordered);
            allEditors.addAll(remainder);
        } else {
            allEditors.sort(Comparator.comparing(
                    tc -> { String n = resolveDisplayName(tc); return n != null ? n : ""; },
                    String.CASE_INSENSITIVE_ORDER));
        }
        applyFilter();
    }

    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        java.util.List<TopComponent> filtered = new ArrayList<>();
        for (TopComponent tc : allEditors) {
            String name = resolveDisplayName(tc);
            if (name == null) continue;
            if (query.isEmpty() || FuzzyMatcher.matches(query, name)) filtered.add(tc);
        }
        if (viewMode == ViewMode.LIST) applyFilterToList(filtered);
        else                           applyFilterToTree(filtered);
    }

    // =========================================================================
    // List population
    // =========================================================================

    private void applyFilterToList(java.util.List<TopComponent> filtered) {
        Set<String>  pinnedKeys  = PluginPrefs.getPins();
        Set<String>  groupedKeys = PluginPrefs.allGroupedKeys();
        Map<String, java.util.List<String>> groupDef = PluginPrefs.getGroups();
        boolean buildGrouped = PluginPrefs.BUILD_GROUPED.equals(PluginPrefs.getBuildGrouping());

        Map<String, TopComponent> keyToTc = new LinkedHashMap<>();
        for (TopComponent tc : filtered) keyToTc.put(PluginPrefs.keyFor(tc), tc);

        java.util.List<Object> prevSelected = fileList.getSelectedValuesList();
        TopComponent active = TopComponent.getRegistry().getActivated();

        listModel.clear();

        // 1. Pinned
        java.util.List<TopComponent> pinnedList = new ArrayList<>();
        for (String key : pinnedKeys) {
            TopComponent tc = keyToTc.get(key);
            if (tc != null) pinnedList.add(tc);
        }
        addSection(PluginPrefs.SECTION_PINNED, pinnedList);

        // 2. User groups
        for (Map.Entry<String, java.util.List<String>> entry : groupDef.entrySet()) {
            String groupName = entry.getKey();
            java.util.List<TopComponent> groupTCs = new ArrayList<>();
            for (String key : entry.getValue()) {
                TopComponent tc = keyToTc.get(key);
                if (tc != null) groupTCs.add(tc);
            }
            if (!groupTCs.isEmpty()) addSection(groupName, groupTCs);
        }

        // 3. Ungrouped non-build
        java.util.List<TopComponent> ungrouped = new ArrayList<>();
        for (TopComponent tc : filtered) {
            String key = PluginPrefs.keyFor(tc);
            if (!pinnedKeys.contains(key) && !groupedKeys.contains(key)
                    && !(buildGrouped && PluginPrefs.isBuildFile(tc))) {
                ungrouped.add(tc);
            }
        }
        if (!ungrouped.isEmpty()) addSection(PluginPrefs.SECTION_UNGROUPED, ungrouped);

        // 4. Generated (build files) — only when BUILD_GROUPED
        if (buildGrouped) {
            java.util.List<TopComponent> buildFiles = new ArrayList<>();
            for (TopComponent tc : filtered) {
                String key = PluginPrefs.keyFor(tc);
                if (!pinnedKeys.contains(key) && !groupedKeys.contains(key)
                        && PluginPrefs.isBuildFile(tc)) {
                    buildFiles.add(tc);
                }
            }
            if (!buildFiles.isEmpty()) addSection(PluginPrefs.SECTION_GENERATED, buildFiles);
        }

        // Restore selection
        if (!prevSelected.isEmpty()) {
            java.util.List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < listModel.getSize(); i++) {
                if (prevSelected.contains(listModel.getElementAt(i))) indices.add(i);
            }
            if (!indices.isEmpty()) {
                int[] arr = indices.stream().mapToInt(Integer::intValue).toArray();
                fileList.setSelectedIndices(arr);
                fileList.ensureIndexIsVisible(arr[0]);
                return;
            }
        }
        if (active != null) {
            for (int i = 0; i < listModel.getSize(); i++) {
                if (listModel.getElementAt(i) == active) {
                    fileList.setSelectedIndex(i);
                    fileList.ensureIndexIsVisible(i);
                    return;
                }
            }
        }
        // Fall back to the first actual TC row — skip GroupHeader rows so we
        // never leave a section header highlighted as the "selected" item.
        fileList.clearSelection();
        for (int i = 0; i < listModel.getSize(); i++) {
            if (listModel.getElementAt(i) instanceof TopComponent) {
                fileList.setSelectedIndex(i);
                fileList.ensureIndexIsVisible(i);
                break;
            }
        }
    }

    private void addSection(String name, java.util.List<TopComponent> tcs) {
        if (tcs.isEmpty()) return;
        boolean collapsed = PluginPrefs.isGroupCollapsed(name);
        listModel.addElement(new OpenFilesCellRenderer.GroupHeader(name, tcs.size()));
        if (!collapsed) tcs.forEach(listModel::addElement);
    }

    // =========================================================================
    // Tree population
    // =========================================================================

    private void applyFilterToTree(java.util.List<TopComponent> filtered) {
        Set<String>  pinnedKeys  = PluginPrefs.getPins();
        Set<String>  groupedKeys = PluginPrefs.allGroupedKeys();
        Map<String, java.util.List<String>> groupDef = PluginPrefs.getGroups();
        boolean buildGrouped = PluginPrefs.BUILD_GROUPED.equals(PluginPrefs.getBuildGrouping());

        Map<String, TopComponent> keyToTc = new LinkedHashMap<>();
        for (TopComponent tc : filtered) keyToTc.put(PluginPrefs.keyFor(tc), tc);

        Set<TopComponent> prevSelected = snapshotTreeSelection();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("root");

        // 1. Pinned folder
        java.util.List<TopComponent> pinnedList = new ArrayList<>();
        for (String key : pinnedKeys) {
            TopComponent tc = keyToTc.get(key);
            if (tc != null) pinnedList.add(tc);
        }
        if (!pinnedList.isEmpty()) {
            DefaultMutableTreeNode pNode =
                    new DefaultMutableTreeNode(PluginPrefs.SECTION_PINNED);
            pinnedList.forEach(tc -> pNode.add(new DefaultMutableTreeNode(tc)));
            root.add(pNode);
        }

        // 2. User group folders
        for (Map.Entry<String, java.util.List<String>> entry : groupDef.entrySet()) {
            java.util.List<TopComponent> groupTCs = new ArrayList<>();
            for (String key : entry.getValue()) {
                TopComponent tc = keyToTc.get(key);
                if (tc != null) groupTCs.add(tc);
            }
            if (groupTCs.isEmpty()) continue;
            DefaultMutableTreeNode gNode =
                    new DefaultMutableTreeNode("\u229E " + entry.getKey()); // ⊞
            groupTCs.forEach(tc -> gNode.add(new DefaultMutableTreeNode(tc)));
            root.add(gNode);
        }

        // 3. Ungrouped: grouped either by two-folder path or by IFS component
        boolean byComponent = PluginPrefs.TREE_GROUP_COMPONENT.equals(PluginPrefs.getTreeGrouping());
        Map<String, java.util.List<TopComponent>> folders = new LinkedHashMap<>();
        for (TopComponent tc : filtered) {
            String key = PluginPrefs.keyFor(tc);
            if (pinnedKeys.contains(key) || groupedKeys.contains(key)) continue;
            if (buildGrouped && PluginPrefs.isBuildFile(tc)) continue;
            String label = byComponent ? resolveIfsComponent(tc) : resolveFolder(tc);
            folders.computeIfAbsent(label, k -> new ArrayList<>()).add(tc);
        }
        java.util.List<String> sortedFolders = new ArrayList<>(folders.keySet());
        Collections.sort(sortedFolders, String.CASE_INSENSITIVE_ORDER);
        for (String folder : sortedFolders) {
            DefaultMutableTreeNode fNode = new DefaultMutableTreeNode(folder);
            folders.get(folder).forEach(tc -> fNode.add(new DefaultMutableTreeNode(tc)));
            root.add(fNode);
        }

        // 4. Generated folder (only when BUILD_GROUPED)
        if (buildGrouped) {
            java.util.List<TopComponent> buildFiles = new ArrayList<>();
            for (TopComponent tc : filtered) {
                String key = PluginPrefs.keyFor(tc);
                if (!pinnedKeys.contains(key) && !groupedKeys.contains(key)
                        && PluginPrefs.isBuildFile(tc)) {
                    buildFiles.add(tc);
                }
            }
            if (!buildFiles.isEmpty()) {
                DefaultMutableTreeNode genNode =
                        new DefaultMutableTreeNode(PluginPrefs.SECTION_GENERATED);
                buildFiles.forEach(tc -> genNode.add(new DefaultMutableTreeNode(tc)));
                root.add(genNode);
            }
        }

        treeModel.setRoot(root);
        // Expand or collapse each top-level folder according to persisted state.
        // Walk root children directly — row-index loops are unreliable because
        // expanding a row changes subsequent row indices.
        for (int ci = 0; ci < root.getChildCount(); ci++) {
            DefaultMutableTreeNode child =
                    (DefaultMutableTreeNode) root.getChildAt(ci);
            TreePath folderPath = new TreePath(new Object[]{root, child});
            // Derive section name: strip the "⊞ " prefix we add to group labels
            String sectionName = child.getUserObject() instanceof String
                    ? (String) child.getUserObject() : "";
            if (sectionName.startsWith("⊞ ")) {
                sectionName = sectionName.substring(2);
            }
            if (PluginPrefs.isGroupCollapsed(sectionName)) {
                fileTree.collapsePath(folderPath);
            } else {
                fileTree.expandPath(folderPath);
            }
        }

        if (!prevSelected.isEmpty()) {
            restoreTreeSelection(prevSelected);
            if (fileTree.getSelectionCount() > 0) return;
        }
        TopComponent active = TopComponent.getRegistry().getActivated();
        if (active != null) selectInTree(active);
    }

    // =========================================================================
    // Tree utilities
    // =========================================================================

    private Set<TopComponent> snapshotTreeSelection() {
        Set<TopComponent> result = new LinkedHashSet<>();
        TreePath[] paths = fileTree.getSelectionPaths();
        if (paths == null) return result;
        for (TreePath tp : paths) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) tp.getLastPathComponent();
            if (n.getUserObject() instanceof TopComponent) {
                result.add((TopComponent) n.getUserObject());
            } else if (n.getUserObject() instanceof String) {
                for (int i = 0; i < n.getChildCount(); i++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) n.getChildAt(i);
                    if (child.getUserObject() instanceof TopComponent)
                        result.add((TopComponent) child.getUserObject());
                }
            }
        }
        return result;
    }

    private void restoreTreeSelection(Set<TopComponent> targets) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<?> e = root.depthFirstEnumeration();
        java.util.List<TreePath> paths = new ArrayList<>();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
            if (node.getUserObject() instanceof TopComponent
                    && targets.contains(node.getUserObject()))
                paths.add(new TreePath(node.getPath()));
        }
        if (paths.isEmpty()) return;
        fileTree.setSelectionPaths(paths.toArray(new TreePath[0]));
        fileTree.scrollPathToVisible(paths.get(0));
    }

    private void selectInTree(TopComponent target) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        Enumeration<?> e = root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
            if (node.getUserObject() == target) {
                TreePath path = new TreePath(node.getPath());
                fileTree.setSelectionPath(path);
                fileTree.scrollPathToVisible(path);
                return;
            }
        }
    }

    // =========================================================================
    // isEditorTC / activation
    // =========================================================================

    private boolean isEditorTC(TopComponent tc) {
        if (tc == this) return false;
        String className = tc.getClass().getName();
        if (className.contains("ProjectsTab") || className.contains("FilesTab")
                || className.contains("FavoritesTab") || className.contains("NavigatorTC")
                || className.contains("OutputTopComponent")
                || className.contains("OpenFilesTopComponent")) return false;
        // Exclude VCS diff viewers — they open temp copies in the OS temp
        // directory and cannot be meaningfully listed or reopened.
        String tip = tc.getToolTipText();
        if (tip != null && !tip.trim().isEmpty()) {
            String normPath = tip.replace('\\', '/').trim()
                                 .replaceAll("<[^>]+>", "").trim()
                                 .toLowerCase(Locale.ROOT);
            String tempDir  = System.getProperty("java.io.tmpdir", "")
                                 .replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!tempDir.isEmpty() && normPath.startsWith(tempDir)) return false;
        }
        Mode mode = WindowManager.getDefault().findMode(tc);
        if (mode == null) return false;
        String modeName = mode.getName();
        return modeName.equals("editor") || modeName.equals("multiview")
                || modeName.startsWith("editor") || modeName.contains("editor");
    }

    /**
     * Called on PROP_ACTIVATED only — updates the selection/highlight in the
     * current view to track the newly active TC, without rebuilding the model
     * or touching tree expand/collapse state.
     */
    private void updateActiveHighlight() {
        TopComponent active = TopComponent.getRegistry().getActivated();
        if (active == null) return;
        if (viewMode == ViewMode.LIST) {
            // Repaint so the active-BG highlight updates; also sync selection.
            for (int i = 0; i < listModel.getSize(); i++) {
                if (listModel.getElementAt(i) == active) {
                    if (!fileList.isSelectedIndex(i)) {
                        fileList.setSelectedIndex(i);
                        fileList.ensureIndexIsVisible(i);
                    }
                    break;
                }
            }
            fileList.repaint();
        } else {
            // Tree: just move the selection to the active TC node.
            selectInTree(active);
        }
    }

    private void activateFromList() {
        for (int idx : fileList.getSelectedIndices()) {
            Object item = listModel.getElementAt(idx);
            if (item instanceof TopComponent) {
                ((TopComponent) item).requestActive();
                return;
            }
        }
    }

    private void activateFromTree() {
        TreePath path = fileTree.getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof TopComponent)
            ((TopComponent) node.getUserObject()).requestActive();
    }

    // =========================================================================
    // IFS action runners
    // =========================================================================

    private void runActionOnEDT(String category, String actionId,
            java.util.List<TopComponent> targets) {
        TopComponent tc = targets.get(0);
        tc.requestActive();
        javax.swing.Timer t = new javax.swing.Timer(300, e -> {
            if (!tc.isOpened()) return;
            Action action = Actions.forID(category, actionId);
            if (action != null)
                action.actionPerformed(new ActionEvent(
                        tc, ActionEvent.ACTION_PERFORMED, ""));
        });
        t.setRepeats(false);
        t.start();
    }

    private void runActionOnTargets(String category, String actionId,
            java.util.List<TopComponent> targets) {
        if (targets.isEmpty()) return;
        if (targets.size() == 1) {
            TopComponent single = targets.get(0);
            SwingUtilities.invokeLater(() -> {
                if (!single.isOpened()) return;
                single.requestActive();
                Action action = Actions.forID(category, actionId);
                if (action != null)
                    action.actionPerformed(new ActionEvent(
                            single, ActionEvent.ACTION_PERFORMED, ""));
                else
                    System.err.println("[OpenFilesNavigator] Action not found: "
                            + category + " / " + actionId);
            });
            return;
        }
        SwingUtilities.invokeLater(() -> {
            java.util.List<Lookup> lookups = new ArrayList<>(targets.size());
            for (TopComponent tc : targets) if (tc.isOpened()) lookups.add(tc.getLookup());
            if (lookups.isEmpty()) return;
            Lookup merged = new ProxyLookup(lookups.toArray(new Lookup[0]));
            Action action = Actions.forID(category, actionId);
            if (action == null) {
                System.err.println("[OpenFilesNavigator] Action not found: "
                        + category + " / " + actionId);
                return;
            }
            TopComponent first = targets.get(0);
            if (first.isOpened()) first.requestActive();
            if (action instanceof ContextAwareAction)
                action = ((ContextAwareAction) action).createContextAwareInstance(merged);
            action.actionPerformed(new ActionEvent(
                    merged, ActionEvent.ACTION_PERFORMED, ""));
        });
    }

    // =========================================================================
    // TopComponent lifecycle
    // =========================================================================

    @Override public void componentOpened() {
        registerListeners(); refreshList(); scheduleDelayedRefresh();
    }
    @Override public void componentClosed() {
        unregisterListeners(); instance = null;
    }
    @Override protected void componentActivated() {
        updateActiveHighlight();
        searchField.requestFocusInWindow();
    }
    @Override public int getPersistenceType() { return TopComponent.PERSISTENCE_ALWAYS; }
    @Override public String preferredID()      { return "OpenFilesTopComponent"; }

    // =========================================================================
    // Tree cell renderer
    // =========================================================================

    private static class OpenTreeCellRenderer extends DefaultTreeCellRenderer {

        private static final String FOLDER_FG_KEY = "Label.disabledForeground";
        private Color tagDotColor = null;

        @Override
        public Component getTreeCellRendererComponent(
                JTree tree, Object value, boolean sel, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {

            super.getTreeCellRendererComponent(
                    tree, value, sel, expanded, leaf, row, hasFocus);

            if (!(value instanceof DefaultMutableTreeNode)) return this;
            Object userObj = ((DefaultMutableTreeNode) value).getUserObject();

            if (userObj instanceof TopComponent) {
                TopComponent tc  = (TopComponent) userObj;
                boolean isBuild  = PluginPrefs.isBuildFile(tc);
                String  tcKey    = PluginPrefs.keyFor(tc);
                String  name     = resolveDisplayNameStatic(tc);
                if (name == null) name = "(unnamed)";

                String  note    = PluginPrefs.getNote(tcKey);
                boolean hasNote = note != null && !note.trim().isEmpty();
                boolean pinned  = PluginPrefs.isPinned(tcKey);
                boolean inline  = PluginPrefs.NOTE_INLINE.equals(PluginPrefs.getNoteDisplay());

                StringBuilder sb = new StringBuilder();
                if (pinned)            sb.append("\uD83D\uDCCC "); // 📌
                sb.append(name);
                if (hasNote && inline) sb.append("  \u270E");       // ✎
                setText(sb.toString());

                // Italic for build files
                setFont(getFont().deriveFont(isBuild ? Font.ITALIC : Font.PLAIN));

                // Colour
                if (!sel) {
                    if (isBuild) setForeground(OpenFilesCellRenderer.BUILD_FILE_COLOR);
                }

                if (hasNote) {
                    setToolTipText("<html><b>Note:</b> "
                            + OpenFilesCellRenderer.escapeHtml(note) + "</html>");
                } else {
                    String tip = tc.getToolTipText();
                    setToolTipText(tip != null && !tip.isEmpty() ? tip : null);
                }

                java.awt.Image img = tc.getIcon();
                if (img != null) setIcon(new ImageIcon(img));

                // Tag dot
                PluginPrefs.TagColor tag = PluginPrefs.getTag(tcKey);
                tagDotColor = (tag != PluginPrefs.TagColor.NONE) ? tag.color : null;

            } else if (userObj instanceof String) {
                tagDotColor = null;
                String folderName = (String) userObj;
                setText(folderName);

                boolean isGenerated = PluginPrefs.SECTION_GENERATED.equals(folderName);
                setIcon(UIManager.getIcon("FileView.directoryIcon"));
                setFont(getFont().deriveFont(Font.BOLD));
                if (!sel) {
                    if (isGenerated) {
                        setForeground(OpenFilesCellRenderer.BUILD_FILE_COLOR.darker());
                    } else {
                        Color fg = UIManager.getColor(FOLDER_FG_KEY);
                        setForeground(fg != null ? fg : new Color(100, 100, 100));
                    }
                }
            } else {
                tagDotColor = null;
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (tagDotColor == null) return;
            // Paint a small filled circle at the right edge of the label.
            int h = getHeight();
            int w = getWidth();
            int d = 8; // dot diameter
            int x = w - d - 4;
            int y = (h - d) / 2;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(tagDotColor);
            g2.fillOval(x, y, d, d);
            g2.dispose();
        }
    }
}