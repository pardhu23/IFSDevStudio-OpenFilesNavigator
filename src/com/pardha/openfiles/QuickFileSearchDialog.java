package com.pardha.openfiles;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.windows.WindowManager;

/**
 * Ctrl+P quick file search popup for IFS Developer Studio.
 *
 * <p>Press Ctrl+P → floating dialog appears → type filename (fuzzy matched) →
 * Enter or click to open. Escape dismisses.
 *
 * <p>File cache: built once in a background thread when the dialog is first
 * opened (or when stale). Subsequent opens within CACHE_TTL_MS reuse the
 * cache instantly. The cache is cleared when the dialog is closed to keep
 * memory footprint near zero while the dialog is hidden.
 */
public final class QuickFileSearchDialog extends JDialog {

    // ── Singleton ──────────────────────────────────────────────────────────
    private static QuickFileSearchDialog instance;

    public static void showDialog() {
        if (instance == null || !instance.isDisplayable()) {
            Frame owner = WindowManager.getDefault().getMainWindow();
            instance = new QuickFileSearchDialog(owner);
        }
        instance.openAndFocus();
    }

    // ── IFS file extensions to index ──────────────────────────────────────
    private static final Set<String> IFS_EXTENSIONS = new HashSet<>(Arrays.asList(
            "entity", "projection", "client", "plsql", "plsvc", "plsvt",
            "fragment", "utility", "java", "xml", "rdf", "report", "cre",
            "upg", "apy", "ins"
    ));

    // ── Cache ──────────────────────────────────────────────────────────────
    // Both caches are STATIC — survive dialog close/reopen for the whole IDE session.

    // Core files (D:\IFS Workspace\...\checkout) — huge, change rarely.
    // Built once per session, never invalidated automatically.
    private static List<FileEntry> coreCache = null;
    private static String coreCacheRoot = null;
    private static volatile boolean coreCacheBuilding = false;

    // Cust layer files — built once, then invalidated only when a file is
    // created or deleted in the workspace (via FileChangeListener).
    // The dialog always opens instantly showing the previous cache;
    // a background rebuild runs silently when the stale flag is set.
    private static List<FileEntry> custCache = null;
    private static volatile boolean custCacheBuilding = false;
    private static volatile boolean custCacheStale = true; // true = needs (re)build
    private static org.openide.filesystems.FileChangeListener custWatcher = null;
    private static String custWatchedRoot = null; // path currently being watched

    // ── UI ─────────────────────────────────────────────────────────────────
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<FileEntry> resultModel = new DefaultListModel<>();
    private final JList<FileEntry> resultList = new JList<>(resultModel);
    private final JLabel statusLabel = new JLabel(" ");

    // ── Data ───────────────────────────────────────────────────────────────
    enum Source { CUST, CORE, GENERATED }

    static final class FileEntry {
        final String name;         // e.g. "CustomerOrder.entity"
        final String relativePath; // e.g. "shpmnt/model/CustomerOrder.entity"
        final String absolutePath;
        final Source source;       // where the file came from
        int score;                 // set during each filter pass

        FileEntry(String name, String relativePath, String absolutePath, Source source) {
            this.name = name;
            this.relativePath = relativePath;
            this.absolutePath = absolutePath;
            this.source = source;
        }
    }

    // ── Construction ───────────────────────────────────────────────────────
    private QuickFileSearchDialog(Frame owner) {
        super(owner, false); // non-modal so IDE stays responsive
        setUndecorated(true);
        buildUI();
        pack();
        setSize(560, 420);
        centerOnOwner(owner);

        // Close on focus loss (click outside the dialog)
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                // Don't close if focus went to a child (e.g. the list)
                Component opposite = e.getOppositeWindow();
                if (opposite != QuickFileSearchDialog.this) {
                    closeDialog();
                }
            }
        });

        // Escape to close
        getRootPane().registerKeyboardAction(
                e -> closeDialog(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(new LineBorder(UIManager.getColor("Separator.foreground") != null
                ? UIManager.getColor("Separator.foreground")
                : new Color(160, 160, 160), 1));
        root.setBackground(UIManager.getColor("Panel.background"));
        setContentPane(root);

        // ── Search field ──────────────────────────────────────────────────
        JPanel topPanel = new JPanel(new BorderLayout(6, 0));
        topPanel.setBorder(new EmptyBorder(8, 10, 8, 10));
        topPanel.setOpaque(false);

        JLabel icon = new JLabel("\uD83D\uDD0D"); // 🔍
        icon.setFont(icon.getFont().deriveFont(14f));
        topPanel.add(icon, BorderLayout.WEST);

        searchField.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        searchField.setFont(searchField.getFont().deriveFont(13f));
        searchField.putClientProperty("JTextField.placeholderText", "Search files\u2026");
        searchField.setOpaque(false);
        topPanel.add(searchField, BorderLayout.CENTER);

        root.add(topPanel, BorderLayout.NORTH);

        // ── Separator ─────────────────────────────────────────────────────
        JSeparator sep = new JSeparator();
        root.add(sep, BorderLayout.CENTER);

        // ── Results list ──────────────────────────────────────────────────
        resultList.setCellRenderer(new FileEntryRenderer());
        resultList.setFixedCellHeight(42);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setBackground(UIManager.getColor("List.background"));
        resultList.setBorder(new EmptyBorder(2, 0, 2, 0));
        resultList.setFocusable(false); // keyboard stays in searchField

        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(resultList);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        // ── Status bar ────────────────────────────────────────────────────
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 10f));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusLabel.setBorder(new EmptyBorder(2, 10, 3, 10));

        JPanel center = new JPanel(new BorderLayout());
        center.setOpaque(false);
        center.add(scroll, BorderLayout.CENTER);
        center.add(statusLabel, BorderLayout.SOUTH);

        root.add(sep, BorderLayout.NORTH); // overwrite — we'll use a different layout
        // Redo layout: topPanel NORTH, center CENTER
        root.removeAll();
        root.add(topPanel, BorderLayout.NORTH);

        JSeparator sep2 = new JSeparator();
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(sep2, BorderLayout.NORTH);
        body.add(center, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        // ── Document listener (live filter) ───────────────────────────────
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onQueryChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onQueryChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onQueryChanged(); }
        });

        // ── Keyboard navigation ───────────────────────────────────────────
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int sel = resultList.getSelectedIndex();
                int size = resultModel.getSize();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN:
                        if (sel < size - 1) {
                            resultList.setSelectedIndex(sel + 1);
                            resultList.ensureIndexIsVisible(sel + 1);
                        }
                        e.consume();
                        break;
                    case KeyEvent.VK_UP:
                        if (sel > 0) {
                            resultList.setSelectedIndex(sel - 1);
                            resultList.ensureIndexIsVisible(sel - 1);
                        }
                        e.consume();
                        break;
                    case KeyEvent.VK_ENTER:
                        openSelected();
                        e.consume();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        closeDialog();
                        e.consume();
                        break;
                }
            }
        });
    }

    // ── Open / close ───────────────────────────────────────────────────────
    private void openAndFocus() {
        searchField.setText("");
        resultModel.clear();
        statusLabel.setText(" ");
        setVisible(true);
        toFront();
        searchField.requestFocusInWindow();
        ensureCache();
    }

    private void closeDialog() {
        setVisible(false);
        // Both caches are static — nothing to clear on close.
        // The cust watcher will mark custCacheStale if files change while closed.
    }

    // ── Cache management ───────────────────────────────────────────────────

    /**
     * Ensures both caches are ready, then triggers filtering.
     *
     * Cust cache: built once, then only rebuilt when custCacheStale=true
     *   (set by the FileChangeListener when files are created/deleted).
     *   The dialog always opens instantly showing the existing cache;
     *   a background rebuild runs silently if stale.
     *
     * Core cache: built once per IDE session. Never invalidated automatically.
     *
     * If a cache is building, existing data is shown immediately and the
     * UI refreshes when the build finishes.
     */
    private void ensureCache() {
        boolean coreReady = coreCache != null;
        boolean custReady = custCache != null && !custCacheStale;

        if (custReady && coreReady) {
            onQueryChanged();
            return;
        }

        // Show whatever we have immediately — never block the UI
        if (custCache != null || coreCache != null) {
            onQueryChanged();
        }

        if (!custReady && !custCacheBuilding) {
            buildCustCacheAsync();
        }
        if (!coreReady && !coreCacheBuilding) {
            buildCoreCacheAsync();
        }
    }

    private void buildCustCacheAsync() {
        custCacheBuilding = true;
        custCacheStale = false; // clear before scan so concurrent changes re-flag it
        if (custCache == null) {
            statusLabel.setText("Scanning project files\u2026");
        }
        new Thread(() -> {
            try {
                // Collect workspace roots before scanning so we can watch them
                List<String> roots = collectCustRoots();
                List<FileEntry> entries = buildCustFiles();
                SwingUtilities.invokeLater(() -> {
                    custCache = entries;
                    custCacheBuilding = false;
                    registerCustWatcher(roots);
                    onQueryChanged();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    custCacheBuilding = false;
                    custCacheStale = true; // allow retry next open
                    statusLabel.setText("Project scan failed: " + ex.getMessage());
                });
            }
        }, "QuickFileSearch-CustIndexer").start();
    }

    /**
     * Collects the workspace root paths from all open projects
     * (without walking the files — fast, just project roots).
     */
    private List<String> collectCustRoots() {
        List<String> roots = new ArrayList<>();
        try {
            org.netbeans.api.project.Project[] projects =
                    org.netbeans.api.project.ui.OpenProjects
                            .getDefault().getOpenProjects();
            for (org.netbeans.api.project.Project project : projects) {
                org.openide.filesystems.FileObject projectDir =
                        project.getProjectDirectory();
                if (projectDir == null) continue;
                File root = FileUtil.toFile(projectDir);
                if (root != null && root.isDirectory()) {
                    roots.add(root.getAbsolutePath());
                }
            }
        } catch (Exception ex) {
            System.err.println("[QuickFileSearch] collectCustRoots: " + ex);
        }
        return roots;
    }

    /**
     * Registers a FileChangeListener on each cust project root.
     * When any file is created or deleted, marks the cust cache as stale.
     * The next Ctrl+P will trigger a background rebuild automatically.
     *
     * Unregisters the previous watcher first to avoid double-listening
     * if the project list changes between scans.
     */
    private void registerCustWatcher(List<String> roots) {
        // Unregister previous watcher
        if (custWatcher != null && custWatchedRoot != null) {
            try {
                org.openide.filesystems.FileObject fo =
                        FileUtil.toFileObject(FileUtil.normalizeFile(
                                new File(custWatchedRoot)));
                if (fo != null) {
                    fo.removeRecursiveListener(custWatcher);
                }
            } catch (Exception ex) {
                System.err.println("[QuickFileSearch] watcher unregister: " + ex);
            }
        }

        if (roots.isEmpty()) return;

        custWatcher = new org.openide.filesystems.FileChangeAdapter() {
            @Override
            public void fileDataCreated(org.openide.filesystems.FileEvent fe) {
                custCacheStale = true;
            }
            @Override
            public void fileFolderCreated(org.openide.filesystems.FileEvent fe) {
                custCacheStale = true;
            }
            @Override
            public void fileDeleted(org.openide.filesystems.FileEvent fe) {
                custCacheStale = true;
            }
        };

        // Watch all project roots (usually just one)
        for (String rootPath : roots) {
            try {
                org.openide.filesystems.FileObject fo =
                        FileUtil.toFileObject(FileUtil.normalizeFile(new File(rootPath)));
                if (fo != null) {
                    fo.addRecursiveListener(custWatcher);
                    custWatchedRoot = rootPath;
                    System.err.println("[QuickFileSearch] Watching: " + rootPath);
                }
            } catch (Exception ex) {
                System.err.println("[QuickFileSearch] watcher register: " + ex);
            }
        }
    }

    private void buildCoreCacheAsync() {
        coreCacheBuilding = true;
        statusLabel.setText("Scanning core files (one-time)\u2026");
        new Thread(() -> {
            try {
                List<String[]> roots = findCoreRoots();   // [absPath, displayRoot]
                if (roots.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        coreCache = new ArrayList<>();
                        coreCacheBuilding = false;
                        onQueryChanged();
                    });
                    return;
                }
                List<FileEntry> entries = new ArrayList<>();
                for (String[] root : roots) {
                    File dir = new File(root[0]);
                    walkDirectory(dir, root[0].replace('\\', '/'), entries, Source.CORE);
                }
                System.err.println("[QuickFileSearch] Core cache built: "
                        + entries.size() + " files");
                SwingUtilities.invokeLater(() -> {
                    coreCache = entries;
                    coreCacheBuilding = false;
                    onQueryChanged();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    coreCache = new ArrayList<>(); // empty so we don't retry forever
                    coreCacheBuilding = false;
                    statusLabel.setText("Core scan failed: " + ex.getMessage());
                });
            }
        }, "QuickFileSearch-CoreIndexer").start();
    }

    /**
     * Scans only the customisation project directories (fast — small file count).
     * Called on every Ctrl+P if cache is stale.
     */
    private List<FileEntry> buildCustFiles() {
        List<FileEntry> result = new ArrayList<>();
        Set<String> scannedRoots = new HashSet<>();

        org.netbeans.api.project.Project[] projects =
                org.netbeans.api.project.ui.OpenProjects
                        .getDefault().getOpenProjects();

        for (org.netbeans.api.project.Project project : projects) {
            FileObject projectDir = project.getProjectDirectory();
            if (projectDir == null) continue;
            File root = FileUtil.toFile(projectDir);
            if (root == null || !root.isDirectory()) continue;

            String rootPath = root.getAbsolutePath().replace('\\', '/');
            if (scannedRoots.add(rootPath)) {
                walkDirectory(root, rootPath, result);
            }
        }
        return result;
    }

    /**
     * Returns a list of [absolutePath, absolutePath] pairs for all unique
     * Core Files roots found across open projects.
     * Called once per IDE session — result is stored in the static coreCache.
     */
    private List<String[]> findCoreRoots() {
        List<String[]> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        org.netbeans.api.project.Project[] projects =
                org.netbeans.api.project.ui.OpenProjects
                        .getDefault().getOpenProjects();

        for (org.netbeans.api.project.Project project : projects) {
            FileObject projectDir = project.getProjectDirectory();
            if (projectDir == null) continue;
            File projRoot = FileUtil.toFile(projectDir);
            if (projRoot == null) continue;

            File coreRoot = readCoreFilesRoot(projRoot);
            if (coreRoot != null && coreRoot.isDirectory()) {
                String path = coreRoot.getAbsolutePath();
                if (seen.add(path)) {
                    roots.add(new String[]{ path, path });
                }
            }
        }
        return roots;
    }

    /**
     * Reads the IFS "Core Files" root directory from the project's
     * nbproject/project.properties file.
     *
     * IFS Customisation Projects store the core checkout path under keys like:
     *   core.files.location=D:/IFS Workspace/24.1.1_New/checkout
     *
     * Returns null if not found.
     */
    private File readCoreFilesRoot(File projectDir) {
        File[] candidates = {
            new File(projectDir, "nbproject/project.properties"),
            new File(projectDir, "project.properties")
        };

        for (File propsFile : candidates) {
            if (!propsFile.exists()) {
                continue;
            }
            java.util.Properties props = new java.util.Properties();
            try (java.io.FileInputStream fis = new java.io.FileInputStream(propsFile)) {
                props.load(fis);
            } catch (Exception ex) {
                continue;
            }

            // Try known IFS property key names (most likely first)
            String[] keys = {
                "project.ccs.corefiles",   // IFS Customisation Project (confirmed)
                "core.files.location",
                "corefiles.dir",
                "core.dir",
                "ifs.core.dir",
                "core.files.dir"
            };
            for (String key : keys) {
                String val = props.getProperty(key);
                if (val != null && !val.trim().isEmpty()) {
                    File f = new File(val.trim());
                    if (!f.isAbsolute()) {
                        f = new File(projectDir, val.trim());
                    }
                    f = FileUtil.normalizeFile(f);
                    if (f.isDirectory()) {
                        System.err.println("[QuickFileSearch] Core files root from '"
                                + key + "': " + f.getAbsolutePath());
                        return f;
                    }
                }
            }
        }
        return null;
    }

    private void walkDirectory(File dir, String rootPath, List<FileEntry> result) {
        walkDirectory(dir, rootPath, result, Source.CUST);
    }

    private void walkDirectory(File dir, String rootPath, List<FileEntry> result, Source source) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isHidden()) {
                continue;
            }
            String name = f.getName();
            if (f.isDirectory()) {
                // Skip obvious noise directories
                if (name.equals(".git") || name.equals(".svn")
                        || name.equals("node_modules") || name.equals("target")
                        || name.equals(".idea") || name.equals("nbproject")) {
                    continue;
                }
                // Detect generated (build) files by /build/ in their path
                Source childSource = source;
                if (source == Source.CUST && name.equals("build")) {
                    childSource = Source.GENERATED;
                }
                walkDirectory(f, rootPath, result, childSource);
            } else {
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    continue;
                }
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!IFS_EXTENSIONS.contains(ext)) {
                    continue;
                }
                String absPath = f.getAbsolutePath().replace('\\', '/');
                String relPath = absPath.startsWith(rootPath)
                        ? absPath.substring(rootPath.length() + 1)
                        : absPath;
                result.add(new FileEntry(name, relPath, absPath, source));
            }
        }
    }

    // ── Filtering ──────────────────────────────────────────────────────────
    private void onQueryChanged() {
        // Merge both caches — whichever are available right now
        List<FileEntry> allFiles = new ArrayList<>();
        if (custCache != null) allFiles.addAll(custCache);
        if (coreCache != null) allFiles.addAll(coreCache);

        if (allFiles.isEmpty() && (custCacheBuilding || coreCacheBuilding)) {
            resultModel.clear();
            statusLabel.setText("Scanning files\u2026");
            return;
        }

        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<FileEntry> matched = new ArrayList<>();

        if (query.isEmpty()) {
            matched.addAll(allFiles);
        } else {
            for (FileEntry entry : allFiles) {
                int score = FuzzyMatcher.score(query, entry.name.toLowerCase(Locale.ROOT));
                if (score >= 0) {
                    entry.score = score;
                    matched.add(entry);
                }
            }
            matched.sort((a, b) -> {
                int cmp = Integer.compare(b.score, a.score);
                return cmp != 0 ? cmp : a.name.compareToIgnoreCase(b.name);
            });
        }

        int displayCount = Math.min(matched.size(), 100);
        resultModel.clear();
        for (int i = 0; i < displayCount; i++) {
            resultModel.addElement(matched.get(i));
        }
        if (!resultModel.isEmpty()) {
            resultList.setSelectedIndex(0);
        }

        // Status bar — show what's ready vs still loading
        int custCount = custCache != null ? custCache.size() : 0;
        int coreCount = coreCache != null ? coreCache.size() : 0;
        int total = custCount + coreCount;
        String building = (custCacheBuilding || coreCacheBuilding) ? " (scanning\u2026)" : "";
        if (query.isEmpty()) {
            statusLabel.setText(total + " files indexed" + building);
        } else {
            statusLabel.setText(matched.size() + " results  \u00b7  " + total + " files" + building);
        }
    }

    // ── Open selected ──────────────────────────────────────────────────────
    private void openSelected() {
        FileEntry entry = resultList.getSelectedValue();
        if (entry == null) {
            return;
        }
        closeDialog();
        openFile(entry.absolutePath, entry.name);
    }

    private void openFile(String absolutePath, String displayName) {
        try {
            File file = FileUtil.normalizeFile(
                    new File(absolutePath.replace('/', File.separatorChar)));
            if (!file.exists()) {
                JOptionPane.showMessageDialog(null,
                        "File not found:\n" + absolutePath,
                        "Open Failed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            FileObject parentFo = FileUtil.toFileObject(file.getParentFile());
            if (parentFo != null) {
                parentFo.refresh();
            }
            FileObject fo = FileUtil.toFileObject(file);
            if (fo == null) {
                JOptionPane.showMessageDialog(null,
                        "Could not resolve file:\n" + absolutePath,
                        "Open Failed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            DataObject dob = DataObject.find(fo);
            OpenCookie oc = dob.getLookup().lookup(OpenCookie.class);
            if (oc != null) {
                oc.open();
            } else {
                javax.swing.Action action = dob.getNodeDelegate().getPreferredAction();
                if (action != null && action.isEnabled()) {
                    action.actionPerformed(
                            new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ""));
                }
            }
        } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Failed to open " + absolutePath + ": " + ex);
            JOptionPane.showMessageDialog(null,
                    "Could not open \u201C" + displayName + "\u201D:\n" + ex.getMessage(),
                    "Open Failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ── Positioning ────────────────────────────────────────────────────────
    private void centerOnOwner(Frame owner) {
        Rectangle ownerBounds = owner.getBounds();
        int x = ownerBounds.x + (ownerBounds.width - getWidth()) / 2;
        int y = ownerBounds.y + (ownerBounds.height - getHeight()) / 3; // slightly above center
        setLocation(x, y);
    }

    // ── Cell renderer ──────────────────────────────────────────────────────
    private static final class FileEntryRenderer extends JPanel
            implements ListCellRenderer<FileEntry> {

        // Badge colours — chosen to be visible on both light and dark themes
        private static final Color BADGE_CORE_BG      = new Color(0x1565C0); // deep blue
        private static final Color BADGE_GENERATED_BG = new Color(0x558B2F); // olive green
        private static final Color BADGE_FG            = Color.WHITE;

        private final JLabel nameLabel  = new JLabel();
        private final JLabel pathLabel  = new JLabel();
        private final JLabel badgeLabel = new JLabel();   // CORE / GEN badge

        FileEntryRenderer() {
            setLayout(new BorderLayout(0, 2));
            setBorder(new EmptyBorder(5, 12, 5, 12));

            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
            pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 10f));

            Color dimColor = UIManager.getColor("Label.disabledForeground");
            pathLabel.setForeground(dimColor != null ? dimColor : new Color(130, 130, 130));

            JPanel textPanel = new JPanel(new BorderLayout(0, 1));
            textPanel.setOpaque(false);
            textPanel.add(nameLabel, BorderLayout.NORTH);
            textPanel.add(pathLabel, BorderLayout.SOUTH);
            add(textPanel, BorderLayout.CENTER);

            // Left spacer (was icon placeholder)
            JLabel spacer = new JLabel();
            spacer.setPreferredSize(new Dimension(4, 20));
            add(spacer, BorderLayout.WEST);

            // Badge on the right — small rounded pill, sized to text
            // badgeLabel is NOT added to the layout — we paint it manually
            // in paintComponent() so it overlays the row without affecting sizing.
            badgeLabel.setFont(badgeLabel.getFont().deriveFont(Font.BOLD, 9f));
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends FileEntry> list, FileEntry value,
                int index, boolean isSelected, boolean cellHasFocus) {

            nameLabel.setText(value.name);
            pathLabel.setText(value.relativePath);

            // Configure badge
            switch (value.source) {
                case CORE:
                    badgeLabel.setText("CORE");
                    badgeLabel.setBackground(BADGE_CORE_BG);
                    break;
                case GENERATED:
                    badgeLabel.setText("GEN");
                    badgeLabel.setBackground(BADGE_GENERATED_BG);
                    break;
                default:
                    badgeLabel.setText("");
                    badgeLabel.setBackground(null);
                    break;
            }

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                nameLabel.setForeground(list.getSelectionForeground());
                Color selFg = list.getSelectionForeground();
                pathLabel.setForeground(blend(selFg,
                        list.getSelectionBackground(), 0.35f));
            } else {
                setBackground(list.getBackground());
                nameLabel.setForeground(list.getForeground());
                Color dim = UIManager.getColor("Label.disabledForeground");
                pathLabel.setForeground(dim != null ? dim : new Color(130, 130, 130));
            }

            setOpaque(true);
            revalidate();
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            String text = badgeLabel.getText();
            if (text == null || text.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // Measure the badge text
            Font badgeFont = badgeLabel.getFont();
            g2.setFont(badgeFont);
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(text);
            int textH = fm.getAscent();

            int padH = 3;   // vertical padding inside pill
            int padW = 6;   // horizontal padding inside pill
            int pillW = textW + padW * 2;
            int pillH = textH + padH * 2;

            // Position: vertically centred, 12px from right edge
            int x = getWidth() - pillW - 12;
            int y = (getHeight() - pillH) / 2;

            // Draw pill background
            g2.setColor(badgeLabel.getBackground());
            g2.fillRoundRect(x, y, pillW, pillH, pillH, pillH);

            // Draw text
            g2.setColor(BADGE_FG);
            g2.drawString(text, x + padW, y + padH + textH - 1);

            g2.dispose();
        }

        private static Color blend(Color a, Color b, float t) {
            int r = Math.round(a.getRed() + t * (b.getRed() - a.getRed()));
            int g = Math.round(a.getGreen() + t * (b.getGreen() - a.getGreen()));
            int bl = Math.round(a.getBlue() + t * (b.getBlue() - a.getBlue()));
            return new Color(
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, bl)));
        }
    }
}