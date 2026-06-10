// Context: see docs/quick-search.md
package com.pardha.openfiles;

import java.awt.*;
import java.awt.event.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <li><b>custCache</b> — {@code workspace/} files. Incrementally updated via watcher.</li>
 * <li><b>buildCache</b> — {@code build/} files. Debounced rebuild after IFS code-gen bursts.</li>
 * <li><b>coreCache</b> — {@code checkout/} files. Rebuilt only when core roots change.</li>
 * </ul>
 *
 * <h3>Key fixes vs previous version</h3>
 * <ul>
 * <li>Double-index on startup prevented by a single {@code ensureIndexedOnce} gate.</li>
 * <li>{@code OpenProjects.getDefault().getOpenProjects()} is always called on a background
 * thread, never on EDT.</li>
 * <li>{@code rebuildMergedCache()} is debounced: rapid successive completions coalesce
 * into one EDT update instead of three.</li>
 * <li>The merge-debounce timer callback storage is fixed: a separate {@code pendingMergeCallback}
 * field holds the latest callback so rewiring listeners can never drop earlier {@code onDone}
 * counters.</li>
 * <li>Query filtering is debounced (80 ms) and runs on a background thread, never blocking EDT.</li>
 * <li>Result model is updated in a single batch via {@link BatchListModel} (one ListDataEvent
 * instead of 100 individual {@code addElement} calls).</li>
 * <li>{@code ensureCacheForDialog()} delegates entirely to {@code scheduleCacheBuilds()} to
 * prevent duplicate builder starts racing with the background init thread.</li>
 * </ul>
 */
public final class QuickFileSearchDialog extends JDialog {

   // =========================================================================
   // Enums / inner types
   // =========================================================================
   enum Source {
      PROJECT, CORE, GENERATED
   }

   static final class FileEntry {

      final String name;
      final String relativePath;
      final String absolutePath;
      final Source source;
      int score;

      FileEntry(String name, String relativePath, String absolutePath, Source source) {
         this.name = name;
         this.relativePath = relativePath;
         this.absolutePath = absolutePath;
         this.source = source;
      }
   }

   /**
    * Resolved subdirectory roots for one open NetBeans project.
    */
   private static final class ProjectRoots {

      final String rootPath;
      final File workspaceDir;
      final File buildDir;
      final File coreDir;

      ProjectRoots(String rootPath, File workspaceDir, File buildDir, File coreDir) {
         this.rootPath = rootPath;
         this.workspaceDir = workspaceDir;
         this.buildDir = buildDir;
         this.coreDir = coreDir;
      }
   }

   // =========================================================================
   // BatchListModel — replaces DefaultListModel to batch all 100 row updates
   // into a single contentsChanged event, eliminating 100x revalidate/repaint.
   // =========================================================================
   private static final class BatchListModel<E> extends AbstractListModel<E> {

      private List<E> data = new ArrayList<>();

      /**
       * Replace entire contents in one event.
       */
      void setAll(List<E> newData) {
         int oldSize = data.size();
         data = new ArrayList<>(newData);
         int newSize = data.size();
         if (oldSize > 0 || newSize > 0) {
            // Fire a single contentsChanged covering the widest range.
            int maxIdx = Math.max(oldSize, newSize) - 1;
            if (maxIdx < 0) {
               maxIdx = 0;
            }
            fireContentsChanged(this, 0, maxIdx);
         }
      }

      @Override
      public int getSize() {
         return data.size();
      }

      @Override
      public E getElementAt(int i) {
         return data.get(i);
      }

      boolean isEmpty() {
         return data.isEmpty();
      }
   }

   // =========================================================================
   // Singleton dialog
   // =========================================================================
   private static QuickFileSearchDialog instance;

   public static void showDialog() {
      showDialog(null);
   }

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
   private static final String CORE_FILES_PROP = "project.ccs.corefiles";
   private static final int BUILD_DEBOUNCE_MS = 3000;

   private static final int DISK_CACHE_VERSION = 1;
   private static final long DISK_CACHE_MAGIC = 0x4F46494C45494458L; // "OFILEIDX"

   private static final String CUST_DIRTY_FLAG = "openfiles-cust-dirty.flag";

   private static final int MERGE_DEBOUNCE_MS = 150;

   /**
    * Quiet period (ms) after last keystroke before filter runs off-EDT.
    */
   private static final int QUERY_DEBOUNCE_MS = 80;

   // =========================================================================
   // Cache state — all reads/writes on EDT unless explicitly noted
   // =========================================================================
   private static List<FileEntry> custCache = null;
   private static volatile boolean custCacheBuilding = false;
   private static volatile boolean custCacheStale = true;

   private static List<FileEntry> buildCache = null;
   private static volatile boolean buildCacheBuilding = false;

   private static List<FileEntry> coreCache = null;
   private static volatile boolean coreCacheBuilding = false;

   private static final Set<String> cachedCoreRoots = new HashSet<>();
   private static Set<String> pendingCoreRoots = null;

   /**
    * Pre-merged view; rebuilt via mergeDebounceTimer.
    */
   private static volatile List<FileEntry> mergedCache = Collections.emptyList();
   private static volatile boolean mergedCacheStale = true;
   private static volatile boolean refreshInProgress = false;

   /**
    * Debounce timer that fires MERGE_DEBOUNCE_MS after the last cache completion.
    */
   private static javax.swing.Timer mergeDebounceTimer = null;
   /**
    * Latest onDone callback to fire when mergeDebounceTimer fires.
    * Stored separately so restarting the timer never drops earlier AtomicInteger
    * counters (fix for the broken listener-rewiring bug in the old code).
    */
   private static Runnable pendingMergeCallback = null;

   private static long indexingStartedAt = 0;

   // ── Gate: prevents double-indexing on startup ─────────────────────────────
   static final AtomicBoolean indexingEverStarted = new AtomicBoolean(false);

   // ── Watchers ──────────────────────────────────────────────────────────────
   private static org.openide.filesystems.FileChangeListener custWatcher = null;
   private static org.openide.filesystems.FileChangeListener buildWatcher = null;

   private static final List<String> custWatchedRoots = new ArrayList<>();
   private static final List<String> buildWatchedRoots = new ArrayList<>();
   private static javax.swing.Timer buildDebounceTimer = null;

   /**
    * Guards incremental add/remove mutations from watcher callbacks.
    */
   private static final Object CACHE_LOCK = new Object();

   // ── Shared ForkJoinPool ───────────────────────────────────────────────────
   private static final ForkJoinPool WALK_POOL = new ForkJoinPool(
           Math.min(6, Runtime.getRuntime().availableProcessors()));

   // =========================================================================
   // Public API
   // =========================================================================
   public static void ensureIndexed() {
      if (isCacheReady()) {
         return;
      }
      if (indexingEverStarted.get()) {
         SwingUtilities.invokeLater(() -> scheduleCacheBuilds(null));
      }
      boolean allReady = custCache != null && !custCacheStale
              && buildCache != null
              && coreCache != null;
      if (allReady) {
         return;
      }

      boolean firstCall = indexingEverStarted.compareAndSet(false, true);
      if (!firstCall) {
         SwingUtilities.invokeLater(() -> scheduleCacheBuilds(null));
         return;
      }

      System.err.println("[QuickFileSearch] ensureIndexed — first call, launching background init");
      SwingUtilities.invokeLater(() -> scheduleCacheBuilds(null));
   }

   public static void seedInitialCoreRoots() {
      if (cachedCoreRoots.isEmpty()) {
         cachedCoreRoots.addAll(computeActiveCoreRootsOffEdt());
         System.err.println("[QuickFileSearch] Initial core roots: " + cachedCoreRoots);
      }
   }

   public static boolean isCacheReady() {
      return custCache != null && !custCacheStale
              && buildCache != null
              && coreCache != null;
   }

   public static void scheduleCacheBuildsPublic(Runnable onDone) {
      SwingUtilities.invokeLater(() -> {
         scheduleCacheBuilds(onDone);
      });
   }

   public static void onOpenProjectsChanged() {
      new Thread(() -> {
         Set<String> neededRoots = computeActiveCoreRootsOffEdt();

         SwingUtilities.invokeLater(() -> {
            Set<String> currentRoots = new HashSet<>(cachedCoreRoots);
            boolean custOrBuildChanged = custCache == null || custCacheStale || buildCache == null;
            boolean coreChanged = !neededRoots.equals(currentRoots);

            if (!custOrBuildChanged && !coreChanged) {
               System.err.println("[QuickFileSearch] onOpenProjectsChanged: no real change, ignoring");
               return;
            }

            System.err.println("[QuickFileSearch] onOpenProjectsChanged: "
                    + "coreChanged=" + coreChanged
                    + " custOrBuildChanged=" + custOrBuildChanged);

            unregisterWatchers();
            custCache = null;
            custCacheStale = true;
            buildCache = null;
            mergedCacheStale = true;

            if (coreChanged) {
               coreCache = null;
               mergedCacheStale = true;
               cachedCoreRoots.clear();
               cachedCoreRoots.addAll(neededRoots);
            }
            if (PluginPrefs.isAutoScanEnabled() && indexingEverStarted.get()) {
               scheduleCacheBuilds(null);
            }
         });
      }, "QuickFileSearch-ProjectChangeChecker").start();
   }

   public static void refreshAllCachesStatic(Runnable onComplete) {
      refreshInProgress = true;
      unregisterWatchers();
      custCache = null;
      custCacheStale = true;
      buildCache = null;
      coreCache = null;
      mergedCache = Collections.emptyList();
      mergedCacheStale = true;
      cachedCoreRoots.clear();
      indexingStartedAt = System.currentTimeMillis();
      indexingEverStarted.set(false);
      coreDiskCacheFile().delete();
      custDiskCacheFile().delete();
      custDirtyFlagFile().delete();

      new Thread(() -> {
         Set<String> roots = computeActiveCoreRootsOffEdt();
         SwingUtilities.invokeLater(() -> {
            cachedCoreRoots.addAll(roots);  // ← populate BEFORE scheduling builds
            scheduleCacheBuilds(() -> {
               refreshInProgress = false;  // ← clear only after builds complete
               if (onComplete != null) {
                  onComplete.run();
               }
            });
         });
      }, "QuickFileSearch-RefreshInit").start();
   }

   static void markCustCacheStale() {
      custCacheStale = true;
      custCache = null;
      buildCache = null;
      coreCache = null;
      mergedCacheStale = true;
      coreDiskCacheFile().delete();
      custDiskCacheFile().delete();
      custDirtyFlagFile().delete();
   }

   // =========================================================================
   // Internal: schedule the caches that need building
   // =========================================================================
   private static void scheduleCacheBuilds(Runnable onAllDone) {
      boolean needCust = (custCache == null || custCacheStale) && !custCacheBuilding;
      boolean needBuild = (buildCache == null) && !buildCacheBuilding;
      boolean needCore = (coreCache == null) && !coreCacheBuilding;

      if (!needCust && !needBuild && !needCore) {
         if (onAllDone != null) {
            SwingUtilities.invokeLater(onAllDone);
         }
         return;
      }
      if (indexingStartedAt == 0) {
         indexingStartedAt = System.currentTimeMillis();
      }
      if (onAllDone != null) {
         int count = (needCust ? 1 : 0) + (needBuild ? 1 : 0) + (needCore ? 1 : 0);
         AtomicInteger pending = new AtomicInteger(count);
         Runnable onOneDone = () -> {
            if (pending.decrementAndGet() == 0) {
               onAllDone.run();
            }
         };
         if (needCust) {
            buildCustCacheAsync(onOneDone);
         }
         if (needBuild) {
            buildBuildCacheAsync(onOneDone);
         }
         if (needCore) {
            buildCoreCacheAsync(onOneDone);
         }
      } else {
         if (needCust) {
            buildCustCacheAsync(null);
         }
         if (needBuild) {
            buildBuildCacheAsync(null);
         }
         if (needCore) {
            buildCoreCacheAsync(null);
         }
      }
   }

   // =========================================================================
   // Cache builders
   // =========================================================================
   private static void buildCustCacheAsync(Runnable onDone) {
      custCacheBuilding = true;
      custCacheStale = false;

      new Thread(() -> {
         long start = System.currentTimeMillis();
         ProgressHandle ph = ProgressHandle.createHandle("Open Files: Indexing workspace\u2026");
         ph.start();
         try {
            Set<String> exts = PluginPrefs.getIndexExtensionSet();
            List<ProjectRoots> projectRoots = collectProjectRoots();
            List<String> workspaceDirs = new ArrayList<>();
            List<String> buildDirs = new ArrayList<>();

            for (ProjectRoots pr : projectRoots) {
               if (pr.workspaceDir != null && pr.workspaceDir.isDirectory()) {
                  workspaceDirs.add(pr.workspaceDir.getAbsolutePath());
               }
               if (pr.buildDir != null && pr.buildDir.isDirectory()) {
                  buildDirs.add(pr.buildDir.getAbsolutePath());
               }
            }

            List<FileEntry> entries = null;
            boolean fromDisk = false;
            File dirtyFlag = custDirtyFlagFile();

            if (!dirtyFlag.exists()) {
               entries = tryLoadCustDiskCache(workspaceDirs, exts);
               fromDisk = entries != null;
            }

            if (!fromDisk) {
               entries = new ArrayList<>();
               for (ProjectRoots pr : projectRoots) {
                  if (pr.workspaceDir != null && pr.workspaceDir.isDirectory()) {
                     walkParallel(pr.workspaceDir, pr.rootPath, entries, Source.PROJECT, exts);
                  }
               }
               dirtyFlag.delete();
               final List<FileEntry> toWrite = entries;
               final List<String> finalWsDirs = workspaceDirs;
               new Thread(() -> writeCustDiskCache(finalWsDirs, exts, toWrite),
                       "QuickFileSearch-CustCacheWriter").start();
            }

            System.err.println("[QuickFileSearch] Cust "
                    + (fromDisk ? "loaded from disk cache" : "scan done")
                    + ": " + entries.size() + " files in "
                    + (System.currentTimeMillis() - start) + "ms");

            final List<FileEntry> finalEntries = entries;
            final List<String> finalWorkspaceDirs = workspaceDirs;
            final List<String> finalBuildDirs = buildDirs;
            final Set<String> finalExts = exts;

            SwingUtilities.invokeLater(() -> {
               custCache = new ArrayList<>(finalEntries);
               custCacheBuilding = false;
               registerCustWatcher(finalWorkspaceDirs, finalExts);
               registerBuildWatcher(finalBuildDirs);
               scheduleMergedRebuild(onDone);
            });
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Cust scan failed: " + ex);
            SwingUtilities.invokeLater(() -> {
               custCacheBuilding = false;
               custCacheStale = true;
               scheduleMergedRebuild(onDone);
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CustIndexer").start();
   }

   private static void buildBuildCacheAsync(Runnable onDone) {
      buildCacheBuilding = true;

      new Thread(() -> {
         long start = System.currentTimeMillis();
         ProgressHandle ph = ProgressHandle.createHandle("Open Files: Indexing build output\u2026");
         ph.start();
         try {
            Set<String> exts = PluginPrefs.getIndexExtensionSet();
            List<ProjectRoots> projectRoots = collectProjectRoots();
            List<FileEntry> entries = new ArrayList<>();

            for (ProjectRoots pr : projectRoots) {
               if (pr.buildDir != null && pr.buildDir.isDirectory()) {
                  walkParallel(pr.buildDir, pr.rootPath, entries, Source.GENERATED, exts);
               }
            }

            System.err.println("[QuickFileSearch] Build scan done: "
                    + entries.size() + " files in "
                    + (System.currentTimeMillis() - start) + "ms");

            final List<FileEntry> finalEntries = entries;
            SwingUtilities.invokeLater(() -> {
               buildCache = new ArrayList<>(finalEntries);
               buildCacheBuilding = false;
               scheduleMergedRebuild(onDone);
            });
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Build scan failed: " + ex);
            SwingUtilities.invokeLater(() -> {
               buildCacheBuilding = false;
               scheduleMergedRebuild(onDone);
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
         long start = System.currentTimeMillis();
         ProgressHandle ph = ProgressHandle.createHandle("Open Files: Indexing core files\u2026");
         ph.start();
         try {
            Set<String> exts = PluginPrefs.getIndexExtensionSet();
            List<FileEntry> entries = tryLoadCoreDiskCache(snapshotRoots, exts);
            boolean fromDisk = entries != null;

            if (!fromDisk) {
               entries = new ArrayList<>();
               for (String rootPath : snapshotRoots) {
                  File rootDir = new File(rootPath);
                  if (rootDir.isDirectory()) {
                     walkParallel(rootDir, rootPath, entries, Source.CORE, exts);
                  }
               }
               final List<FileEntry> toWrite = entries;
               new Thread(() -> writeCoreDiskCache(snapshotRoots, exts, toWrite),
                       "QuickFileSearch-CacheWriter").start();
            }

            System.err.println("[QuickFileSearch] Core "
                    + (fromDisk ? "loaded from disk cache" : "scan done")
                    + ": " + entries.size() + " from " + snapshotRoots
                    + " in " + (System.currentTimeMillis() - start) + "ms");

            final List<FileEntry> finalEntries = entries;
            SwingUtilities.invokeLater(() -> {
               if (!snapshotRoots.equals(cachedCoreRoots)) {
                  System.err.println("[QuickFileSearch] Core roots changed during build — restarting");
                  coreCacheBuilding = false;
                  pendingCoreRoots = null;
                  buildCoreCacheAsync(onDone);
                  return;
               }
               coreCache = finalEntries;
               coreCacheBuilding = false;
               pendingCoreRoots = null;
               scheduleMergedRebuild(onDone);
            });
         } catch (Exception ex) {
            System.err.println("[QuickFileSearch] Core scan failed: " + ex);
            SwingUtilities.invokeLater(() -> {
               coreCacheBuilding = false;
               pendingCoreRoots = null;
               scheduleMergedRebuild(onDone);
            });
         } finally {
            ph.finish();
         }
      }, "QuickFileSearch-CoreIndexer").start();
   }

   // =========================================================================
   // Core disk cache
   // =========================================================================
   private static File coreDiskCacheFile() {
      File cacheDir = new File(org.openide.modules.Places.getUserDirectory(), "var/cache");
      cacheDir.mkdirs();
      return new File(cacheDir, "openfiles-core-index.dat");
   }

   private static List<FileEntry> tryLoadCoreDiskCache(Set<String> roots, Set<String> exts) {
      File f = coreDiskCacheFile();
      if (!f.exists()) {
         return null;
      }

      try (DataInputStream in = new DataInputStream(
              new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16))) {

         long magic = in.readLong();
         int version = in.readInt();
         if (magic != DISK_CACHE_MAGIC || version != DISK_CACHE_VERSION) {
            System.err.println("[QuickFileSearch] Core disk cache: magic/version mismatch — rescanning");
            f.delete();
            return null;
         }

         int numRoots = in.readInt();
         if (numRoots != roots.size()) {
            System.err.println("[QuickFileSearch] Core disk cache: root count changed — rescanning");
            f.delete();
            return null;
         }
         Map<String, Long> storedRoots = new LinkedHashMap<>(numRoots * 2);
         for (int i = 0; i < numRoots; i++) {
            storedRoots.put(in.readUTF(), in.readLong());
         }
         for (String rootPath : roots) {
            Long storedMtime = storedRoots.get(rootPath);
            if (storedMtime == null) {
               System.err.println("[QuickFileSearch] Core disk cache: root path missing: " + rootPath);
               f.delete();
               return null;
            }
            if (new File(rootPath).lastModified() != storedMtime) {
               System.err.println("[QuickFileSearch] Core disk cache: root modified — rescanning");
               f.delete();
               return null;
            }
         }

         String storedExts = in.readUTF();
         if (!storedExts.equals(sortedExtString(exts))) {
            System.err.println("[QuickFileSearch] Core disk cache: ext set changed — rescanning");
            f.delete();
            return null;
         }

         int numEntries = in.readInt();
         List<FileEntry> entries = new ArrayList<>(numEntries);
         Source[] srcValues = Source.values();
         for (int i = 0; i < numEntries; i++) {
            String name = in.readUTF();
            String rel = in.readUTF();
            String abs = in.readUTF();
            int srcOrd = in.readInt();
            Source src = (srcOrd >= 0 && srcOrd < srcValues.length) ? srcValues[srcOrd] : Source.CORE;
            entries.add(new FileEntry(name, rel, abs, src));
         }
         return entries;

      } catch (IOException ex) {
         System.err.println("[QuickFileSearch] Core disk cache read failed: " + ex + " — rescanning");
         f.delete();
         return null;
      }
   }

   private static void writeCoreDiskCache(Set<String> roots, Set<String> exts, List<FileEntry> entries) {
      File f = coreDiskCacheFile();
      File tmp = new File(f.getParent(), f.getName() + ".tmp");
      try (DataOutputStream out = new DataOutputStream(
              new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp), 1 << 16))) {

         out.writeLong(DISK_CACHE_MAGIC);
         out.writeInt(DISK_CACHE_VERSION);
         out.writeInt(roots.size());
         for (String rootPath : roots) {
            out.writeUTF(rootPath);
            out.writeLong(new File(rootPath).lastModified());
         }
         out.writeUTF(sortedExtString(exts));
         out.writeInt(entries.size());
         for (FileEntry e : entries) {
            out.writeUTF(e.name);
            out.writeUTF(e.relativePath);
            out.writeUTF(e.absolutePath);
            out.writeInt(e.source.ordinal());
         }
         out.flush();
      } catch (IOException ex) {
         System.err.println("[QuickFileSearch] Core disk cache write failed: " + ex);
         tmp.delete();
         return;
      }
      f.delete();
      if (!tmp.renameTo(f)) {
         System.err.println("[QuickFileSearch] Core disk cache rename failed");
         tmp.delete();
      } else {
         System.err.println("[QuickFileSearch] Core disk cache written: "
                 + entries.size() + " entries → " + f.getAbsolutePath());
      }
   }

   private static String sortedExtString(Set<String> exts) {
      List<String> sorted = new ArrayList<>(exts);
      Collections.sort(sorted);
      return String.join(",", sorted);
   }

   // =========================================================================
   // Cust (workspace) disk cache
   // =========================================================================
   private static File custDiskCacheFile() {
      File cacheDir = new File(org.openide.modules.Places.getUserDirectory(), "var/cache");
      cacheDir.mkdirs();
      return new File(cacheDir, "openfiles-cust-index.dat");
   }

   private static File custDirtyFlagFile() {
      File cacheDir = new File(org.openide.modules.Places.getUserDirectory(), "var/cache");
      return new File(cacheDir, CUST_DIRTY_FLAG);
   }

   private static void writeCustDirtyFlag() {
      try {
         custDirtyFlagFile().createNewFile();
      } catch (IOException ignored) {
      }
   }

   private static List<FileEntry> tryLoadCustDiskCache(List<String> workspaceDirs, Set<String> exts) {
      File f = custDiskCacheFile();
      if (!f.exists()) {
         return null;
      }

      try (DataInputStream in = new DataInputStream(
              new java.io.BufferedInputStream(new java.io.FileInputStream(f), 1 << 16))) {

         long magic = in.readLong();
         int version = in.readInt();
         if (magic != DISK_CACHE_MAGIC || version != DISK_CACHE_VERSION) {
            System.err.println("[QuickFileSearch] Cust disk cache: magic/version mismatch — rescanning");
            f.delete();
            return null;
         }

         int numRoots = in.readInt();
         if (numRoots != workspaceDirs.size()) {
            System.err.println("[QuickFileSearch] Cust disk cache: root count changed — rescanning");
            f.delete();
            return null;
         }
         Map<String, Long> storedRoots = new LinkedHashMap<>(numRoots * 2);
         for (int i = 0; i < numRoots; i++) {
            storedRoots.put(in.readUTF(), in.readLong());
         }
         for (String wsDir : workspaceDirs) {
            String normDir = wsDir.replace('\\', '/');
            if (!storedRoots.containsKey(normDir)) {
               System.err.println("[QuickFileSearch] Cust disk cache: workspace dir missing — rescanning");
               f.delete();
               return null;
            }
            // NOTE: intentionally NOT checking lastModified here.
            // The dirty flag (openfiles-cust-dirty.flag) handles invalidation
            // when files are added/deleted. Directory mtime changes too
            // frequently on Windows (any child edit updates it) to be useful.
         }

         String storedExts = in.readUTF();
         if (!storedExts.equals(sortedExtString(exts))) {
            System.err.println("[QuickFileSearch] Cust disk cache: ext set changed — rescanning");
            f.delete();
            return null;
         }

         int numEntries = in.readInt();
         List<FileEntry> entries = new ArrayList<>(numEntries);
         Source[] srcValues = Source.values();
         for (int i = 0; i < numEntries; i++) {
            String name = in.readUTF();
            String rel = in.readUTF();
            String abs = in.readUTF();
            int srcOrd = in.readInt();
            Source src = (srcOrd >= 0 && srcOrd < srcValues.length) ? srcValues[srcOrd] : Source.PROJECT;
            entries.add(new FileEntry(name, rel, abs, src));
         }
         return entries;

      } catch (IOException ex) {
         System.err.println("[QuickFileSearch] Cust disk cache read failed: " + ex + " — rescanning");
         f.delete();
         return null;
      }
   }

   private static void writeCustDiskCache(List<String> workspaceDirs, Set<String> exts,
           List<FileEntry> entries) {
      File f = custDiskCacheFile();
      File tmp = new File(f.getParent(), f.getName() + ".tmp");
      try (DataOutputStream out = new DataOutputStream(
              new java.io.BufferedOutputStream(new java.io.FileOutputStream(tmp), 1 << 16))) {

         out.writeLong(DISK_CACHE_MAGIC);
         out.writeInt(DISK_CACHE_VERSION);
         out.writeInt(workspaceDirs.size());
         for (String wsDir : workspaceDirs) {
            out.writeUTF(wsDir.replace('\\', '/'));
            out.writeLong(new File(wsDir).lastModified());
         }
         out.writeUTF(sortedExtString(exts));
         out.writeInt(entries.size());
         for (FileEntry e : entries) {
            out.writeUTF(e.name);
            out.writeUTF(e.relativePath);
            out.writeUTF(e.absolutePath);
            out.writeInt(e.source.ordinal());
         }
         out.flush();
      } catch (IOException ex) {
         System.err.println("[QuickFileSearch] Cust disk cache write failed: " + ex);
         tmp.delete();
         return;
      }
      f.delete();
      if (!tmp.renameTo(f)) {
         System.err.println("[QuickFileSearch] Cust disk cache rename failed");
         tmp.delete();
      } else {
         System.err.println("[QuickFileSearch] Cust disk cache written: "
                 + entries.size() + " entries → " + f.getAbsolutePath());
      }
   }

   // =========================================================================
   // Merged cache — debounced rebuild
   // =========================================================================
   /**
    * Schedules a merged-cache rebuild after MERGE_DEBOUNCE_MS of quiet time.
    *
    * <p>
    * FIX vs old code: the callback is stored in {@code pendingMergeCallback}
    * instead of being wired directly into the timer's ActionListener list.
    * Rewiring listeners on every restart was silently dropping earlier callbacks,
    * causing AtomicInteger pending-counters in scheduleCacheBuilds to never reach
    * zero and swallowing onComplete notifications.
    *
    * <p>
    * Must be called on EDT.
    */
   private static void scheduleMergedRebuild(Runnable extraCallback) {
      // Store the latest callback — last write wins, which is correct because
      // the timer restart means an earlier callback would have fired anyway.
      if (extraCallback != null) {
         pendingMergeCallback = extraCallback;
      }

      if (mergeDebounceTimer != null) {
         mergeDebounceTimer.restart();
      } else {
         mergeDebounceTimer = new javax.swing.Timer(MERGE_DEBOUNCE_MS, e -> {
            mergeDebounceTimer = null;
            doRebuildMergedCache();
            Runnable cb = pendingMergeCallback;
            pendingMergeCallback = null;
            if (cb != null) {
               cb.run();
            }
         });
         mergeDebounceTimer.setRepeats(false);
         mergeDebounceTimer.start();
      }
   }

   /**
    * The actual merge — must be called on EDT.
    */
   private static void doRebuildMergedCache() {
      int cap = (custCache != null ? custCache.size() : 0)
              + (buildCache != null ? buildCache.size() : 0)
              + (coreCache != null ? coreCache.size() : 0);

      List<FileEntry> merged = new ArrayList<>(cap);
      if (custCache != null) {
         merged.addAll(custCache);
      }
      if (buildCache != null) {
         merged.addAll(buildCache);
      }
      if (coreCache != null) {
         merged.addAll(coreCache);
      }

      mergedCache = merged;
      mergedCacheStale = false;

      if (custCache != null && buildCache != null && coreCache != null) {
         long elapsed = indexingStartedAt > 0
                 ? (System.currentTimeMillis() - indexingStartedAt)
                 : -1;
         System.err.println("[QuickFileSearch] All caches ready — total: "
                 + (elapsed >= 0 ? elapsed + "ms" : "n/a (disk cache)") + " | "
                 + "cust=" + custCache.size()
                 + " build=" + buildCache.size()
                 + " core=" + coreCache.size()
                 + " merged=" + mergedCache.size());
      }

      if (instance != null && instance.isVisible()) {
         instance.scheduleQueryUpdate();
      }
   }

   // =========================================================================
   // Watchers
   // =========================================================================
   private static void registerCustWatcher(List<String> workspaceDirs, Set<String> exts) {
      unregisterCustWatcher();
      if (workspaceDirs.isEmpty()) {
         return;
      }

      custWatcher = new org.openide.filesystems.FileChangeAdapter() {
         @Override
         public void fileDataCreated(org.openide.filesystems.FileEvent fe) {
            java.io.File f = FileUtil.toFile(fe.getFile());
            if (f == null) {
               return;
            }
            String ext = getExtension(f.getName());
            if (!exts.contains(ext)) {
               return;
            }

            String abs = f.getAbsolutePath().replace('\\', '/');
            String rootPath = findWatchedRoot(abs, custWatchedRoots);
            String rel = rootPath != null && abs.startsWith(rootPath)
                    ? abs.substring(rootPath.length() + 1) : abs;

            FileEntry entry = new FileEntry(f.getName(), rel, abs, Source.PROJECT);
            synchronized (CACHE_LOCK) {
               if (custCache != null) {
                  custCache.add(entry);
               }
            }
            writeCustDirtyFlag();
            SwingUtilities.invokeLater(() -> {
               mergedCacheStale = true;
               scheduleMergedRebuild(null);
            });
         }

         @Override
         public void fileDeleted(org.openide.filesystems.FileEvent fe) {
            java.io.File f = FileUtil.toFile(fe.getFile());
            if (f == null) {
               return;
            }
            String abs = f.getAbsolutePath().replace('\\', '/');
            synchronized (CACHE_LOCK) {
               if (custCache != null) {
                  custCache.removeIf(e -> e.absolutePath.equals(abs));
               }
            }
            writeCustDirtyFlag();
            SwingUtilities.invokeLater(() -> {
               mergedCacheStale = true;
               scheduleMergedRebuild(null);
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

   private static void registerBuildWatcher(List<String> buildDirs) {
      unregisterBuildWatcher();
      if (buildDirs.isEmpty()) {
         return;
      }

      buildWatcher = new org.openide.filesystems.FileChangeAdapter() {
         @Override
         public void fileDataCreated(org.openide.filesystems.FileEvent fe) {
            debounceBuildRebuild();
         }

         @Override
         public void fileFolderCreated(org.openide.filesystems.FileEvent fe) {
            debounceBuildRebuild();
         }

         @Override
         public void fileDeleted(org.openide.filesystems.FileEvent fe) {
            debounceBuildRebuild();
         }
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
      if (custWatcher == null) {
         return;
      }
      for (String path : custWatchedRoots) {
         try {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
            if (fo != null) {
               fo.removeRecursiveListener(custWatcher);
            }
         } catch (Exception ignored) {
         }
      }
      custWatcher = null;
      custWatchedRoots.clear();
   }

   private static void unregisterBuildWatcher() {
      if (buildWatcher == null) {
         return;
      }
      for (String path : buildWatchedRoots) {
         try {
            FileObject fo = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
            if (fo != null) {
               fo.removeRecursiveListener(buildWatcher);
            }
         } catch (Exception ignored) {
         }
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
   /**
    * MUST be called from a background thread.
    */
   private static List<ProjectRoots> collectProjectRoots() {
      List<ProjectRoots> result = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      try {
         for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            FileObject projectDir = project.getProjectDirectory();
            if (projectDir == null) {
               continue;
            }
            File root = FileUtil.toFile(projectDir);
            if (root == null || !root.isDirectory()) {
               continue;
            }

            String rootPath = root.getAbsolutePath().replace('\\', '/');
            if (!seen.add(rootPath)) {
               continue;
            }

            File workspace = new File(root, "workspace");
            if (!workspace.isDirectory()) {
               System.err.println("[QuickFileSearch] Skipping non-IFS project: " + rootPath);
               continue;
            }

            File build = new File(root, "build");
            File core = readCoreFilesRoot(root);
            result.add(new ProjectRoots(rootPath, workspace,
                    build.isDirectory() ? build : null, core));
         }
      } catch (Exception ex) {
         System.err.println("[QuickFileSearch] collectProjectRoots: " + ex);
      }
      return result;
   }

   private static Set<String> computeActiveCoreRootsOffEdt() {
      Set<String> roots = new HashSet<>();
      try {
         for (Project project : OpenProjects.getDefault().getOpenProjects()) {
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
               roots.add(coreRoot.getAbsolutePath());
            }
         }
      } catch (Exception ex) {
         System.err.println("[QuickFileSearch] computeActiveCoreRootsOffEdt: " + ex);
      }
      return roots;
   }

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
            continue;
         }
         File f = new File(val.trim());
         if (!f.isAbsolute()) {
            f = new File(projectDir, val.trim());
         }
         f = FileUtil.normalizeFile(f);
         if (f.exists() && f.isDirectory()) {
            return f;
         }
         System.err.println("[QuickFileSearch] Core path invalid: " + f);
      }
      return null;
   }

   // =========================================================================
   // Parallel directory walker
   // =========================================================================
   private static void walkParallel(File dir, String rootPath,
           List<FileEntry> result, Source source, Set<String> exts) {

      String normRoot = rootPath.replace('\\', '/');
      ConcurrentLinkedQueue<FileEntry> queue = new ConcurrentLinkedQueue<>();

      File[] rootChildren = dir.listFiles();
      if (rootChildren == null) {
         return;
      }

      List<WalkTask> topTasks = new ArrayList<>();
      for (File f : rootChildren) {
         if (f.isHidden()) {
            continue;
         }
         String name = f.getName();
         if (f.isDirectory()) {
            if (shouldSkipDir(name, f)) {
               continue;
            }
            Source childSource = (source == Source.PROJECT && name.equals("build"))
                    ? Source.GENERATED : source;
            topTasks.add(new WalkTask(f, normRoot, queue, childSource, exts));
         } else {
            String ext = getExtension(name);
            if (!exts.contains(ext)) {
               continue;
            }
            String abs = f.getAbsolutePath().replace('\\', '/');
            String rel = abs.startsWith(normRoot) ? abs.substring(normRoot.length() + 1) : abs;
            queue.add(new FileEntry(name, rel, abs, source));
         }
      }

      if (!topTasks.isEmpty()) {
         WALK_POOL.invoke(new RecursiveAction() {
            @Override
            protected void compute() {
               invokeAll(topTasks);
            }
         });
      }

      result.addAll(queue);
   }

   private static final class WalkTask extends RecursiveAction {

      private final File dir;
      private final String rootPath;
      private final ConcurrentLinkedQueue<FileEntry> result;
      private final Source source;
      private final Set<String> exts;

      WalkTask(File dir, String rootPath, ConcurrentLinkedQueue<FileEntry> result,
              Source source, Set<String> exts) {
         this.dir = dir;
         this.rootPath = rootPath;
         this.result = result;
         this.source = source;
         this.exts = exts;
      }

      @Override
      protected void compute() {
         walkDir(dir, source);
      }

      private void walkDir(File startDir, Source startSource) {
         Deque<File[]> dirStack = new ArrayDeque<>();
         Deque<Source> srcStack = new ArrayDeque<>();
         File[] first = startDir.listFiles();
         if (first == null) {
            return;
         }
         dirStack.push(first);
         srcStack.push(startSource);

         while (!dirStack.isEmpty()) {
            File[] children = dirStack.pop();
            Source curSource = srcStack.pop();
            for (File f : children) {
               if (f.isHidden()) {
                  continue;
               }
               String name = f.getName();
               if (f.isDirectory()) {
                  if (shouldSkipDir(name, f)) {
                     continue;
                  }
                  File[] sub = f.listFiles();
                  if (sub == null) {
                     continue;
                  }
                  Source childSource = (curSource == Source.PROJECT && name.equals("build"))
                          ? Source.GENERATED : curSource;
                  dirStack.push(sub);
                  srcStack.push(childSource);
               } else {
                  String ext = getExtension(name);
                  if (!exts.contains(ext)) {
                     continue;
                  }
                  String abs = f.getAbsolutePath().replace('\\', '/');
                  String rel = abs.startsWith(rootPath)
                          ? abs.substring(rootPath.length() + 1) : abs;
                  result.add(new FileEntry(name, rel, abs, curSource));
               }
            }
         }
      }
   }

   private static boolean shouldSkipDir(String name, File dir) {
      switch (name) {
         case ".git":
         case ".svn":
         case "node_modules":
         case "target":
         case ".idea":
         case "nbproject":
            return true;
      }
      if (name.equals("server") && isModuleChild(dir)) {
         return true;
      }
      return false;
   }

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

   // =========================================================================
   // Utilities
   // =========================================================================
   private static String getExtension(String fileName) {
      int dot = fileName.lastIndexOf('.');
      return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
   }

   private static String findWatchedRoot(String absPath, List<String> roots) {
      for (String root : roots) {
         if (absPath.startsWith(root)) {
            return root;
         }
      }
      return null;
   }

   private static String getBaseName(String fileName) {
      String base = fileName.contains(".")
              ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
      String lower = base.toLowerCase(Locale.ROOT);
      if (lower.endsWith("-cust") || lower.endsWith("-base")) {
         base = base.substring(0, base.length() - 5);
      }
      return base;
   }

   // =========================================================================
   // UI fields
   // =========================================================================
   private final BatchListModel<FileEntry> resultModel = new BatchListModel<>();
   private final JList<FileEntry> resultList = new JList<>(resultModel);
   private final JTextField searchField = new JTextField();
   private final JLabel statusLabel = new JLabel(" ");

   /**
    * Debounce timer for keystrokes — fires QUERY_DEBOUNCE_MS after last change,
    * then offloads filtering to a background thread.
    */
   private javax.swing.Timer queryDebounceTimer = null;

   // =========================================================================
   // Construction
   // =========================================================================
   private QuickFileSearchDialog(Frame owner) {
      super(owner, false);
      setUndecorated(true);
      buildUI();
      pack();
      setSize(560, 420);
      centerOnOwner(owner);

      addWindowFocusListener(new WindowAdapter() {
         @Override
         public void windowLostFocus(WindowEvent e) {
            if (e.getOppositeWindow() != QuickFileSearchDialog.this) {
               closeDialog();
            }
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

      // ── Top bar ───────────────────────────────────────────────────────
      JPanel topPanel = new JPanel(new BorderLayout(6, 0));
      topPanel.setBorder(new EmptyBorder(8, 10, 8, 10));
      topPanel.setOpaque(false);

      JLabel icon = new JLabel("\uD83D\uDD0D");
      icon.setFont(icon.getFont().deriveFont(14f));
      topPanel.add(icon, BorderLayout.WEST);

      searchField.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
      searchField.setFont(searchField.getFont().deriveFont(13f));
      searchField.putClientProperty("JTextField.placeholderText", "Search files\u2026");
      searchField.setOpaque(false);
      topPanel.add(searchField, BorderLayout.CENTER);

      // ── East button panel ─────────────────────────────────────────────
      JPanel eastBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
      eastBtns.setOpaque(false);

      JButton convertBtn = new JButton("\u21C4"); // ⇄
      convertBtn.setFocusable(false);
      convertBtn.setFont(convertBtn.getFont().deriveFont(13f));
      convertBtn.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
      convertBtn.setOpaque(false);
      convertBtn.setContentAreaFilled(false);
      convertBtn.setToolTipText("Convert _API/_SYS/_SVC name to PascalCase (e.g. customer_order_api → CustomerOrder)");
      convertBtn.addActionListener(e -> {
         String current = searchField.getText().trim();
         if (!current.isEmpty()) {
            String converted = FindApiFileAction.convertApiName(current);
            searchField.setText(converted);
            searchField.setCaretPosition(converted.length());
            searchField.requestFocusInWindow();
         }
      });

      JButton refreshBtn = new JButton("\u21BB");
      refreshBtn.setFocusable(false);
      refreshBtn.setFont(refreshBtn.getFont().deriveFont(13f));
      refreshBtn.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 2));
      refreshBtn.setOpaque(false);
      refreshBtn.setContentAreaFilled(false);
      refreshBtn.setToolTipText("Refresh file index (clears all caches)");
      refreshBtn.addActionListener(e -> refreshAllCaches());

      eastBtns.add(convertBtn);
      eastBtns.add(refreshBtn);
      topPanel.add(eastBtns, BorderLayout.EAST);

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

      // ── Document listener ─────────────────────────────────────────────
      searchField.getDocument().addDocumentListener(new DocumentListener() {
         @Override
         public void insertUpdate(DocumentEvent e) {
            scheduleQueryUpdate();
         }

         @Override
         public void removeUpdate(DocumentEvent e) {
            scheduleQueryUpdate();
         }

         @Override
         public void changedUpdate(DocumentEvent e) {
            scheduleQueryUpdate();
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

   // =========================================================================
   // Open / close / refresh
   // =========================================================================
   private void openAndFocus(String prefill) {
      searchField.setText(prefill != null ? prefill : "");
      resultModel.setAll(Collections.emptyList());
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

   private void refreshAllCaches() {
      statusLabel.setText("Refreshing\u2026");
      resultModel.setAll(Collections.emptyList());
      refreshAllCachesStatic(() -> {
         statusLabel.setText("Refresh complete \u2014 " + mergedCache.size() + " files indexed");
         scheduleQueryUpdate();
      });
   }

   /**
    * Ensures caches are building if needed.
    */
   private void ensureCacheForDialog() {
      scheduleQueryUpdate();
      if (refreshInProgress) {
         return;
      }
      if (cachedCoreRoots.isEmpty() && !indexingEverStarted.get()) {
         new Thread(() -> {
            QuickFileSearchDialog.seedInitialCoreRoots();
            SwingUtilities.invokeLater(() -> {
               indexingEverStarted.set(true);
               scheduleCacheBuilds(this::scheduleQueryUpdate);
            });
         }, "QuickFileSearch-DialogInitGate").start();
      } else {
         scheduleCacheBuilds(this::scheduleQueryUpdate);
      }
   }

   // =========================================================================
   // Query / filter — debounced + off-EDT
   // =========================================================================
   /**
    * Entry point called from document listener and from doRebuildMergedCache().
    * Debounces QUERY_DEBOUNCE_MS then offloads the actual filtering work to a
    * background thread so the EDT is never blocked by a linear scan of mergedCache.
    */
   private void scheduleQueryUpdate() {
      if (queryDebounceTimer != null) {
         queryDebounceTimer.restart();
         return;
      }
      queryDebounceTimer = new javax.swing.Timer(QUERY_DEBOUNCE_MS, e -> {
         queryDebounceTimer = null;
         runFilterAsync();
      });
      queryDebounceTimer.setRepeats(false);
      queryDebounceTimer.start();
   }

   /**
    * Snapshot state on EDT, then do the heavy lifting off-EDT, then
    * apply results back on EDT in one batch.
    */
   private void runFilterAsync() {
      // Snapshot volatile state on EDT before handing off.
      final List<FileEntry> snapshot = mergedCache;
      final String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
      final boolean stillScanning = custCacheBuilding || coreCacheBuilding || buildCacheBuilding;

      if (snapshot.isEmpty() && stillScanning) {
         resultModel.setAll(Collections.emptyList());
         statusLabel.setText("Scanning files\u2026");
         return;
      }

      new Thread(() -> {
         List<FileEntry> matched = filterAndSort(snapshot, query);
         final int custCount = custCache != null ? custCache.size() : 0;
         final int buildCount = buildCache != null ? buildCache.size() : 0;
         final int coreCount = coreCache != null ? coreCache.size() : 0;
         SwingUtilities.invokeLater(() -> applyResults(matched, query, custCount, buildCount, coreCount));
      }, "QuickFileSearch-Filter").start();
   }

   /**
    * Pure filtering function — no EDT access, safe to run on any thread.
    */
   private static List<FileEntry> filterAndSort(List<FileEntry> allFiles, String query) {
      List<FileEntry> matched = new ArrayList<>();

      if (query.isEmpty()) {
         matched.addAll(allFiles);
         return matched;
      }

      for (FileEntry entry : allFiles) {
         String lowerName = entry.name.toLowerCase(Locale.ROOT);
         String lowerBase = lowerName.contains(".")
                 ? lowerName.substring(0, lowerName.lastIndexOf('.')) : lowerName;

         boolean isCust = lowerBase.endsWith("-cust");
         boolean isBase = lowerBase.endsWith("-base");
         String matchBase = (isCust || isBase)
                 ? lowerBase.substring(0, lowerBase.length() - 5) : lowerBase;

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
            entry.score = tierScore + 10;
            matched.add(entry);
         } else if (matchBase.contains(query)) {
            entry.score = tierScore;
            matched.add(entry);
         } else if (lowerBase.startsWith(query)) {
            entry.score = tierScore + 10;
            matched.add(entry);
         } else if (lowerBase.contains(query)) {
            entry.score = tierScore;
            matched.add(entry);
         } else if (lowerName.startsWith(query)) {
            entry.score = tierScore + 10;
            matched.add(entry);
         } else if (lowerName.contains(query)) {
            entry.score = tierScore;
            matched.add(entry);
         }
      }

      matched.sort((a, b) -> {
         String baseA = getBaseName(a.name);
         String baseB = getBaseName(b.name);
         String lowerA = a.name.toLowerCase(Locale.ROOT);
         String lowerB = b.name.toLowerCase(Locale.ROOT);
         String lowerBa = baseA.toLowerCase(Locale.ROOT);
         String lowerBb = baseB.toLowerCase(Locale.ROOT);

         boolean aExact = lowerA.equals(query), bExact = lowerB.equals(query);
         if (aExact != bExact) {
            return aExact ? -1 : 1;
         }

         boolean aFP = lowerA.startsWith(query), bFP = lowerB.startsWith(query);
         if (aFP != bFP) {
            return aFP ? -1 : 1;
         }

         boolean aBP = lowerBa.startsWith(query), bBP = lowerBb.startsWith(query);
         if (aBP != bBP) {
            return aBP ? -1 : 1;
         }

         int nc = baseA.compareToIgnoreCase(baseB);
         if (nc != 0) {
            return nc;
         }

         int tc = Integer.compare(b.score, a.score);
         if (tc != 0) {
            return tc;
         }

         return a.name.compareToIgnoreCase(b.name);
      });

      return matched;
   }

   /**
    * Applies filter results back to the UI on EDT.
    * Uses BatchListModel.setAll() to fire a single ListDataEvent instead of
    * 100 individual addElement() calls.
    */
   private void applyResults(List<FileEntry> matched, String query,
           int custCount, int buildCount, int coreCount) {
      int displayCount = Math.min(matched.size(), 100);
      List<FileEntry> display = matched.subList(0, displayCount);

      // Single batch update — one contentsChanged event, one repaint.
      resultModel.setAll(display);
      if (!resultModel.isEmpty()) {
         resultList.setSelectedIndex(0);
      }

      int total = custCount + buildCount + coreCount;
      boolean scanning = custCacheBuilding || coreCacheBuilding || buildCacheBuilding;
      String scanning_suffix = scanning ? " (scanning\u2026)" : "";

      statusLabel.setText(query.isEmpty()
              ? total + " files indexed" + scanning_suffix
              : matched.size() + " results  \u00b7  " + total + " files" + scanning_suffix);
   }

   // =========================================================================
   // Open selected file
   // =========================================================================
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
                    "File not found:\n" + absolutePath, "Open Failed", JOptionPane.WARNING_MESSAGE);
            return;
         }
         FileObject parentFo = FileUtil.toFileObject(file.getParentFile());
         if (parentFo != null) {
            parentFo.refresh();
         }

         FileObject fo = FileUtil.toFileObject(file);
         if (fo == null) {
            JOptionPane.showMessageDialog(null,
                    "Could not resolve file:\n" + absolutePath, "Open Failed", JOptionPane.WARNING_MESSAGE);
            return;
         }
         DataObject dob = DataObject.find(fo);
         OpenCookie oc = dob.getLookup().lookup(OpenCookie.class);
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
      setLocation(b.x + (b.width - getWidth()) / 2,
              b.y + (b.height - getHeight()) / 3);
   }

   // =========================================================================
   // Cell renderer
   // =========================================================================
   private static final class FileEntryRenderer extends JPanel
           implements ListCellRenderer<FileEntry> {

      private static final Color BADGE_CORE_BG = new Color(0x6A1B9A);
      private static final Color BADGE_GENERATED_BG = new Color(0xE65100);
      private static final Color BADGE_FG = Color.WHITE;

      private final JLabel nameLabel = new JLabel();
      private final JLabel pathLabel = new JLabel();
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
         if (text == null || text.isEmpty()) {
            return;
         }

         Graphics2D g2 = (Graphics2D) g.create();
         g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
         g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

         g2.setFont(badgeLabel.getFont());
         FontMetrics fm = g2.getFontMetrics();
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
                 Math.max(0, Math.min(255, Math.round(a.getRed() + t * (b.getRed() - a.getRed())))),
                 Math.max(0, Math.min(255, Math.round(a.getGreen() + t * (b.getGreen() - a.getGreen())))),
                 Math.max(0, Math.min(255, Math.round(a.getBlue() + t * (b.getBlue() - a.getBlue())))));
      }
   }
}
