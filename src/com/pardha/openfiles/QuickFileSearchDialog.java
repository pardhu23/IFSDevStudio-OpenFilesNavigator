package com.pardha.openfiles;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.windows.WindowManager;

/**
 * Ctrl+P quick file search popup for IFS Developer Studio.
 *
 * <h3>Three-tier cache</h3>
 * <ul>
 *   <li><b>custCache</b>  — {@code workspace/} files. Incrementally updated via watcher.</li>
 *   <li><b>buildCache</b> — {@code build/} files. Debounced rebuild after IFS code-gen bursts.</li>
 *   <li><b>coreCache</b>  — {@code checkout/} files. Rebuilt only when core roots change.</li>
 * </ul>
 */
public final class QuickFileSearchDialog extends JDialog {

   // =========================================================================
   // Enums / inner types
   // =========================================================================

   enum Source { PROJECT, CORE, GENERATED }

   static final class FileEntry {
      final String name;
      final String relativePath;
      final String absolutePath;
      final Source source;
      int score;

      FileEntry(String name, String relativePath, String absolutePath, Source source) {
         this.name         = name;
         this.relativePath = relativePath;
         this.absolutePath = absolutePath;
         this.source       = source;
      }
   }

   /** Resolved subdirectory roots for one open NetBeans project. */
   private static final class ProjectRoots {
      final String rootPath;     // forward-slash, no trailing slash
      final File   workspaceDir; // may be null
      final File   buildDir;     // may be null
      final File   coreDir;      // from project.ccs.corefiles, may be null

      ProjectRoots(String rootPath, File workspaceDir, File buildDir, File coreDir) {
         this.rootPath     = rootPath;
         this.workspaceDir = workspaceDir;
         this.buildDir     = buildDir;
         this.coreDir      = coreDir;
      }
   }

   // =========================================================================
   // Singleton
   // =========================================================================

   private static QuickFileSearchDialog instance;

   public static void showDialog() {
      showDialog(null);
   }

   /** Opens the dialog pre-filled with {@code prefill} (pass null for blank). */
   public static void showDialog(String prefill) {
      if (instance == null || !instance.isDisplayable()) {
         Frame owner = WindowManager.getDefault().getMainWindow();
         instance = new QuickFileSearchDialog(owner);
      }
      instance.openAndFocus(prefill);
   }

   // =========================================================================
   // Constants
   // =========================================================================

   private static final String CORE_FILES_PROP   = "project.ccs.corefiles";
   private static final int    BUILD_DEBOUNCE_MS  = 3000;

   // =========================================================================
   // Cache state
   // =========================================================================

   // ── Cust cache (workspace/) ───────────────────────────────────────────────
   private static List<FileEntry>  custCache         = null;
   private static volatile boolean custCacheBuilding  = false;
   private static volatile boolean custCacheStale     = true;

   // ── Build cache (build/) ──────────────────────────────────────────────────
   private static List<FileEntry>  buildCache         = null;
   private static volatile boolean buildCacheBuilding  = false;

   // ── Core cache (checkout/) ────────────────────────────────────────────────
   private static List<FileEntry>  coreCache          = null;
   private static volatile boolean coreCacheBuilding   = false;

   /**
    * Core root paths the current coreCache was built from.
    * Compared against live project roots to detect staleness on project switch.
    */
   private static final Set<String> cachedCoreRoots  = new HashSet<>();

   /**
    * Snapshot of roots being built in the active core-indexer thread.
    * Used to detect a project change that arrives mid-build.
    */
   private static Set<String>       pendingCoreRoots = null;

   // ── Merged cache ──────────────────────────────────────────────────────────
   /** Pre-merged view of all three caches. Re-used on every keystroke — no per-keypress copy. */
   private static volatile List<FileEntry> mergedCache      = Collections.emptyList();
   private static volatile boolean         mergedCacheStale = true;

   /** Wall-clock start time for the current indexing run — used in total-time log. */
   private static long indexingStartedAt = 0;

   // ── Watchers ──────────────────────────────────────────────────────────────
   private static org.openide.filesystems.FileChangeListener custWatcher   = null;
   private static org.openide.filesystems.FileChangeListener buildWatcher  = null;

   private static final List<String>      custWatchedRoots   = new ArrayList<>();
   private static final List<String>      buildWatchedRoots  = new ArrayList<>();
   private static       javax.swing.Timer buildDebounceTimer = null;

   /** Guards incremental add/remove mutations to custCache from watcher callbacks. */
   private static final Object CACHE_LOCK = new Object();

   // ── Shared ForkJoinPool ───────────────────────────────────────────────────
   /**
    * Created once and reused across all scans.
    * Avoids ~200ms thread-creation overhead on Windows per walkParallel call.
    */
   private static final ForkJoinPool WALK_POOL = new ForkJoinPool(
           Math.min(4, Runtime.getRuntime().availableProcessors()));

   // =========================================================================
   // Public API
   // =========================================================================

   /**
    * Pre-indexes all three caches in the background.
    * Call from {@code OpenFilesTopComponent.componentOpened()}.
    * Safe to call multiple times — skips caches already built or building.
    */
   public static void ensureIndexed() {
      indexingStartedAt = System.currentTimeMillis();
      System.err.println("[QuickFileSearch] ensureIndexed — "
              + "cust="   + cacheState(custCache,  custCacheBuilding,  custCacheStale)
              + " build=" + cacheState(buildCache, buildCacheBuilding, false)
              + " core="  + cacheState(coreCache,  coreCacheBuilding,  false));

      if ((custCache == null || custCacheStale) && !custCacheBuilding) {
         buildCustCacheAsync(null);
      }
      if (buildCache == null && !buildCacheBuilding) {
         buildBuildCacheAsync(null);
      }
      if (coreCache == null && !coreCacheBuilding) {
         buildCoreCacheAsync(null);
      }
   }

   /**
    * Seeds the initial core-roots snapshot before the first ensureIndexed() call.
    * Call once from OpenFilesTopComponent.componentOpened(), before ensureIndexed().
    */
   public static void seedInitialCoreRoots() {
      if (cachedCoreRoots.isEmpty()) {
         cachedCoreRoots.addAll(computeActiveCoreRoots());
         System.err.println("[QuickFileSearch] Initial core roots: " + cachedCoreRoots);
      }
   }

   /**
    * Called by OpenFilesTopComponent when the set of open projects changes.
    * Always rebuilds cust + build caches. Rebuilds core only when roots differ.
    */
   public static void onOpenProjectsChanged() {
      Set<String> neededRoots = computeActiveCoreRoots();

      custCache        = null;
      custCacheStale   = true;
      buildCache       = null;
      mergedCacheStale = true;
      unregisterWatchers();

      if (!custCacheBuilding)  buildCustCacheAsync(null);
      if (!buildCacheBuilding) buildBuildCacheAsync(null);

      if (neededRoots.equals(cachedCoreRoots)) {
         System.err.println("[QuickFileSearch] Core roots unchanged — skipping core rebuild");
         return;
      }

      System.err.println("[QuickFileSearch] Core roots changed."
              + " Old=" + cachedCoreRoots + " New=" + neededRoots);

      coreCache        = null;
      mergedCacheStale = true;
      cachedCoreRoots.clear();
      cachedCoreRoots.addAll(neededRoots);

      if (!coreCacheBuilding) buildCoreCacheAsync(null);
   }

   /**
    * Wipes all three caches and rebuilds from scratch.
    * onComplete is called on the EDT when all three finish. Pass null if unneeded.
    */
   public static void refreshAllCachesStatic(Runnable onComplete) {
      unregisterWatchers();
      custCache        = null;
      custCacheStale   = true;
      buildCache       = null;
      coreCache        = null;
      mergedCache      = Collections.emptyList();
      mergedCacheStale = true;
      cachedCoreRoots.clear();
      cachedCoreRoots.addAll(computeActiveCoreRoots());
      indexingStartedAt = System.currentTimeMillis();

      int[] pending = {3};
      Runnable onOneDone = () -> {
         pending[0]--;
         if (pending[0] == 0 && onComplete != null) {
            onComplete.run();
         }
      };

      buildCustCacheAsync(onOneDone);
      buildBuildCacheAsync(onOneDone);
      buildCoreCacheAsync(onOneDone);
   }

   /** Marks cust + build caches stale. Called from Settings when indexed extensions change. */
   static void markCustCacheStale() {
      custCacheStale   = true;
      custCache        = null;
      buildCache       = null;
      mergedCacheStale = true;
   }

   // =========================================================================
   // Cache builders
   // =========================================================================

   private static void buildCustCacheAsync(Runnable onDone) {
      custCacheBuilding = true;
      custCacheStale    = false;

      new Thread(() -> {
         long           start        = System.currentTimeMillis();
         ProgressHandle ph           = ProgressHandle.createHandle("Open Files: Indexing workspace\u2026");
         ph.start();
         try {
            Set<String>        exts          = PluginPrefs.getIndexExtensionSet();
            List<ProjectRoots> projectRoots  = collectProjectRoots();
            List<FileEntry>    entries       = new ArrayList<>();
            List<String>       workspaceDirs = new ArrayList<>();
            List<String>       buildDirs     = new ArrayList<>();

            for (ProjectRoots pr : projectRoots) {
               if (pr.workspaceDir != null && pr.workspaceDir.isDirectory()) {
                  walkParallel(pr.workspaceDir, pr.rootPath, entries, Source.PROJECT, exts);
                  workspaceDirs.add(pr.workspaceDir.getAbsolutePath());
               }
               if (pr.buildDir != null && pr.buildDir.isDirectory()) {
                  buildDirs.add(pr.buildDir.getAbsolutePath());
               }
            }

            System.err.println("[QuickFileSearch] Cust scan done: "
                    + entries.size() + " files in "
                    + (System.currentTimeMillis() - start) + "ms");

            SwingUtilities.invokeLater(() -> {
               custCache         = new ArrayList<>(entries);
               custCacheBuilding = false;
               registerCustWatcher(workspaceDirs, exts);
               registerBuildWatcher(buildDirs);
               rebuildMergedCache();
               if (onDone != null) onDone.run();
            });
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Cust scan failed: " + ex);
            SwingUtilities.invokeLater(() -> {
               custCacheBuilding = false;
               custCacheStale    = true;
               if (onDone != null) onDone.run();
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CustIndexer").start();
   }

   private static void buildBuildCacheAsync(Runnable onDone) {
      buildCacheBuilding = true;

      new Thread(() -> {
         long           start = System.currentTimeMillis();
         ProgressHandle ph    = ProgressHandle.createHandle("Open Files: Indexing build output\u2026");
         ph.start();
         try {
            Set<String>        exts         = PluginPrefs.getIndexExtensionSet();
            List<ProjectRoots> projectRoots = collectProjectRoots();
            List<FileEntry>    entries      = new ArrayList<>();

            for (ProjectRoots pr : projectRoots) {
               if (pr.buildDir != null && pr.buildDir.isDirectory()) {
                  walkParallel(pr.buildDir, pr.rootPath, entries, Source.GENERATED, exts);
               }
            }

            System.err.println("[QuickFileSearch] Build scan done: "
                    + entries.size() + " files in "
                    + (System.currentTimeMillis() - start) + "ms");

            SwingUtilities.invokeLater(() -> {
               buildCache         = new ArrayList<>(entries);
               buildCacheBuilding = false;
               rebuildMergedCache();
               if (onDone != null) onDone.run();
            });
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Build scan failed: " + ex);
            SwingUtilities.invokeLater(() -> {
               buildCacheBuilding = false;
               if (onDone != null) onDone.run();
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-BuildIndexer").start();
   }

   private static void buildCoreCacheAsync(Runnable onDone) {
      coreCacheBuilding = true;
      Set<String> snapshotRoots = new HashSet<>(cachedCoreRoots);
      pendingCoreRoots = snapshotRoots;

      new Thread(() -> {
         long           start = System.currentTimeMillis();
         ProgressHandle ph    = ProgressHandle.createHandle("Open Files: Indexing core files\u2026");
         ph.start();
         try {
            Set<String>     exts    = PluginPrefs.getIndexExtensionSet();
            List<FileEntry> entries = new ArrayList<>();

            for (String rootPath : snapshotRoots) {
               File rootDir = new File(rootPath);
               if (rootDir.isDirectory()) {
                  walkParallel(rootDir, rootPath, entries, Source.CORE, exts);
               }
            }

            System.err.println("[QuickFileSearch] Core scan done: "
                    + entries.size() + " files from " + snapshotRoots
                    + " in " + (System.currentTimeMillis() - start) + "ms");

            SwingUtilities.invokeLater(() -> {
               // If roots changed again while building, restart
               if (!snapshotRoots.equals(cachedCoreRoots)) {
                  System.err.println("[QuickFileSearch] Core roots changed during build — restarting");
                  coreCacheBuilding = false;
                  pendingCoreRoots  = null;
                  buildCoreCacheAsync(onDone);
                  return;
               }
               coreCache         = entries;
               coreCacheBuilding = false;
               pendingCoreRoots  = null;
               rebuildMergedCache();
               if (onDone != null) onDone.run();
            });
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Core scan failed: " + ex);
            SwingUtilities.invokeLater(() -> {
               coreCacheBuilding = false;
               pendingCoreRoots  = null;
               if (onDone != null) onDone.run();
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CoreIndexer").start();
   }

   // =========================================================================
   // Merged cache
   // =========================================================================

   /** Rebuilds mergedCache from all three caches. Must be called on EDT. */
   private static void rebuildMergedCache() {
      int cap = (custCache  != null ? custCache.size()  : 0)
              + (buildCache != null ? buildCache.size() : 0)
              + (coreCache  != null ? coreCache.size()  : 0);

      List<FileEntry> merged = new ArrayList<>(cap);
      if (custCache  != null) merged.addAll(custCache);
      if (buildCache != null) merged.addAll(buildCache);
      if (coreCache  != null) merged.addAll(coreCache);

      mergedCache      = merged;
      mergedCacheStale = false;

      if (custCache != null && buildCache != null && coreCache != null) {
         System.err.println("[QuickFileSearch] All caches ready — total: "
                 + (System.currentTimeMillis() - indexingStartedAt) + "ms | "
                 + "cust="    + custCache.size()
                 + " build="  + buildCache.size()
                 + " core="   + coreCache.size()
                 + " merged=" + mergedCache.size());
      }

      if (instance != null && instance.isVisible()) {
         instance.onQueryChanged();
      }
   }

   // =========================================================================
   // Watchers
   // =========================================================================

   /**
    * Registers an incremental watcher on each workspace/ directory.
    * File created/deleted → single entry added/removed, no full rebuild.
    */
   private static void registerCustWatcher(List<String> workspaceDirs, Set<String> exts) {
      unregisterCustWatcher();
      if (workspaceDirs.isEmpty()) return;

      custWatcher = new org.openide.filesystems.FileChangeAdapter() {
         @Override
         public void fileDataCreated(org.openide.filesystems.FileEvent fe) {
            java.io.File f = FileUtil.toFile(fe.getFile());
            if (f == null) return;
            String ext = getExtension(f.getName());
            if (!exts.contains(ext)) return;

            String abs      = f.getAbsolutePath().replace('\\', '/');
            String rootPath = findWatchedRoot(abs, custWatchedRoots);
            String rel      = rootPath != null && abs.startsWith(rootPath)
                    ? abs.substring(rootPath.length() + 1) : abs;

            FileEntry entry = new FileEntry(f.getName(), rel, abs, Source.PROJECT);
            synchronized (CACHE_LOCK) {
               if (custCache != null) custCache.add(entry);
            }
            SwingUtilities.invokeLater(() -> {
               mergedCacheStale = true;
               rebuildMergedCache();
            });
         }

         @Override
         public void fileDeleted(org.openide.filesystems.FileEvent fe) {
            java.io.File f = FileUtil.toFile(fe.getFile());
            if (f == null) return;
            String abs = f.getAbsolutePath().replace('\\', '/');
            synchronized (CACHE_LOCK) {
               if (custCache != null) custCache.removeIf(e -> e.absolutePath.equals(abs));
            }
            SwingUtilities.invokeLater(() -> {
               mergedCacheStale = true;
               rebuildMergedCache();
            });
         }
      };

      for (String dirPath : workspaceDirs) {
         try {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(dirPath)));
            if (fo != null) {
               fo.addRecursiveListener(custWatcher);
               custWatchedRoots.add(dirPath.replace('\\', '/'));
               System.err.println("[QuickFileSearch] Watching workspace: " + dirPath);
            }
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] custWatcher register failed: " + ex);
         }
      }
   }

   /**
    * Registers a debounced watcher on each build/ directory.
    * IFS code-gen bursts are coalesced into a single rebuild after
    * BUILD_DEBOUNCE_MS ms of silence.
    */
   private static void registerBuildWatcher(List<String> buildDirs) {
      unregisterBuildWatcher();
      if (buildDirs.isEmpty()) return;

      buildWatcher = new org.openide.filesystems.FileChangeAdapter() {
         @Override public void fileDataCreated(org.openide.filesystems.FileEvent fe)   { debounceBuildRebuild(); }
         @Override public void fileFolderCreated(org.openide.filesystems.FileEvent fe) { debounceBuildRebuild(); }
         @Override public void fileDeleted(org.openide.filesystems.FileEvent fe)       { debounceBuildRebuild(); }
      };

      for (String dirPath : buildDirs) {
         try {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(dirPath)));
            if (fo != null) {
               fo.addRecursiveListener(buildWatcher);
               buildWatchedRoots.add(dirPath.replace('\\', '/'));
               System.err.println("[QuickFileSearch] Watching build: " + dirPath);
            }
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] buildWatcher register failed: " + ex);
         }
      }
   }

   /** Restarts a debounce timer on every build-directory event. */
   private static void debounceBuildRebuild() {
      SwingUtilities.invokeLater(() -> {
         if (buildDebounceTimer != null) {
            buildDebounceTimer.restart();
         } else {
            buildDebounceTimer = new javax.swing.Timer(BUILD_DEBOUNCE_MS, e -> {
               buildDebounceTimer = null;
               if (!buildCacheBuilding) {
                  System.err.println("[QuickFileSearch] Build debounce fired — rebuilding");
                  buildBuildCacheAsync(null);
               }
            });
            buildDebounceTimer.setRepeats(false);
            buildDebounceTimer.start();
         }
      });
   }

   private static void unregisterCustWatcher() {
      if (custWatcher == null) return;
      for (String path : custWatchedRoots) {
         try {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
            if (fo != null) fo.removeRecursiveListener(custWatcher);
         } catch (Exception ignored) {}
      }
      custWatcher = null;
      custWatchedRoots.clear();
   }

   private static void unregisterBuildWatcher() {
      if (buildWatcher == null) return;
      for (String path : buildWatchedRoots) {
         try {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
            if (fo != null) fo.removeRecursiveListener(buildWatcher);
         } catch (Exception ignored) {}
      }
      buildWatcher = null;
      buildWatchedRoots.clear();
   }

   private static void unregisterWatchers() {
      unregisterCustWatcher();
      unregisterBuildWatcher();
      if (buildDebounceTimer != null) {
         buildDebounceTimer.stop();
         buildDebounceTimer = null;
      }
   }

   // =========================================================================
   // Project root helpers
   // =========================================================================

   private static List<ProjectRoots> collectProjectRoots() {
      List<ProjectRoots> result = new ArrayList<>();
      Set<String>        seen   = new HashSet<>();
      try {
         for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            FileObject projectDir = project.getProjectDirectory();
            if (projectDir == null) continue;
            File root = FileUtil.toFile(projectDir);
            if (root == null || !root.isDirectory()) continue;

            String rootPath = root.getAbsolutePath().replace('\\', '/');
            if (!seen.add(rootPath)) continue;

            File workspace = new File(root, "workspace");

            // Skip non-IFS projects (e.g. the plugin project itself has no workspace/)
            if (!workspace.isDirectory()) {
               System.err.println("[QuickFileSearch] Skipping non-IFS project: " + rootPath);
               continue;
            }

            File build = new File(root, "build");
            File core  = readCoreFilesRoot(root);

            result.add(new ProjectRoots(
                    rootPath,
                    workspace,
                    build.isDirectory() ? build : null,
                    core));
         }
      } catch (Exception ex) {
         System.err.println("[QuickFileSearch] collectProjectRoots: " + ex);
      }
      return result;
   }

   /** Returns the set of core root paths across all open projects (deduplicated). */
   private static Set<String> computeActiveCoreRoots() {
      Set<String> roots = new HashSet<>();
      try {
         for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            FileObject projectDir = project.getProjectDirectory();
            if (projectDir == null) continue;
            File projRoot = FileUtil.toFile(projectDir);
            if (projRoot == null) continue;
            File coreRoot = readCoreFilesRoot(projRoot);
            if (coreRoot != null && coreRoot.isDirectory()) {
               roots.add(coreRoot.getAbsolutePath());
            }
         }
      } catch (Exception ex) {
         System.err.println("[QuickFileSearch] computeActiveCoreRoots: " + ex);
      }
      return roots;
   }

   /**
    * Reads project.ccs.corefiles from nbproject/project.properties.
    * Returns null when absent or path does not exist.
    */
   private static File readCoreFilesRoot(File projectDir) {
      File[] candidates = {
         new File(projectDir, "nbproject/project.properties"),
         new File(projectDir, "project.properties")
      };
      for (File propsFile : candidates) {
         if (!propsFile.exists()) continue;
         java.util.Properties props = new java.util.Properties();
         try (java.io.FileInputStream fis = new java.io.FileInputStream(propsFile)) {
            props.load(fis);
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Could not read " + propsFile + ": " + ex);
            continue;
         }
         String val = props.getProperty(CORE_FILES_PROP);
         if (val == null || val.trim().isEmpty()) continue;
         File f = new File(val.trim());
         if (!f.isAbsolute()) f = new File(projectDir, val.trim());
         f = FileUtil.normalizeFile(f);
         if (f.exists() && f.isDirectory()) return f;
         System.err.println("[QuickFileSearch] Core path invalid: " + f);
      }
      return null;
   }

   // =========================================================================
   // Parallel directory walker
   // =========================================================================

   /**
    * Walks dir in parallel using the shared WALK_POOL.
    * exts is passed in once — not re-fetched per file.
    * Uses Collections.synchronizedList (O(n) adds) instead of
    * CopyOnWriteArrayList (O(n²) on large trees).
    */
   private static void walkParallel(File dir, String rootPath,
           List<FileEntry> result, Source source, Set<String> exts) {
      List<FileEntry> concurrent = Collections.synchronizedList(new ArrayList<>());
      String normRoot = rootPath.replace('\\', '/');
      WALK_POOL.invoke(new WalkTask(dir, normRoot, concurrent, source, exts));
      result.addAll(concurrent);
   }

   private static final class WalkTask extends RecursiveAction {

      private final File            dir;
      private final String          rootPath;
      private final List<FileEntry> result;
      private final Source          source;
      private final Set<String>     exts;

      WalkTask(File dir, String rootPath, List<FileEntry> result,
               Source source, Set<String> exts) {
         this.dir      = dir;
         this.rootPath = rootPath;
         this.result   = result;
         this.source   = source;
         this.exts     = exts;
      }

      @Override
      protected void compute() {
         File[] children = dir.listFiles();
         if (children == null) return;

         List<WalkTask> subTasks = new ArrayList<>();

         for (File f : children) {
            if (f.isHidden()) continue;
            String name = f.getName();

            if (f.isDirectory()) {
               if (shouldSkipDir(name, f)) continue;
               Source childSource = (source == Source.PROJECT && name.equals("build"))
                       ? Source.GENERATED : source;
               subTasks.add(new WalkTask(f, rootPath, result, childSource, exts));
            } else {
               String ext = getExtension(name);
               if (!exts.contains(ext)) continue;
               String abs = f.getAbsolutePath().replace('\\', '/');
               String rel = abs.startsWith(rootPath)
                       ? abs.substring(rootPath.length() + 1) : abs;
               result.add(new FileEntry(name, rel, abs, source));
            }
         }

         if (!subTasks.isEmpty()) invokeAll(subTasks);
      }
   }

   private static boolean shouldSkipDir(String name, File dir) {
      switch (name) {
         case ".git": case ".svn": case "node_modules":
         case "target": case ".idea": case "nbproject":
            return true;
      }
      if (name.equals("server") && isModuleChild(dir)) return true;
      return false;
   }

   /** Returns true when dir is a direct child of workspace/ or checkout/. */
   private static boolean isModuleChild(File dir) {
      File module  = dir.getParentFile();
      if (module == null) return false;
      File ifsRoot = module.getParentFile();
      if (ifsRoot == null) return false;
      String rootName = ifsRoot.getName().toLowerCase(Locale.ROOT);
      return rootName.equals("workspace") || rootName.equals("checkout");
   }

   // =========================================================================
   // Utilities
   // =========================================================================

   private static String getExtension(String fileName) {
      int dot = fileName.lastIndexOf('.');
      return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
   }

   private static String findWatchedRoot(String absPath, List<String> roots) {
      for (String root : roots) {
         if (absPath.startsWith(root)) return root;
      }
      return null;
   }

   private static String getBaseName(String fileName) {
      String base  = fileName.contains(".")
              ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
      String lower = base.toLowerCase(Locale.ROOT);
      if (lower.endsWith("-cust") || lower.endsWith("-base")) {
         base = base.substring(0, base.length() - 5);
      }
      return base;
   }

   private static String cacheState(List<?> cache, boolean building, boolean stale) {
      if (building)      return "building";
      if (cache == null) return "null";
      if (stale)         return "stale(" + cache.size() + ")";
      return "ok(" + cache.size() + ")";
   }

   // =========================================================================
   // UI fields
   // =========================================================================

   private final JTextField              searchField = new JTextField();
   private final DefaultListModel<FileEntry> resultModel = new DefaultListModel<>();
   private final JList<FileEntry>        resultList  = new JList<>(resultModel);
   private final JLabel                  statusLabel = new JLabel(" ");

   // =========================================================================
   // Construction
   // =========================================================================

   private QuickFileSearchDialog(Frame owner) {
      super(owner, false); // non-modal
      setUndecorated(true);
      buildUI();
      pack();
      setSize(560, 420);
      centerOnOwner(owner);

      addWindowFocusListener(new WindowAdapter() {
         @Override
         public void windowLostFocus(WindowEvent e) {
            if (e.getOppositeWindow() != QuickFileSearchDialog.this) closeDialog();
         }
      });

      getRootPane().registerKeyboardAction(
              e -> closeDialog(),
              KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
              JComponent.WHEN_IN_FOCUSED_WINDOW);
   }

   private void buildUI() {
      JPanel root = new JPanel(new BorderLayout(0, 0));
      root.setBorder(new LineBorder(
              UIManager.getColor("Separator.foreground") != null
              ? UIManager.getColor("Separator.foreground")
              : new Color(160, 160, 160), 1));
      root.setBackground(UIManager.getColor("Panel.background"));
      setContentPane(root);

      // ── Top bar: icon + search field + refresh button ─────────────────
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

      JButton refreshBtn = new JButton("\u21BB"); // ↻
      refreshBtn.setFocusable(false);
      refreshBtn.setFont(refreshBtn.getFont().deriveFont(13f));
      refreshBtn.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
      refreshBtn.setOpaque(false);
      refreshBtn.setContentAreaFilled(false);
      refreshBtn.setToolTipText("Refresh file index (clears all caches)");
      refreshBtn.addActionListener(e -> refreshAllCaches());
      topPanel.add(refreshBtn, BorderLayout.EAST);

      root.add(topPanel, BorderLayout.NORTH);

      // ── Results list ──────────────────────────────────────────────────
      resultList.setCellRenderer(new FileEntryRenderer());
      resultList.setFixedCellHeight(42);
      resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
      resultList.setBackground(UIManager.getColor("List.background"));
      resultList.setBorder(new EmptyBorder(2, 0, 2, 0));
      resultList.setFocusable(false);
      resultList.addMouseListener(new MouseAdapter() {
         @Override
         public void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) openSelected();
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

      // ── Document listener — live filter ───────────────────────────────
      searchField.getDocument().addDocumentListener(new DocumentListener() {
         @Override public void insertUpdate(DocumentEvent e)  { onQueryChanged(); }
         @Override public void removeUpdate(DocumentEvent e)  { onQueryChanged(); }
         @Override public void changedUpdate(DocumentEvent e) { onQueryChanged(); }
      });

      // ── Keyboard navigation ───────────────────────────────────────────
      searchField.addKeyListener(new KeyAdapter() {
         @Override
         public void keyPressed(KeyEvent e) {
            int sel  = resultList.getSelectedIndex();
            int size = resultModel.getSize();
            switch (e.getKeyCode()) {
               case KeyEvent.VK_DOWN:
                  if (sel < size - 1) { resultList.setSelectedIndex(sel + 1); resultList.ensureIndexIsVisible(sel + 1); }
                  e.consume(); break;
               case KeyEvent.VK_UP:
                  if (sel > 0) { resultList.setSelectedIndex(sel - 1); resultList.ensureIndexIsVisible(sel - 1); }
                  e.consume(); break;
               case KeyEvent.VK_ENTER:
                  openSelected(); e.consume(); break;
               case KeyEvent.VK_ESCAPE:
                  closeDialog();  e.consume(); break;
            }
         }
      });
   }

   // =========================================================================
   // Open / close / refresh
   // =========================================================================

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
      ensureCacheForDialog();
   }

   private void closeDialog() {
      setVisible(false);
   }

   /** Delegates to the static refresh so logic lives in one place. */
   private void refreshAllCaches() {
      statusLabel.setText("Refreshing\u2026");
      resultModel.clear();
      refreshAllCachesStatic(() -> {
         statusLabel.setText("Refresh complete \u2014 " + mergedCache.size() + " files indexed");
         onQueryChanged();
      });
   }

   /** Shows whatever is already cached immediately, then triggers builds for anything not ready. */
   private void ensureCacheForDialog() {
      onQueryChanged();

      boolean custReady  = custCache  != null && !custCacheStale;
      boolean buildReady = buildCache != null;
      boolean coreReady  = coreCache  != null;

      if (custReady && buildReady && coreReady) return;

      Runnable refresh = this::onQueryChanged;

      if (!custReady && !custCacheBuilding) {
         statusLabel.setText("Scanning workspace\u2026");
         buildCustCacheAsync(refresh);
      }
      if (!buildReady && !buildCacheBuilding) {
         buildBuildCacheAsync(refresh);
      }
      if (!coreReady && !coreCacheBuilding) {
         if (statusLabel.getText().equals(" ")) {
            statusLabel.setText("Scanning core files (one-time)\u2026");
         }
         buildCoreCacheAsync(refresh);
      }
   }

   // =========================================================================
   // Query / filter
   // =========================================================================

   private void onQueryChanged() {
      List<FileEntry> allFiles = mergedCache; // single reference — no copy

      if (allFiles.isEmpty() && (custCacheBuilding || coreCacheBuilding || buildCacheBuilding)) {
         resultModel.clear();
         statusLabel.setText("Scanning files\u2026");
         return;
      }

      String          query   = searchField.getText().trim().toLowerCase(Locale.ROOT);
      List<FileEntry> matched = new ArrayList<>();

      if (query.isEmpty()) {
         matched.addAll(allFiles);
      } else {
         for (FileEntry entry : allFiles) {
            String lowerName = entry.name.toLowerCase(Locale.ROOT);
            String lowerBase = lowerName.contains(".")
                    ? lowerName.substring(0, lowerName.lastIndexOf('.')) : lowerName;

            boolean isCust    = lowerBase.endsWith("-cust");
            boolean isBase    = lowerBase.endsWith("-base");
            String  matchBase = (isCust || isBase)
                    ? lowerBase.substring(0, lowerBase.length() - 5) : lowerBase;

            int tierScore;
            if      (entry.source == Source.GENERATED) tierScore = 1;
            else if (isCust)                           tierScore = 1000;
            else if (!isBase)                          tierScore = 500;
            else                                       tierScore = 10;

            if      (matchBase.startsWith(query)) { entry.score = tierScore + 10; matched.add(entry); }
            else if (matchBase.contains(query))   { entry.score = tierScore;      matched.add(entry); }
            else if (lowerBase.startsWith(query)) { entry.score = tierScore + 10; matched.add(entry); }
            else if (lowerBase.contains(query))   { entry.score = tierScore;      matched.add(entry); }
            else if (lowerName.startsWith(query)) { entry.score = tierScore + 10; matched.add(entry); }
            else if (lowerName.contains(query))   { entry.score = tierScore;      matched.add(entry); }
         }

         matched.sort((a, b) -> {
            String baseA   = getBaseName(a.name);
            String baseB   = getBaseName(b.name);
            String lowerA  = a.name.toLowerCase(Locale.ROOT);
            String lowerB  = b.name.toLowerCase(Locale.ROOT);
            String lowerBa = baseA.toLowerCase(Locale.ROOT);
            String lowerBb = baseB.toLowerCase(Locale.ROOT);

            boolean aExact = lowerA.equals(query),        bExact = lowerB.equals(query);
            if (aExact != bExact) return aExact ? -1 : 1;

            boolean aFP = lowerA.startsWith(query),       bFP = lowerB.startsWith(query);
            if (aFP != bFP) return aFP ? -1 : 1;

            boolean aBP = lowerBa.startsWith(query),      bBP = lowerBb.startsWith(query);
            if (aBP != bBP) return aBP ? -1 : 1;

            int nc = baseA.compareToIgnoreCase(baseB);
            if (nc != 0) return nc;

            int tc = Integer.compare(b.score, a.score);
            if (tc != 0) return tc;

            return a.name.compareToIgnoreCase(b.name);
         });
      }

      int displayCount = Math.min(matched.size(), 100);
      resultModel.clear();
      for (int i = 0; i < displayCount; i++) resultModel.addElement(matched.get(i));
      if (!resultModel.isEmpty()) resultList.setSelectedIndex(0);

      // Status bar
      int custCount  = custCache  != null ? custCache.size()  : 0;
      int buildCount = buildCache != null ? buildCache.size() : 0;
      int coreCount  = coreCache  != null ? coreCache.size()  : 0;
      int total      = custCount + buildCount + coreCount;
      String building = (custCacheBuilding || coreCacheBuilding || buildCacheBuilding)
              ? " (scanning\u2026)" : "";
      statusLabel.setText(query.isEmpty()
              ? total + " files indexed" + building
              : matched.size() + " results  \u00b7  " + total + " files" + building);
   }

   // =========================================================================
   // Open selected file
   // =========================================================================

   private void openSelected() {
      FileEntry entry = resultList.getSelectedValue();
      if (entry == null) return;
      closeDialog();
      openFile(entry.absolutePath, entry.name);
   }

   private void openFile(String absolutePath, String displayName) {
      try {
         File file = FileUtil.normalizeFile(
                 new File(absolutePath.replace('/', File.separatorChar)));
         if (!file.exists()) {
            JOptionPane.showMessageDialog(null,
                    "File not found:\n" + absolutePath, "Open Failed", JOptionPane.WARNING_MESSAGE);
            return;
         }
         FileObject parentFo = FileUtil.toFileObject(file.getParentFile());
         if (parentFo != null) parentFo.refresh();

         FileObject fo = FileUtil.toFileObject(file);
         if (fo == null) {
            JOptionPane.showMessageDialog(null,
                    "Could not resolve file:\n" + absolutePath, "Open Failed", JOptionPane.WARNING_MESSAGE);
            return;
         }
         DataObject dob = DataObject.find(fo);
         OpenCookie oc  = dob.getLookup().lookup(OpenCookie.class);
         if (oc != null) {
            oc.open();
         } else {
            javax.swing.Action action = dob.getNodeDelegate().getPreferredAction();
            if (action != null && action.isEnabled()) {
               action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ""));
            }
         }
      } catch (Exception ex) {
         System.err.println("[QuickFileSearch] Failed to open " + absolutePath + ": " + ex);
         JOptionPane.showMessageDialog(null,
                 "Could not open \u201C" + displayName + "\u201D:\n" + ex.getMessage(),
                 "Open Failed", JOptionPane.WARNING_MESSAGE);
      }
   }

   // =========================================================================
   // Positioning
   // =========================================================================

   private void centerOnOwner(Frame owner) {
      Rectangle b = owner.getBounds();
      setLocation(b.x + (b.width  - getWidth())  / 2,
                  b.y + (b.height - getHeight()) / 3);
   }

   // =========================================================================
   // Cell renderer
   // =========================================================================

   private static final class FileEntryRenderer extends JPanel
           implements ListCellRenderer<FileEntry> {

      private static final Color BADGE_CORE_BG      = new Color(0x6A1B9A);
      private static final Color BADGE_GENERATED_BG = new Color(0xE65100);
      private static final Color BADGE_FG           = Color.WHITE;

      private final JLabel nameLabel  = new JLabel();
      private final JLabel pathLabel  = new JLabel();
      private final JLabel badgeLabel = new JLabel();

      FileEntryRenderer() {
         setLayout(new BorderLayout(0, 2));
         setBorder(new EmptyBorder(5, 12, 5, 12));
         nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
         pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 10f));
         Color dim = UIManager.getColor("Label.disabledForeground");
         pathLabel.setForeground(dim != null ? dim : new Color(130, 130, 130));

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
               badgeLabel.setText("CORE"); badgeLabel.setBackground(BADGE_CORE_BG);      break;
            case GENERATED:
               badgeLabel.setText("GEN");  badgeLabel.setBackground(BADGE_GENERATED_BG); break;
            default:
               badgeLabel.setText("");     badgeLabel.setBackground(null);
         }

         if (isSelected) {
            setBackground(list.getSelectionBackground());
            nameLabel.setForeground(list.getSelectionForeground());
            pathLabel.setForeground(blend(list.getSelectionForeground(),
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
         g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
         g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

         g2.setFont(badgeLabel.getFont());
         FontMetrics fm    = g2.getFontMetrics();
         int textW = fm.stringWidth(text), textH = fm.getAscent();
         int padH = 3, padW = 6;
         int pillW = textW + padW * 2, pillH = textH + padH * 2;
         int x = getWidth() - pillW - 12, y = (getHeight() - pillH) / 2;

         g2.setColor(badgeLabel.getBackground());
         g2.fillRoundRect(x, y, pillW, pillH, pillH, pillH);
         g2.setColor(BADGE_FG);
         g2.drawString(text, x + padW, y + padH + textH - 1);
         g2.dispose();
      }

      private static Color blend(Color a, Color b, float t) {
         return new Color(
                 Math.max(0, Math.min(255, Math.round(a.getRed()   + t * (b.getRed()   - a.getRed())))),
                 Math.max(0, Math.min(255, Math.round(a.getGreen() + t * (b.getGreen() - a.getGreen())))),
                 Math.max(0, Math.min(255, Math.round(a.getBlue()  + t * (b.getBlue()  - a.getBlue())))));
      }
   }
}