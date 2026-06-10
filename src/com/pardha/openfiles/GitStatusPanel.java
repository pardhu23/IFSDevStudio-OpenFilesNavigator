// Context: see docs/git-integration.md
package com.pardha.openfiles;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.util.ContextAwareAction;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;

/**
 * Lightweight Git status panel — runs "git status --porcelain" directly,
 * bypassing the NetBeans Git plugin. Shows unstaged + staged changes in
 * under a second.
 *
 * Opened from OpenFilesTopComponent toolbar button.
 */
public class GitStatusPanel extends JPanel {

   // ── Git status codes (first two chars of porcelain output) ───────────
   private static final Map<String, String> STATUS_LABEL = new LinkedHashMap<>();

   static {
      STATUS_LABEL.put("M ", "Staged modified");
      STATUS_LABEL.put("A ", "Staged new");
      STATUS_LABEL.put("D ", "Staged deleted");
      STATUS_LABEL.put("R ", "Staged renamed");
      STATUS_LABEL.put(" M", "Modified");
      STATUS_LABEL.put(" D", "Deleted");
      STATUS_LABEL.put(" A", "Added");
      STATUS_LABEL.put("??", "Untracked");
      STATUS_LABEL.put("MM", "Staged + modified");
      STATUS_LABEL.put("AM", "Added + modified");
      STATUS_LABEL.put("UU", "Conflict");
   }

   // ── Status colours ────────────────────────────────────────────────────
   private static final Map<String, Color> STATUS_COLOR = new HashMap<>();

   static {
      STATUS_COLOR.put("M ", new Color(60, 160, 60));      // staged green
      STATUS_COLOR.put("A ", new Color(60, 160, 60));
      STATUS_COLOR.put("D ", new Color(200, 60, 60));
      STATUS_COLOR.put("R ", new Color(60, 160, 60));
      STATUS_COLOR.put(" M", new Color(220, 140, 30));     // unstaged orange
      STATUS_COLOR.put(" D", new Color(200, 60, 60));
      STATUS_COLOR.put(" A", new Color(220, 140, 30));
      STATUS_COLOR.put("??", new Color(150, 150, 150));    // untracked grey
      STATUS_COLOR.put("MM", new Color(50, 130, 210));
      STATUS_COLOR.put("AM", new Color(50, 130, 210));
      STATUS_COLOR.put("UU", new Color(200, 60, 60));
   }

   // ── Model ─────────────────────────────────────────────────────────────
   static final class GitEntry {

      final String xy;        // raw two-char status code
      final String path;      // repo-relative path
      final String absPath;   // absolute path on disk
      final String repoRoot;  // git repo root

      GitEntry(String xy, String path, String absPath, String repoRoot) {
         this.xy = xy;
         this.path = path;
         this.absPath = absPath;
         this.repoRoot = repoRoot;
      }

      String fileName() {
         int slash = path.lastIndexOf('/');
         return slash >= 0 ? path.substring(slash + 1) : path;
      }

      String statusLabel() {
         return STATUS_LABEL.getOrDefault(xy, xy.trim());
      }

      Color statusColor() {
         return STATUS_COLOR.getOrDefault(xy, Color.GRAY);
      }

      boolean isStaged() {
         return xy.charAt(0) != ' ' && xy.charAt(0) != '?';
      }

      boolean isUnstaged() {
         return xy.charAt(1) != ' ';
      }

      boolean isUntracked() {
         return xy.equals("??");
      }
   }

   // ── UI ────────────────────────────────────────────────────────────────
   private final DefaultListModel<Object> listModel = new DefaultListModel<>();
   private final JList<Object> list = new JList<>(listModel);
   private final JLabel statusBar = new JLabel(" ");
   private final JButton refreshBtn;
   private final JCheckBox showUntrackedCb = new JCheckBox("Untracked", true);
   private final JTextField filterField = new JTextField();

   private List<GitEntry> allEntries = new ArrayList<>();
   private volatile boolean loading = false;
   private Runnable onCountsChanged;

   private volatile String currentBranch = "";
   private volatile String currentRepoRoot = "";
   private volatile List<String> localBranches = Collections.emptyList();

   public GitStatusPanel() {
      setLayout(new BorderLayout(0, 4));
      setBorder(new EmptyBorder(6, 6, 6, 6));

      // ── Toolbar ───────────────────────────────────────────────────────
      refreshBtn = new JButton("\u21BB");
      refreshBtn.setFocusable(false);
      refreshBtn.setMargin(new Insets(1, 5, 1, 5));
      refreshBtn.setToolTipText("Refresh git status");
      refreshBtn.addActionListener(e -> refresh());

      showUntrackedCb.setFocusable(false);
      showUntrackedCb.addActionListener(e -> applyFilter());

      filterField.putClientProperty("JTextField.placeholderText", "Filter files\u2026");
      filterField.getDocument().addDocumentListener(
              new javax.swing.event.DocumentListener() {
         @Override
         public void insertUpdate(javax.swing.event.DocumentEvent e) {
            applyFilter();
         }

         @Override
         public void removeUpdate(javax.swing.event.DocumentEvent e) {
            applyFilter();
         }

         @Override
         public void changedUpdate(javax.swing.event.DocumentEvent e) {
            applyFilter();
         }
      });

      JPanel toolbar = new JPanel(new BorderLayout(4, 0));
      toolbar.setOpaque(false);

      JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      left.setOpaque(false);
      left.add(refreshBtn);
      left.add(showUntrackedCb);
      toolbar.add(left, BorderLayout.WEST);
      toolbar.add(filterField, BorderLayout.CENTER);
      add(toolbar, BorderLayout.NORTH);

      // ── List ──────────────────────────────────────────────────────────
      list.setCellRenderer(new GitEntryCellRenderer());
      list.setFixedCellHeight(26);
      list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
      list.addMouseListener(new MouseAdapter() {
         @Override
         public void mouseClicked(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
               openSelected();
            }
         }

         @Override
         public void mousePressed(MouseEvent e) {
            maybePopup(e);
         }

         @Override
         public void mouseReleased(MouseEvent e) {
            maybePopup(e);
         }
      });
      list.addKeyListener(new KeyAdapter() {
         @Override
         public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
               openSelected();
            }
         }
      });

      JScrollPane scroll = new JScrollPane(list);
      scroll.setBorder(BorderFactory.createEmptyBorder());
      add(scroll, BorderLayout.CENTER);

      // ── Status bar ────────────────────────────────────────────────────
      statusBar.setFont(statusBar.getFont().deriveFont(Font.PLAIN, 10f));
      statusBar.setForeground(UIManager.getColor("Label.disabledForeground"));
      add(statusBar, BorderLayout.SOUTH);

      refresh();
   }

   // =========================================================================
   // Git execution
   // =========================================================================
   public void refresh() {
      if (loading) {
         return;
      }
      loading = true;
      refreshBtn.setEnabled(false);
      statusBar.setText("Running git status\u2026");
      listModel.clear();

      new Thread(() -> {
         List<GitEntry> entries = new ArrayList<>();
         List<String> errors = new ArrayList<>();
         File firstRoot = null;

         try {
            for (Project project : OpenProjects.getDefault().getOpenProjects()) {
               FileObject projDir = project.getProjectDirectory();
               if (projDir == null) {
                  continue;
               }
               File root = FileUtil.toFile(projDir);
               if (root == null) {
                  continue;
               }

               // Check workspace subfolder first (IFS project structure)
               File workspaceDir = new File(root, "workspace");
               File gitRoot = new File(workspaceDir, ".git").exists()
                       ? workspaceDir
                       : findGitRoot(root);
               if (gitRoot == null) {
                  errors.add("No .git found for: " + root.getName());
                  continue;
               }

               if (firstRoot == null) { firstRoot = gitRoot; }
               entries.addAll(runGitStatus(gitRoot));
            }
         } catch (Exception ex) {
            errors.add("Error: " + ex.getMessage());
            System.err.println("[GitStatusPanel] refresh failed: " + ex);
         }

         final String branch   = firstRoot != null ? fetchCurrentBranch(firstRoot) : "";
         final String rootPath = firstRoot != null
                 ? firstRoot.getAbsolutePath().replace('\\', '/') : "";
         final List<String> branches = firstRoot != null
                 ? fetchLocalBranches(firstRoot) : Collections.emptyList();

         final List<GitEntry> finalEntries = entries;
         final List<String> finalErrors = errors;
         SwingUtilities.invokeLater(() -> {
            currentBranch   = branch;
            currentRepoRoot = rootPath;
            localBranches   = branches;
            allEntries = finalEntries;
            loading = false;
            refreshBtn.setEnabled(true);
            applyFilter();
            if (!finalErrors.isEmpty()) {
               statusBar.setText(finalErrors.get(0));
            }
         });
      }, "GitStatusPanel-Refresh").start();
   }

   /**
    * Walks up from {@code dir} looking for a ".git" directory or file.
    */
   private static File findGitRoot(File dir) {
      File d = dir;
      while (d != null) {
         if (new File(d, ".git").exists()) {
            return d;
         }
         d = d.getParentFile();
      }
      return null;
   }

   /**
    * Runs "git status --porcelain -u" in {@code gitRoot} and parses output.
    * Porcelain format: XY PATH or XY ORIG -> PATH (for renames)
    */
   private static List<GitEntry> runGitStatus(File gitRoot) {
      List<GitEntry> result = new ArrayList<>();
      try {
         ProcessBuilder pb = new ProcessBuilder(findGitExecutable(), "status", "--porcelain", "-u");
         pb.directory(gitRoot);
         pb.redirectErrorStream(false);
         Process proc = pb.start();

         try (BufferedReader br = new BufferedReader(
                 new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
               if (line.length() < 4) {
                  continue;   // "XY " minimum
               }
               String xy = line.substring(0, 2);
               String rest = line.substring(3);

               // Handle renames: "R  old -> new"  or  "old\0new" in -z mode
               // In non-z porcelain: "R  orig -> dest"
               String filePath = rest.contains(" -> ")
                       ? rest.substring(rest.lastIndexOf(" -> ") + 4)
                       : rest;
               filePath = filePath.replace('\\', '/').trim();

               // Strip surrounding quotes Git adds for paths with spaces
               if (filePath.startsWith("\"") && filePath.endsWith("\"")) {
                  filePath = filePath.substring(1, filePath.length() - 1);
               }

               String absPath = gitRoot.getAbsolutePath().replace('\\', '/')
                       + "/" + filePath;
               result.add(new GitEntry(xy, filePath, absPath,
                       gitRoot.getAbsolutePath().replace('\\', '/')));
            }
         }
         proc.waitFor();

      } catch (Exception ex) {
         System.err.println("[GitStatusPanel] git status failed: " + ex);
         // "git not found" on Windows — try with full path or show message
      }
      return result;
   }

   // =========================================================================
   // Filter + display
   // =========================================================================
   private void applyFilter() {
      String q = filterField.getText().trim().toLowerCase(java.util.Locale.ROOT);
      boolean showUntracked = showUntrackedCb.isSelected();

      List<GitEntry> filtered = new ArrayList<>();
      for (GitEntry e : allEntries) {
         if (!showUntracked && e.isUntracked()) {
            continue;
         }
         if (!q.isEmpty() && !e.path.toLowerCase(java.util.Locale.ROOT).contains(q)) {
            continue;
         }
         filtered.add(e);
      }

      // Group by status category
      List<GitEntry> staged = new ArrayList<>();
      List<GitEntry> unstaged = new ArrayList<>();
      List<GitEntry> untracked = new ArrayList<>();
      for (GitEntry e : filtered) {
         if (e.isUntracked()) {
            untracked.add(e);
         } else if (e.isStaged()) {
            staged.add(e);
         } else {
            unstaged.add(e);
         }
      }

      listModel.clear();
      addSection("Staged  (" + staged.size() + ")", staged);
      addSection("Unstaged  (" + unstaged.size() + ")", unstaged);
      if (showUntracked) {
         addSection("Untracked  (" + untracked.size() + ")", untracked);
      }

      String counts = formatCounts(countTypes(filtered));
      String text = counts.isEmpty() ? "No changes" : counts;
      if (allEntries.size() != filtered.size()) {
         text += "  (filtered from " + allEntries.size() + ")";
      }
      statusBar.setText(text);

      if (onCountsChanged != null) {
         onCountsChanged.run();
      }
   }

   private void addSection(String header, List<GitEntry> entries) {
      if (entries.isEmpty()) {
         return;
      }
      listModel.addElement(header);   // String = section header
      for (GitEntry e : entries) {
         listModel.addElement(e);
      }
   }

   // =========================================================================
   // Open / context menu
   // =========================================================================
   private void openSelected() {
      int idx = list.getSelectedIndex();
      if (idx < 0) {
         return;
      }
      Object val = listModel.getElementAt(idx);
      if (val instanceof GitEntry) {
         openEntry((GitEntry) val);
      }
   }

   private void openEntry(GitEntry entry) {
      try {
         File file = FileUtil.normalizeFile(
                 new File(entry.absPath.replace('/', File.separatorChar)));
         if (!file.exists()) {
            JOptionPane.showMessageDialog(this,
                    "File not found:\n" + entry.absPath,
                    "Open Failed", JOptionPane.WARNING_MESSAGE);
            return;
         }
         FileObject fo = FileUtil.toFileObject(file);
         if (fo == null) {
            return;
         }
         DataObject dob = DataObject.find(fo);
         OpenCookie oc = dob.getLookup().lookup(OpenCookie.class);
         if (oc != null) {
            oc.open();
         } else {
            javax.swing.Action action = dob.getNodeDelegate().getPreferredAction();
            if (action != null && action.isEnabled()) {
               action.actionPerformed(new ActionEvent(
                       this, ActionEvent.ACTION_PERFORMED, ""));
            }
         }
      } catch (Exception ex) {
         System.err.println("[GitStatusPanel] openEntry failed: " + ex);
      }
   }

   private List<GitEntry> getSelectedEntries() {
      List<GitEntry> result = new ArrayList<>();
      for (int i : list.getSelectedIndices()) {
         Object val = listModel.getElementAt(i);
         if (val instanceof GitEntry) {
            result.add((GitEntry) val);
         }
      }
      return result;
   }

   private void maybePopup(MouseEvent e) {
      if (!SwingUtilities.isRightMouseButton(e)) {
         return;
      }
      int idx = list.locationToIndex(e.getPoint());
      if (idx < 0) {
         return;
      }
      if (!list.isSelectedIndex(idx)) {
         list.setSelectedIndex(idx);
      }

      Object val = listModel.getElementAt(idx);
      if (!(val instanceof GitEntry)) {
         return;
      }

      List<GitEntry> targets = getSelectedEntries();
      if (targets.isEmpty()) {
         return;
      }

      JPopupMenu menu = new JPopupMenu();
      boolean single = targets.size() == 1;

      JMenuItem openItem = new JMenuItem(single ? "Open" : "Open all (" + targets.size() + ")");
      openItem.setFont(openItem.getFont().deriveFont(Font.BOLD));
      openItem.addActionListener(ev -> targets.forEach(this::openEntry));
      menu.add(openItem);

      menu.addSeparator();

      if (single) {
         GitEntry entry = targets.get(0);

         JMenuItem showItem = new JMenuItem("Show in Project");
         showItem.addActionListener(ev -> showInProject(entry));
         menu.add(showItem);

         JMenuItem copyPath = new JMenuItem("Copy Absolute Path");
         copyPath.addActionListener(ev -> copyToClipboard(entry.absPath));
         menu.add(copyPath);

         JMenuItem copyRel = new JMenuItem("Copy Relative Path");
         copyRel.addActionListener(ev -> copyToClipboard(entry.path));
         menu.add(copyRel);

         JMenuItem copyName = new JMenuItem("Copy File Name");
         copyName.addActionListener(ev -> copyToClipboard(entry.fileName()));
         menu.add(copyName);

         menu.addSeparator();

         // Git actions — run in background
         JMenuItem gitAdd = new JMenuItem("git add");
         gitAdd.setEnabled(!entry.isStaged() || entry.isUntracked());
         gitAdd.addActionListener(ev -> runGitCommand(entry.repoRoot, entry.path,
                 "add", "git add"));

         JMenuItem gitRestore = new JMenuItem("git restore (discard changes)");
         gitRestore.setEnabled(!entry.isUntracked() && entry.isUnstaged());
         gitRestore.addActionListener(ev -> {
            int res = JOptionPane.showConfirmDialog(this,
                    "Discard all changes to:\n" + entry.path
                    + "\n\nThis cannot be undone.",
                    "git restore", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (res == JOptionPane.YES_OPTION) {
               runGitCommand(entry.repoRoot, entry.path, "restore", "git restore");
            }
         });

         JMenuItem gitUnstage = new JMenuItem("git restore --staged (unstage)");
         gitUnstage.setEnabled(entry.isStaged());
         gitUnstage.addActionListener(ev
                 -> runGitCommand(entry.repoRoot, entry.path, "restore --staged", "git unstage"));

         menu.add(gitAdd);
         menu.add(gitUnstage);
         menu.add(gitRestore);

      } else {
         // Multi-selection git add
         JMenuItem gitAddAll = new JMenuItem("git add (" + targets.size() + " files)");
         gitAddAll.addActionListener(ev -> {
            for (GitEntry entry : targets) {
               runGitCommandSilent(entry.repoRoot, entry.path, "add");
            }
            refresh();
         });
         menu.add(gitAddAll);
      }

      menu.addSeparator();
      JMenuItem refreshItem = new JMenuItem("Refresh");
      refreshItem.addActionListener(ev -> refresh());
      menu.add(refreshItem);

      menu.show(list, e.getX(), e.getY());
   }

   // =========================================================================
   // Branch info — used by header label and branch-switch popup
   // =========================================================================
   public String getCurrentBranch()       { return currentBranch; }
   public String getCurrentRepoRoot()     { return currentRepoRoot; }
   public List<String> getLocalBranches() { return localBranches; }

   public void checkoutBranch(String branch) {
      String root = currentRepoRoot;
      if (root.isEmpty()) { return; }
      new Thread(() -> {
         runGit(root, "checkout", branch);
         SwingUtilities.invokeLater(this::refresh);
      }, "GitStatusPanel-Checkout").start();
   }

   private static String fetchCurrentBranch(File gitRoot) {
      return runGitOutput(gitRoot, "rev-parse", "--abbrev-ref", "HEAD");
   }

   private static List<String> fetchLocalBranches(File gitRoot) {
      String raw = runGitOutput(gitRoot, "branch");
      List<String> result = new ArrayList<>();
      for (String line : raw.split("\n")) {
         String b = line.replace("*", "").trim();
         if (!b.isEmpty()) { result.add(b); }
      }
      return result;
   }

   private static String runGitOutput(File workDir, String... args) {
      try {
         List<String> cmd = new ArrayList<>();
         cmd.add(findGitExecutable());
         cmd.addAll(Arrays.asList(args));
         ProcessBuilder pb = new ProcessBuilder(cmd);
         pb.directory(workDir);
         pb.redirectErrorStream(false);
         Process proc = pb.start();
         StringBuilder sb = new StringBuilder();
         try (BufferedReader br = new BufferedReader(
                 new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
               if (sb.length() > 0) { sb.append('\n'); }
               sb.append(line);
            }
         }
         proc.waitFor();
         return sb.toString().trim();
      } catch (Exception ex) {
         return "";
      }
   }

   private static void runGit(String repoRoot, String... args) {
      try {
         List<String> cmd = new ArrayList<>();
         cmd.add(findGitExecutable());
         cmd.addAll(Arrays.asList(args));
         ProcessBuilder pb = new ProcessBuilder(cmd);
         pb.directory(new File(repoRoot.replace('/', File.separatorChar)));
         pb.redirectErrorStream(true);
         Process proc = pb.start();
         try (BufferedReader br = new BufferedReader(
                 new InputStreamReader(proc.getInputStream()))) {
            while (br.readLine() != null) { }
         }
         proc.waitFor();
      } catch (Exception ex) {
         System.err.println("[GitStatusPanel] git command failed: " + ex);
      }
   }

   // =========================================================================
   // Count summary — used by header label in OpenFilesTopComponent
   // =========================================================================
   public void setOnCountsChanged(Runnable r) {
      this.onCountsChanged = r;
   }

   public String getCountSummary() {
      return formatCounts(countTypes(allEntries));
   }

   private static int[] countTypes(List<GitEntry> entries) {
      int a = 0, m = 0, d = 0, r = 0;
      for (GitEntry e : entries) {
         char x = e.xy.charAt(0);
         char y = e.xy.charAt(1);
         if (e.isUntracked() || x == 'A' || y == 'A') { a++; }
         else if (x == 'R' || y == 'R')               { r++; }
         else if (x == 'D' || y == 'D')               { d++; }
         else                                          { m++; }
      }
      return new int[]{a, m, d, r};
   }

   private static String formatCounts(int[] c) {
      // +N = new/added  ~N = modified  -N = deleted  →N = renamed
      StringBuilder sb = new StringBuilder();
      if (c[0] > 0) {                                   sb.append("+").append(c[0]); }
      if (c[1] > 0) { if (sb.length() > 0) sb.append("  "); sb.append("~").append(c[1]); }
      if (c[2] > 0) { if (sb.length() > 0) sb.append("  "); sb.append("-").append(c[2]); }
      if (c[3] > 0) { if (sb.length() > 0) sb.append("  "); sb.append("→").append(c[3]); }
      return sb.toString();
   }

   // =========================================================================
   // Git command helpers
   // =========================================================================
   private void runGitCommand(String repoRoot, String filePath,
           String gitArgs, String label) {
      new Thread(() -> {
         runGitCommandSilent(repoRoot, filePath, gitArgs);
         SwingUtilities.invokeLater(() -> {
            statusBar.setText(label + " done");
            refresh();
         });
      }, "GitStatusPanel-Cmd").start();
   }

   private static void runGitCommandSilent(String repoRoot, String filePath, String gitArgs) {
      try {
         List<String> cmd = new ArrayList<>();
         cmd.add(findGitExecutable());
         cmd.addAll(Arrays.asList(gitArgs.split(" ")));
         cmd.add(filePath);
         ProcessBuilder pb = new ProcessBuilder(cmd);
         pb.directory(new File(repoRoot.replace('/', File.separatorChar)));
         pb.redirectErrorStream(true);
         Process proc = pb.start();
         // Drain stdout so process doesn't hang
         try (BufferedReader br = new BufferedReader(
                 new InputStreamReader(proc.getInputStream()))) {
            while (br.readLine() != null) {
            }
         }
         proc.waitFor();
      } catch (Exception ex) {
         System.err.println("[GitStatusPanel] git command failed: " + ex);
      }
   }

   // =========================================================================
   // Show in Project
   // =========================================================================
   private void showInProject(GitEntry entry) {
      try {
         File file = FileUtil.normalizeFile(
                 new File(entry.absPath.replace('/', File.separatorChar)));
         if (!file.exists()) {
            JOptionPane.showMessageDialog(this,
                    "File not found:\n" + entry.absPath,
                    "Show in Project", JOptionPane.WARNING_MESSAGE);
            return;
         }
         FileObject fo = FileUtil.toFileObject(file);
         if (fo == null) {
            return;
         }
         DataObject dob = DataObject.find(fo);

         // SelectInProjects is registered as an instance file, not via @ActionID,
         // so Actions.forID cannot find it — must load from config FS directly.
         FileObject actionFile = FileUtil.getConfigFile(
                 "Actions/Window/SelectDocumentNode/"
                 + "org-netbeans-modules-project-ui-SelectInProjects.instance");
         if (actionFile == null) {
            JOptionPane.showMessageDialog(this,
                    "SelectInProjects action not found in config FS.",
                    "Show in Project", JOptionPane.WARNING_MESSAGE);
            return;
         }

         Object obj = actionFile.getAttribute("instanceCreate");
         if (!(obj instanceof javax.swing.Action)) {
            JOptionPane.showMessageDialog(this,
                    "Could not load SelectInProjects action instance.",
                    "Show in Project", JOptionPane.WARNING_MESSAGE);
            return;
         }

         javax.swing.Action action = (javax.swing.Action) obj;

         // Activate the file's TC first so the global selection propagates
         // before the action fires.
         org.openide.windows.TopComponent targetTc = findOpenTcForFile(fo);
         if (targetTc != null) {
            targetTc.requestActive();
         }

         final javax.swing.Action finalAction = action;
         final org.openide.nodes.Node node = dob.getNodeDelegate();
         final Lookup nodeLookup = Lookups.fixed(node, fo);

         javax.swing.Timer t = new javax.swing.Timer(200, ev -> {
            javax.swing.Action toFire = finalAction;
            if (toFire instanceof ContextAwareAction) {
               toFire = ((ContextAwareAction) toFire)
                       .createContextAwareInstance(nodeLookup);
            }
            toFire.actionPerformed(new ActionEvent(
                    this, ActionEvent.ACTION_PERFORMED, ""));
         });
         t.setRepeats(false);
         t.start();

      } catch (Exception ex) {
         System.err.println("[GitStatusPanel] showInProject: " + ex);
         JOptionPane.showMessageDialog(this,
                 "Failed:\n" + ex.getMessage(),
                 "Show in Project", JOptionPane.WARNING_MESSAGE);
      }
   }

   private static org.openide.windows.TopComponent findOpenTcForFile(FileObject fo) {
      String targetPath = fo.getPath().replace('\\', '/');
      for (org.openide.windows.TopComponent tc :
              org.openide.windows.TopComponent.getRegistry().getOpened()) {
         String tip = tc.getToolTipText();
         if (tip == null) {
            continue;
         }
         String normTip = tip.replace('\\', '/').trim()
                 .replaceAll("<[^>]+>", "").trim()
                 .replaceAll("\\s+[\\(\\[][^\\)\\]]*[\\)\\]]\\s*$", "").trim();
         if (normTip.endsWith(targetPath) || normTip.equals(targetPath)) {
            return tc;
         }
      }
      return null;
   }

   // =========================================================================
   // Utility
   // =========================================================================
   private static void copyToClipboard(String text) {
      java.awt.Toolkit.getDefaultToolkit()
              .getSystemClipboard()
              .setContents(new StringSelection(text), null);
   }

   // =========================================================================
   // Cell renderer
   // =========================================================================
   private static final class GitEntryCellRenderer extends JPanel
           implements javax.swing.ListCellRenderer<Object> {

      private final JLabel statusBadge = new JLabel();
      private final JLabel nameLabel = new JLabel();
      private final JLabel pathLabel = new JLabel();

      GitEntryCellRenderer() {
         setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
         setBorder(new EmptyBorder(0, 6, 0, 6));

         Dimension badgeSize = new Dimension(66, 16);
         statusBadge.setFont(statusBadge.getFont().deriveFont(Font.BOLD, 9f));
         statusBadge.setOpaque(true);
         statusBadge.setBorder(new EmptyBorder(1, 4, 1, 4));
         statusBadge.setPreferredSize(badgeSize);
         statusBadge.setMaximumSize(badgeSize);
         statusBadge.setMinimumSize(badgeSize);
         statusBadge.setHorizontalAlignment(SwingConstants.CENTER);

         nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 11f));

         Color dim = UIManager.getColor("Label.disabledForeground");
         pathLabel.setFont(pathLabel.getFont().deriveFont(Font.PLAIN, 10f));
         pathLabel.setForeground(dim != null ? dim : new Color(130, 130, 130));

         add(statusBadge);
         add(Box.createHorizontalStrut(7));
         add(nameLabel);
         add(Box.createHorizontalStrut(6));
         add(pathLabel);
         add(Box.createHorizontalGlue());
      }

      @Override
      public Component getListCellRendererComponent(
              JList<?> list, Object value, int index,
              boolean isSelected, boolean cellHasFocus) {

         if (value instanceof String) {
            JLabel header = new JLabel("  " + value);
            header.setFont(header.getFont().deriveFont(Font.BOLD, 10f));
            header.setOpaque(true);
            Color bg = UIManager.getColor("Panel.background");
            if (bg == null) {
               bg = new Color(240, 240, 240);
            }
            header.setBackground(blend(bg, new Color(128, 128, 128), 0.08f));
            return header;
         }

         GitEntry entry = (GitEntry) value;
         nameLabel.setText(entry.fileName());

         // Show the directory portion dimmed; omit if the file is at repo root
         int slash = entry.path.lastIndexOf('/');
         pathLabel.setText(slash > 0 ? entry.path.substring(0, slash) : "");

         statusBadge.setText(entry.statusLabel());
         statusBadge.setBackground(entry.statusColor());
         statusBadge.setForeground(Color.WHITE);

         Color dimFg = UIManager.getColor("Label.disabledForeground");
         pathLabel.setForeground(isSelected
                 ? list.getSelectionForeground()
                 : (dimFg != null ? dimFg : new Color(130, 130, 130)));

         if (isSelected) {
            setBackground(list.getSelectionBackground());
            nameLabel.setForeground(list.getSelectionForeground());
         } else {
            setBackground(list.getBackground());
            nameLabel.setForeground(list.getForeground());
         }
         setOpaque(true);
         return this;
      }

      private static Color blend(Color a, Color b, float t) {
         return new Color(
                 Math.max(0, Math.min(255, Math.round(a.getRed() + t * (b.getRed() - a.getRed())))),
                 Math.max(0, Math.min(255, Math.round(a.getGreen() + t * (b.getGreen() - a.getGreen())))),
                 Math.max(0, Math.min(255, Math.round(a.getBlue() + t * (b.getBlue() - a.getBlue())))));
      }
   }

   static String findGitExecutable() {
      String configured = PluginPrefs.getGitPath();
      if (!configured.isEmpty()) {
         return configured.replace('/', File.separatorChar);
      }
      String[] candidates = {
         "C:/Program Files/Git/bin/git.exe",
         "C:/Program Files (x86)/Git/bin/git.exe",
         System.getenv("LOCALAPPDATA") != null
         ? System.getenv("LOCALAPPDATA") + "/Programs/Git/bin/git.exe" : null,
         System.getenv("ProgramFiles") != null
         ? System.getenv("ProgramFiles") + "/Git/bin/git.exe" : null,
      };
      for (String path : candidates) {
         if (path != null && new File(path).exists()) {
            return path.replace('/', File.separatorChar);
         }
      }
      return "git";
   }
}
