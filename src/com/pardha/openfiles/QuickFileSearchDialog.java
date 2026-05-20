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
import org.netbeans.api.progress.ProgressHandle;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.windows.WindowManager;

/**
 * Ctrl+P quick file search popup for IFS Developer Studio.
 *
 * <p>
 * Press Ctrl+P → floating dialog appears → type filename → Enter or click
 * to open. Escape dismisses.
 *
 * <p>
 * <b>Search behaviour</b>: strict substring/prefix matching only (no fuzzy).
 * Results are ranked by IFS naming convention:
 * <ol>
 * <li>-Cust files (your customisation layer) — highest</li>
 * <li>Plain core files (no suffix)</li>
 * <li>-Base files</li>
 * <li>GEN (build output) files — lowest</li>
 * </ol>
 * Within each tier, prefix matches rank above contains matches.
 *
 * <p>
 * <b>Caching strategy</b>
 * <ul>
 * <li><b>Project layer</b> — scanned once per session, then monitored via a
 * {@code FileChangeListener}. Invalidated only when files are created or
 * deleted. Dialog always opens instantly showing the existing cache; any
 * rebuild runs silently in the background.</li>
 * <li><b>Core files</b> — scanned once per session, never rescanned
 * automatically. Multiple projects pointing to the same core root are
 * deduplicated via {@link #coreScannedRoots} — each unique path is walked
 * exactly once for the lifetime of the IDE session.</li>
 * </ul>
 *
 * <p>
 * Both caches are {@code static} — they survive dialog close/reopen.
 *
 * <p>
 * <b>Background pre-indexing</b>: call {@link #ensureIndexed()} from
 * {@code OpenFilesTopComponent.componentOpened()} so the caches are warm
 * before the user first presses Ctrl+P.
 */
public final class QuickFileSearchDialog extends JDialog {

   // ── Singleton ──────────────────────────────────────────────────────────
   private static QuickFileSearchDialog instance;

   public static void showDialog() {
      showDialog(null);
   }

   /**
    * Opens the dialog pre-filled with {@code prefill} text.
    * Called by {@link FindApiFileAction} after converting an API name.
    * Pass {@code null} or empty string for a blank search field.
    */
   public static void showDialog(String prefill) {
      if (instance == null || !instance.isDisplayable()) {
         Frame owner = WindowManager.getDefault().getMainWindow();
         instance = new QuickFileSearchDialog(owner);
      }
      instance.openAndFocus(prefill);
   }

   // ── IFS file extensions to index ──────────────────────────────────────
   // User-configurable via Settings dialog. Stored as CSV in PluginPrefs.
   // Falls back to PluginPrefs.INDEX_EXTENSIONS_DEFAULT if not set.
   // ── IFS property key for core files path ──────────────────────────────
   /**
    * The only project property key used to locate the IFS Core Files checkout.
    * Confirmed present in IFS Customisation Projects (nbproject/project.properties).
    */
   private static final String CORE_FILES_PROP = "project.ccs.corefiles";

   // ── Cache ──────────────────────────────────────────────────────────────
   // Both caches are STATIC — survive dialog close/reopen for the whole IDE session.
   // Core files (e.g. D:\IFS Workspace\...\checkout) — huge, change rarely.
   // Built once per session, never invalidated automatically.
   // coreScannedRoots ensures that multiple projects pointing to the same
   // core directory are NEVER walked more than once per IDE session.
   private static List<FileEntry> coreCache = null;
   private static final Set<String> coreScannedRoots = new HashSet<>();
   private static volatile boolean coreCacheBuilding = false;

   // Project layer files — built once, then invalidated only when a file is
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
   enum Source {
      PROJECT, CORE, GENERATED
   }

   static final class FileEntry {

      final String name;         // e.g. "CustomerOrder-Cust.entity"
      final String relativePath; // e.g. "order/model/order/CustomerOrder-Cust.entity"
      final String absolutePath;
      final Source source;
      int score;                 // set during each filter pass

      FileEntry(String name, String relativePath, String absolutePath, Source source) {
         this.name = name;
         this.relativePath = relativePath;
         this.absolutePath = absolutePath;
         this.source = source;
      }
   }

   // ── Background pre-indexing ────────────────────────────────────────────
   /**
    * Starts background indexing of both caches immediately, without opening
    * the dialog. Call this from {@code OpenFilesTopComponent.componentOpened()}
    * so that Ctrl+P is instant even on the first press of the session.
    *
    * <p>
    * Safe to call multiple times — each cache is only built once.
    * Does nothing if both caches are already built or currently building.
    */
   public static void ensureIndexed() {
      System.err.println("[QuickFileSearch] ensureIndexed called — "
              + "custReady=" + (custCache != null && !custCacheStale)
              + " coreReady=" + (coreCache != null));
      if (custCache == null && !custCacheBuilding) {
         buildCustCacheAsyncStatic();
      } else if (custCacheStale && !custCacheBuilding) {
         buildCustCacheAsyncStatic();
      }
      if (coreCache == null && !coreCacheBuilding) {
         buildCoreCacheAsyncStatic();
      }
   }

   /**
    * Marks the project cache as stale and clears it so the next
    * {@code ensureIndexed()} or Ctrl+P triggers a full rebuild.
    * Called from the Settings dialog when the indexed extensions change.
    */
   static void markCustCacheStale() {
      custCacheStale = true;
      custCache = null;
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

      JPanel body = new JPanel(new BorderLayout());
      body.setOpaque(false);
      body.add(new JSeparator(), BorderLayout.NORTH);
      body.add(center, BorderLayout.CENTER);
      root.add(body, BorderLayout.CENTER);

      // ── Document listener (live filter) ───────────────────────────────
      searchField.getDocument().addDocumentListener(new DocumentListener() {
         @Override
         public void insertUpdate(DocumentEvent e) {
            onQueryChanged();
         }

         @Override
         public void removeUpdate(DocumentEvent e) {
            onQueryChanged();
         }

         @Override
         public void changedUpdate(DocumentEvent e) {
            onQueryChanged();
         }
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
   private void openAndFocus(String prefill) {
      searchField.setText(prefill != null ? prefill : "");
      resultModel.clear();
      statusLabel.setText(" ");
      setVisible(true);
      toFront();
      searchField.requestFocusInWindow();
      if (prefill != null && !prefill.isEmpty()) {
         searchField.setCaretPosition(prefill.length());
      }
      ensureCache();
   }

   private void closeDialog() {
      setVisible(false);
      // Both caches are static — nothing to clear on close.
      // The cust watcher will mark custCacheStale if files change while closed.
   }

   // ── Cache management ───────────────────────────────────────────────────
   /**
    * Instance-level entry point called when the dialog opens.
    * Shows whatever is already cached immediately, then triggers background
    * builds for anything not yet ready.
    */
   private void ensureCache() {
      boolean coreReady = coreCache != null;
      boolean custReady = custCache != null && !custCacheStale;

      // Show whatever we have immediately — never block the UI
      if (custCache != null || coreCache != null) {
         onQueryChanged();
      }

      if (custReady && coreReady) {
         return;
      }

      if (!custReady && !custCacheBuilding) {
         buildCustCacheAsync();
      }
      if (!coreReady && !coreCacheBuilding) {
         buildCoreCacheAsync();
      }
   }

   // ── Project cache — instance version (updates statusLabel) ────────────
   private void buildCustCacheAsync() {
      custCacheBuilding = true;
      custCacheStale = false;
      if (custCache == null) {
         statusLabel.setText("Scanning project files\u2026");
      }
      new Thread(() -> {
         ProgressHandle ph = ProgressHandle.createHandle(
                 "Open Files: Indexing workspace\u2026");
         ph.start();
         try {
            List<String> roots = collectCustRootsStatic();
            List<FileEntry> entries = buildCustFilesStatic();
            SwingUtilities.invokeLater(() -> {
               custCache = entries;
               custCacheBuilding = false;
               registerCustWatcherStatic(roots);
               onQueryChanged();
            });
         } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
               custCacheBuilding = false;
               custCacheStale = true;
               statusLabel.setText("Project scan failed: " + ex.getMessage());
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CustIndexer").start();
   }

   // ── Project cache — static version (called from ensureIndexed) ────────
   private static void buildCustCacheAsyncStatic() {
      custCacheBuilding = true;
      custCacheStale = false;
      new Thread(() -> {
         ProgressHandle ph = ProgressHandle.createHandle(
                 "Open Files: Indexing workspace\u2026");
         ph.start();
         try {
            List<String> roots = collectCustRootsStatic();
            List<FileEntry> entries = buildCustFilesStatic();
            SwingUtilities.invokeLater(() -> {
               custCache = entries;
               custCacheBuilding = false;
               registerCustWatcherStatic(roots);
            });
         } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
               custCacheBuilding = false;
               custCacheStale = true;
               System.err.println("[QuickFileSearch] Background cust scan failed: " + ex);
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CustIndexer").start();
   }

   // ── Core cache — instance version (updates statusLabel) ───────────────
   private void buildCoreCacheAsync() {
      coreCacheBuilding = true;
      statusLabel.setText("Scanning core files (one-time)\u2026");
      new Thread(() -> {
         ProgressHandle ph = ProgressHandle.createHandle(
                 "Open Files: Indexing core files (one-time)\u2026");
         ph.start();
         try {
            List<String[]> roots = findCoreRootsStatic();
            if (roots.isEmpty()) {
               SwingUtilities.invokeLater(() -> {
                  if (coreCache == null) {
                     coreCache = new ArrayList<>();
                  }
                  coreCacheBuilding = false;
                  onQueryChanged();
               });
               return;
            }
            ph.switchToDeterminate(roots.size());
            List<FileEntry> entries = new ArrayList<>();
            int i = 0;
            for (String[] root : roots) {
               walkDirectory(new File(root[0]), root[0].replace('\\', '/'),
                       entries, Source.CORE);
               ph.progress(++i);
            }
            System.err.println("[QuickFileSearch] Core cache built: "
                    + entries.size() + " files");
            final List<FileEntry> finalEntries = entries;
            SwingUtilities.invokeLater(() -> {
               if (coreCache == null) {
                  coreCache = new ArrayList<>();
               }
               coreCache.addAll(finalEntries); // append — don't replace
               coreCacheBuilding = false;
               onQueryChanged();
            });
         } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
               if (coreCache == null) {
                  coreCache = new ArrayList<>();
               }
               coreCacheBuilding = false;
               statusLabel.setText("Core scan failed: " + ex.getMessage());
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CoreIndexer").start();
   }

   // ── Core cache — static version (called from ensureIndexed) ──────────
   private static void buildCoreCacheAsyncStatic() {
      coreCacheBuilding = true;
      new Thread(() -> {
         ProgressHandle ph = ProgressHandle.createHandle(
                 "Open Files: Indexing core files (one-time)\u2026");
         ph.start();
         try {
            List<String[]> roots = findCoreRootsStatic();
            if (!roots.isEmpty()) {
               ph.switchToDeterminate(roots.size());
               List<FileEntry> entries = new ArrayList<>();
               int i = 0;
               for (String[] root : roots) {
                  walkDirectory(new File(root[0]), root[0].replace('\\', '/'),
                          entries, Source.CORE);
                  ph.progress(++i);
               }
               System.err.println("[QuickFileSearch] Core cache built (background): "
                       + entries.size() + " files");
               final List<FileEntry> finalEntries = entries;
               SwingUtilities.invokeLater(() -> {
                  if (coreCache == null) {
                     coreCache = new ArrayList<>();
                  }
                  coreCache.addAll(finalEntries);
                  coreCacheBuilding = false;
               });
            } else {
               SwingUtilities.invokeLater(() -> {
                  if (coreCache == null) {
                     coreCache = new ArrayList<>();
                  }
                  coreCacheBuilding = false;
               });
            }
         } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
               if (coreCache == null) {
                  coreCache = new ArrayList<>();
               }
               coreCacheBuilding = false;
               System.err.println("[QuickFileSearch] Background core scan failed: " + ex);
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CoreIndexer").start();
   }

   // ── Project scanning helpers ───────────────────────────────────────────
   /**
    * Returns the workspace root paths of all open projects.
    * Fast — no file walking, just project root resolution.
    */
   private static List<String> collectCustRootsStatic() {
      List<String> roots = new ArrayList<>();
      try {
         org.netbeans.api.project.Project[] projects
                 = org.netbeans.api.project.ui.OpenProjects
                         .getDefault().getOpenProjects();
         for (org.netbeans.api.project.Project project : projects) {
            FileObject projectDir = project.getProjectDirectory();
            if (projectDir == null) {
               continue;
            }
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
    * Walks all open project directories and collects IFS source files.
    * Duplicate roots are skipped via a local seen set.
    */
   private static List<FileEntry> buildCustFilesStatic() {
      long start = System.currentTimeMillis();
      System.err.println("[QuickFileSearch] Project scan started");
      List<FileEntry> result = new ArrayList<>();
      Set<String> scannedRoots = new HashSet<>();

      org.netbeans.api.project.Project[] projects
              = org.netbeans.api.project.ui.OpenProjects
                      .getDefault().getOpenProjects();

      for (org.netbeans.api.project.Project project : projects) {
         FileObject projectDir = project.getProjectDirectory();
         if (projectDir == null) {
            continue;
         }
         File root = FileUtil.toFile(projectDir);
         if (root == null || !root.isDirectory()) {
            continue;
         }

         String rootPath = root.getAbsolutePath().replace('\\', '/');
         if (scannedRoots.add(rootPath)) {
            walkDirectory(root, rootPath, result, Source.PROJECT);
         }
      }
      System.err.println("[QuickFileSearch] Project scan done: "
              + result.size() + " files in "
              + (System.currentTimeMillis() - start) + "ms");
      return result;
   }

   /**
    * Returns a list of [absolutePath, absolutePath] pairs for all unique
    * Core Files roots found across open projects.
    *
    * <p>
    * Uses the static {@link #coreScannedRoots} set to ensure that if
    * multiple projects point to the same core checkout, it is returned
    * (and therefore walked) exactly once per IDE session — even across
    * multiple calls to this method.
    */
   private static List<String[]> findCoreRootsStatic() {
      List<String[]> roots = new ArrayList<>();

      org.netbeans.api.project.Project[] projects
              = org.netbeans.api.project.ui.OpenProjects
                      .getDefault().getOpenProjects();

      for (org.netbeans.api.project.Project project : projects) {
         FileObject projectDir = project.getProjectDirectory();
         if (projectDir == null) {
            continue;
         }
         File projRoot = FileUtil.toFile(projectDir);
         if (projRoot == null) {
            continue;
         }

         File coreRoot = readCoreFilesRoot(projRoot);
         if (coreRoot != null && coreRoot.isDirectory()) {
            String path = coreRoot.getAbsolutePath();
            if (coreScannedRoots.add(path)) {
               roots.add(new String[]{path, path});
            } else {
               System.err.println("[QuickFileSearch] Core root already indexed, skipping: "
                       + path);
            }
         }
      }
      return roots;
   }

   /**
    * Registers a {@code FileChangeListener} on each project root.
    * When any file is created or deleted, marks the project cache as stale.
    * The next Ctrl+P (or {@code ensureIndexed()} call) triggers a rebuild.
    */
   private static void registerCustWatcherStatic(List<String> roots) {
      // Unregister previous watcher
      if (custWatcher != null && custWatchedRoot != null) {
         try {
            FileObject fo = FileUtil.toFileObject(
                    FileUtil.normalizeFile(new File(custWatchedRoot)));
            if (fo != null) {
               fo.removeRecursiveListener(custWatcher);
            }
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] watcher unregister: " + ex);
         }
      }

      if (roots.isEmpty()) {
         return;
      }

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

      for (String rootPath : roots) {
         try {
            FileObject fo = FileUtil.toFileObject(
                    FileUtil.normalizeFile(new File(rootPath)));
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

   /**
    * Reads the IFS Core Files root directory from the project's
    * {@code nbproject/project.properties} file using the single confirmed
    * key {@value #CORE_FILES_PROP}.
    *
    * <p>
    * Returns {@code null} — without showing any dialog — when:
    * <ul>
    * <li>The property is not set (project has no core files — valid config).</li>
    * <li>The property value points to a path that does not exist or is not
    * a directory.</li>
    * </ul>
    * Both cases are logged once to {@code messages.log} via {@code System.err}.
    */
   private static File readCoreFilesRoot(File projectDir) {
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
            System.err.println("[QuickFileSearch] Could not read " + propsFile + ": " + ex);
            continue;
         }

         String val = props.getProperty(CORE_FILES_PROP);

         if (val == null || val.trim().isEmpty()) {
            System.err.println("[QuickFileSearch] " + CORE_FILES_PROP
                    + " not set in " + propsFile
                    + " — core file search disabled for this project.");
            return null;
         }

         File f = new File(val.trim());
         if (!f.isAbsolute()) {
            f = new File(projectDir, val.trim());
         }
         f = FileUtil.normalizeFile(f);

         if (!f.exists()) {
            System.err.println("[QuickFileSearch] Core files path does not exist: "
                    + f.getAbsolutePath() + " (from " + CORE_FILES_PROP + ")");
            return null;
         }
         if (!f.isDirectory()) {
            System.err.println("[QuickFileSearch] Core files path is not a directory: "
                    + f.getAbsolutePath());
            return null;
         }

         System.err.println("[QuickFileSearch] Core files root: " + f.getAbsolutePath());
         return f;
      }
      return null;
   }

   // ── Directory walker ───────────────────────────────────────────────────
   private static void walkDirectory(File dir, String rootPath,
           List<FileEntry> result, Source source) {
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
            // Skip <module>/server directories in both project and core layouts:
            //   workspace/<module>/server  (project layer)
            //   checkout/<module>/server   (core files)
            // These are compiled Java server output — not IFS source files.
            if (name.equals("server") && isModuleChild(f)) {
               continue;
            }
            // Files inside a /build/ directory are generated artifacts
            Source childSource = source;
            if (source == Source.PROJECT && name.equals("build")) {
               childSource = Source.GENERATED;
            }
            walkDirectory(f, rootPath, result, childSource);
         } else {
            int dot = name.lastIndexOf('.');
            if (dot < 0) {
               continue;
            }
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!PluginPrefs.getIndexExtensionSet().contains(ext)) {
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

   /**
    * Returns true when {@code dir} is a direct child of a module directory
    * that itself sits under a known IFS root folder ("workspace" or "checkout").
    *
    * <p>
    * Matches:
    * <pre>
    *   …/workspace/shpmnt/server   (project layer)
    *   …/checkout/crm/server       (core files)
    * </pre>
    * Does NOT match deeper paths like {@code …/workspace/shpmnt/source/x/server}.
    */
   private static boolean isModuleChild(File dir) {
      File module = dir.getParentFile();
      if (module == null) {
         return false;
      }
      File ifsRoot = module.getParentFile();
      if (ifsRoot == null) {
         return false;
      }
      String rootName = ifsRoot.getName().toLowerCase(Locale.ROOT);
      return rootName.equals("workspace") || rootName.equals("checkout");
   }

   private static String getBaseName(String fileName) {
      // Strip extension
      String base = fileName.contains(".")
              ? fileName.substring(0, fileName.lastIndexOf('.'))
              : fileName;
      // Strip -Cust / -Base suffix (case-insensitive)
      String lower = base.toLowerCase(Locale.ROOT);
      if (lower.endsWith("-cust") || lower.endsWith("-base")) {
         base = base.substring(0, base.length() - 5);
      }
      return base;
   }

   // ── Filtering ──────────────────────────────────────────────────────────
   private void onQueryChanged() {
      // Merge both caches — whichever are available right now
      List<FileEntry> allFiles = new ArrayList<>();
      if (custCache != null) {
         allFiles.addAll(custCache);
      }
      if (coreCache != null) {
         allFiles.addAll(coreCache);
      }

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
            String lowerName = entry.name.toLowerCase(Locale.ROOT);
            // Strip extension for matching
            String lowerBase = lowerName.contains(".")
                    ? lowerName.substring(0, lowerName.lastIndexOf('.'))
                    : lowerName;

            // Detect IFS naming convention suffixes
            boolean isCust = lowerBase.endsWith("-cust");
            boolean isBase = lowerBase.endsWith("-base");
            // Strip suffix so "customerorder" matches "CustomerOrder-Cust.entity"
            String matchBase = (isCust || isBase)
                    ? lowerBase.substring(0, lowerBase.length() - 5)
                    : lowerBase;

            // Tier scoring:
            //   -Cust (project layer)  ← highest
            //   plain (core, no suffix)
            //   -Base
            //   GEN (build output)     ← lowest
            int tierScore;
            if (entry.source == Source.GENERATED) {
               tierScore = 1;
            } else if (isCust) {
               tierScore = 1000;
            } else if (!isBase) {
               tierScore = 500;
            } else {
               tierScore = 10;
            }

            if (matchBase.startsWith(query)) {
               entry.score = tierScore + 10; // prefix bonus within tier
               matched.add(entry);
            } else if (matchBase.contains(query)) {
               entry.score = tierScore;
               matched.add(entry);
            }
         }
         matched.sort((a, b) -> {
            String baseA = getBaseName(a.name);
            String baseB = getBaseName(b.name);
            String lowerBaseA = baseA.toLowerCase(Locale.ROOT);
            String lowerBaseB = baseB.toLowerCase(Locale.ROOT);

            // Primary: prefix match base names before contains match base names
            boolean aPrefixMatch = lowerBaseA.startsWith(query);
            boolean bPrefixMatch = lowerBaseB.startsWith(query);
            if (aPrefixMatch && !bPrefixMatch) {
               return -1;
            }
            if (!aPrefixMatch && bPrefixMatch) {
               return 1;
            }

            // Secondary: group by base name alphabetically
            int nameCmp = baseA.compareToIgnoreCase(baseB);
            if (nameCmp != 0) {
               return nameCmp;
            }

            // Tertiary: within same base name, sort by tier (highest score first)
            int cmp = Integer.compare(b.score, a.score);
            if (cmp != 0) {
               return cmp;
            }

            // Quaternary: alphabetical by full filename
            return a.name.compareToIgnoreCase(b.name);
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

      // Status bar
      int custCount = custCache != null ? custCache.size() : 0;
      int coreCount = coreCache != null ? coreCache.size() : 0;
      int total = custCount + coreCount;
      String building = (custCacheBuilding || coreCacheBuilding) ? " (scanning\u2026)" : "";
      if (query.isEmpty()) {
         statusLabel.setText(total + " files indexed" + building);
      } else {
         statusLabel.setText(matched.size() + " results  \u00b7  "
                 + total + " files" + building);
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
      int y = ownerBounds.y + (ownerBounds.height - getHeight()) / 3;
      setLocation(x, y);
   }

   // ── Cell renderer ──────────────────────────────────────────────────────
   private static final class FileEntryRenderer extends JPanel
           implements ListCellRenderer<FileEntry> {

      private static final Color BADGE_CORE_BG = new Color(0x6A1B9A); // purple
      private static final Color BADGE_GENERATED_BG = new Color(0xE65100); // orange
      private static final Color BADGE_FG = Color.WHITE;

      private final JLabel nameLabel = new JLabel();
      private final JLabel pathLabel = new JLabel();
      private final JLabel badgeLabel = new JLabel();

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

         JLabel spacer = new JLabel();
         spacer.setPreferredSize(new Dimension(4, 20));
         add(spacer, BorderLayout.WEST);

         badgeLabel.setFont(badgeLabel.getFont().deriveFont(Font.BOLD, 9f));
      }

      @Override
      public Component getListCellRendererComponent(
              JList<? extends FileEntry> list, FileEntry value,
              int index, boolean isSelected, boolean cellHasFocus) {

         nameLabel.setText(value.name);
         pathLabel.setText(value.relativePath);

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
            pathLabel.setForeground(blend(selFg, list.getSelectionBackground(), 0.35f));
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
         if (text == null || text.isEmpty()) {
            return;
         }

         Graphics2D g2 = (Graphics2D) g.create();
         g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                 RenderingHints.VALUE_ANTIALIAS_ON);
         g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                 RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

         Font badgeFont = badgeLabel.getFont();
         g2.setFont(badgeFont);
         FontMetrics fm = g2.getFontMetrics();
         int textW = fm.stringWidth(text);
         int textH = fm.getAscent();

         int padH = 3;
         int padW = 6;
         int pillW = textW + padW * 2;
         int pillH = textH + padH * 2;

         int x = getWidth() - pillW - 12;
         int y = (getHeight() - pillH) / 2;

         g2.setColor(badgeLabel.getBackground());
         g2.fillRoundRect(x, y, pillW, pillH, pillH, pillH);

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
