// Context: see docs/ifs-actions.md
package com.pardha.openfiles;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;

public class ModuleDependencyTreePanel extends JPanel {

    private static final String CORE_FILES_PROP = "project.ccs.corefiles";

    public ModuleDependencyTreePanel() {
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel title = new JLabel("Module Dependencies");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Static",             buildTree("STATIC"));
        tabs.addTab("Dynamic",            buildTree("DYNAMIC"));
        tabs.addTab("Static Path Finder", buildPathFinder());
        add(tabs, BorderLayout.CENTER);
    }

    // ── flat tree with RMB ───────────────────────────────────────────────────
    private JScrollPane buildTree(String type) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Modules");
        try {
            ClassLoader ifsClassLoader = getIfsClassLoader();
            if (ifsClassLoader == null) {
                return errorPane("Could not find IFS classloader.");
            }

            List<?> pairs = getPairs(ifsClassLoader, type);
            if (pairs == null || pairs.isEmpty()) {
                return errorPane("No " + type.toLowerCase() + " dependencies found.");
            }

            Class<?>                            pairClass = pairs.get(0).getClass();
            java.lang.reflect.Field             modField  = pairClass.getDeclaredField("module");
            java.lang.reflect.Field             depField  = pairClass.getDeclaredField("dependencyModule");
            modField.setAccessible(true);
            depField.setAccessible(true);

            java.util.Map<String, List<String>> grouped = new java.util.TreeMap<>();
            for (Object pair : pairs) {
                String mod = (String) modField.get(pair);
                String dep = (String) depField.get(pair);
                grouped.computeIfAbsent(mod, k -> new ArrayList<>()).add(dep);
            }

            for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
                List<String> deps = entry.getValue();
                Collections.sort(deps);
                DefaultMutableTreeNode moduleNode = new DefaultMutableTreeNode(
                        entry.getKey() + "  (" + deps.size() + ")");
                for (String dep : deps) {
                    moduleNode.add(new DefaultMutableTreeNode(dep));
                }
                root.add(moduleNode);
            }

        } catch (Exception e) {
            root.add(new DefaultMutableTreeNode("Error: " + e.getMessage()));
            e.printStackTrace();
        }

        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        for (int i = tree.getRowCount() - 1; i >= 0; i--) {
            tree.collapseRow(i);
        }

        addDeployIniContextMenu(tree);
        return new JScrollPane(tree);
    }

    // ── RMB context menu ─────────────────────────────────────────────────────
    private void addDeployIniContextMenu(JTree tree) {
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShowPopup(tree, e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(tree, e); }
        });
    }

    private void maybeShowPopup(JTree tree, MouseEvent e) {
        if (!SwingUtilities.isRightMouseButton(e)) return;

        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;

        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) path.getLastPathComponent();
        DefaultMutableTreeNode treeRoot =
                (DefaultMutableTreeNode) tree.getModel().getRoot();

        // Only act on direct children of root (= module/parent nodes, not leaf deps)
        if (node == treeRoot || node.getParent() != treeRoot) return;

        tree.setSelectionPath(path);

        // Strip trailing "  (N)" count to get the bare module folder name
        String label      = node.getUserObject().toString().trim();
        String moduleName = label.contains("  (")
                ? label.substring(0, label.indexOf("  (")).trim()
                : label;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem item  = new JMenuItem("Open deploy.ini  [" + moduleName + "]");
        item.addActionListener(ev -> openDeployIni(moduleName));
        menu.add(item);
        menu.show(tree, e.getX(), e.getY());
    }

    // ── deploy.ini resolver ──────────────────────────────────────────────────
    /**
     * Resolution order:
     *   1. {projectRoot}/workspace/{MODULE}/deploy.ini   (workspace customisation)
     *   2. {coreFilesRoot}/{MODULE}/deploy.ini           (core checkout)
     *
     * Both roots are read from the open NetBeans projects, mirroring the same
     * logic used in QuickFileSearchDialog / OpenFilesTopComponent.
     */
    private void openDeployIni(String moduleName) {
        // Must call OpenProjects off-EDT to avoid freezing the IDE.
        new Thread(() -> {
            File found = resolveDeployIni(moduleName);
            final File finalFound = found;
            SwingUtilities.invokeLater(() -> {
                if (finalFound == null) {
                    JOptionPane.showMessageDialog(
                            ModuleDependencyTreePanel.this,
                            "deploy.ini not found for module: " + moduleName
                            + "\n\nLooked in:\n"
                            + "  workspace\\" + moduleName + "\\deploy.ini\n"
                            + "  <corefiles>\\" + moduleName + "\\deploy.ini",
                            "File Not Found",
                            JOptionPane.WARNING_MESSAGE);
                } else {
                    doOpenFile(finalFound);
                }
            });
        }, "ModDepTree-DeployIni").start();
    }

    /**
     * Called from a background thread — safe to call OpenProjects here.
     */
    private File resolveDeployIni(String moduleName) {
        String iniRelPath = moduleName + File.separator + "deploy.ini";

        try {
            for (Project project : OpenProjects.getDefault().getOpenProjects()) {
                FileObject projectDir = project.getProjectDirectory();
                if (projectDir == null) continue;
                File projRoot = FileUtil.toFile(projectDir);
                if (projRoot == null) continue;

                // 1 — workspace path
                File workspaceCandidate = new File(
                        projRoot, "workspace" + File.separator + iniRelPath);
                workspaceCandidate = FileUtil.normalizeFile(workspaceCandidate);
                if (workspaceCandidate.exists()) {
                    System.err.println("[ModDepTree] deploy.ini found in workspace: "
                            + workspaceCandidate);
                    return workspaceCandidate;
                }

                // 2 — core / checkout path from project.properties
                File coreRoot = readCoreFilesRoot(projRoot);
                if (coreRoot != null) {
                    File coreCandidate = new File(coreRoot, iniRelPath);
                    coreCandidate = FileUtil.normalizeFile(coreCandidate);
                    if (coreCandidate.exists()) {
                        System.err.println("[ModDepTree] deploy.ini found in core: "
                                + coreCandidate);
                        return coreCandidate;
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("[ModDepTree] resolveDeployIni failed: " + ex);
        }
        return null;
    }

    // ── open a File in the IDE editor ────────────────────────────────────────
    private void doOpenFile(File file) {
        try {
            FileObject parentFo = FileUtil.toFileObject(file.getParentFile());
            if (parentFo != null) parentFo.refresh();

            FileObject fo = FileUtil.toFileObject(file);
            if (fo == null) {
                JOptionPane.showMessageDialog(this,
                        "Could not resolve file:\n" + file.getAbsolutePath(),
                        "Open Failed", JOptionPane.WARNING_MESSAGE);
                return;
            }
            DataObject dob = DataObject.find(fo);
            OpenCookie oc  = dob.getLookup().lookup(OpenCookie.class);
            if (oc != null) {
                oc.open();
            } else {
                javax.swing.Action action = dob.getNodeDelegate().getPreferredAction();
                if (action != null && action.isEnabled()) {
                    action.actionPerformed(
                            new java.awt.event.ActionEvent(
                                    this, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));
                } else {
                    JOptionPane.showMessageDialog(this,
                            "No handler found to open:\n" + file.getAbsolutePath(),
                            "Open Failed", JOptionPane.WARNING_MESSAGE);
                }
            }
        } catch (Exception ex) {
            System.err.println("[ModDepTree] doOpenFile failed: " + ex);
            JOptionPane.showMessageDialog(this,
                    "Failed to open file:\n" + ex.getMessage(),
                    "Open Failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ── core root reader — mirrors QuickFileSearchDialog.readCoreFilesRoot ───
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
                System.err.println("[ModDepTree] Could not read " + propsFile + ": " + ex);
                continue;
            }
            String val = props.getProperty(CORE_FILES_PROP);
            if (val == null || val.trim().isEmpty()) continue;
            File f = new File(val.trim());
            if (!f.isAbsolute()) f = new File(projectDir, val.trim());
            f = FileUtil.normalizeFile(f);
            if (f.exists() && f.isDirectory()) return f;
            System.err.println("[ModDepTree] Core path invalid: " + f);
        }
        return null;
    }

    // ── path finder tab ──────────────────────────────────────────
    private JPanel buildPathFinder() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));

        JPanel inputRow = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(0, 4, 0, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JTextField fromField = new JTextField(10);
        JTextField toField   = new JTextField(10);
        JButton    findBtn   = new JButton("Find Path");
        JLabel     fromLabel = new JLabel("From:");
        JLabel     toLabel   = new JLabel("To:");

        fromField.setToolTipText("e.g. REQMGT");
        toField.setToolTipText("e.g. WO");

        gc.gridx = 0; gc.weightx = 0; inputRow.add(fromLabel, gc);
        gc.gridx = 1; gc.weightx = 1; inputRow.add(fromField, gc);
        gc.gridx = 2; gc.weightx = 0; inputRow.add(toLabel,   gc);
        gc.gridx = 3; gc.weightx = 1; inputRow.add(toField,   gc);
        gc.gridx = 4; gc.weightx = 0; inputRow.add(findBtn,   gc);

        panel.add(inputRow, BorderLayout.NORTH);

        JTree resultTree = new JTree(new DefaultMutableTreeNode("Results will appear here"));
        resultTree.setRootVisible(true);
        resultTree.setShowsRootHandles(true);
        panel.add(new JScrollPane(resultTree), BorderLayout.CENTER);

        findBtn.addActionListener(e -> {
            String from = fromField.getText().trim().toUpperCase();
            String to   = toField.getText().trim().toUpperCase();
            if (from.isEmpty() || to.isEmpty()) {
                showResult(resultTree, "Please enter both module names.");
                return;
            }
            try {
                ClassLoader ifsClassLoader = getIfsClassLoader();
                if (ifsClassLoader == null) {
                    showResult(resultTree, "Could not find IFS classloader.");
                    return;
                }
                Map<String, Set<String>> staticMap = buildAdjacency(ifsClassLoader, "STATIC");
                List<List<String>>       paths     = findAllPaths(staticMap, from, to);
                if (paths.isEmpty()) {
                    showResult(resultTree, "No static dependency path found between " + from + " and " + to);
                    return;
                }
                DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                        from + "  \u2192  " + to + "  [STATIC]  (" + paths.size() + " path(s))");
                for (List<String> path : paths) {
                    DefaultMutableTreeNode pathNode = new DefaultMutableTreeNode(
                            String.join(" \u2192 ", path));
                    addChain(pathNode, path, staticMap, "STATIC");
                    root.add(pathNode);
                }
                resultTree.setModel(new DefaultTreeModel(root));
            } catch (Exception ex) {
                showResult(resultTree, "Error: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        fromField.addActionListener(e -> findBtn.doClick());
        toField.addActionListener(e -> findBtn.doClick());
        return panel;
    }

    private void addChain(DefaultMutableTreeNode parent, List<String> path,
            Map<String, Set<String>> map, String depType) {
        for (int i = 0; i < path.size() - 1; i++) {
            parent.add(new DefaultMutableTreeNode(
                    path.get(i) + "  --[" + depType + "]-->  " + path.get(i + 1)));
        }
    }

    private List<List<String>> findAllPaths(Map<String, Set<String>> adjacency,
            String from, String to) {
        List<List<String>> results = new ArrayList<>();
        if (!adjacency.containsKey(from)) return results;

        Queue<List<String>> queue = new LinkedList<>();
        queue.add(new ArrayList<>(Collections.singletonList(from)));

        while (!queue.isEmpty() && results.size() < 20) {
            List<String> path      = queue.poll();
            String       last      = path.get(path.size() - 1);
            Set<String>  neighbours = adjacency.get(last);
            if (neighbours == null) continue;
            for (String neighbour : neighbours) {
                if (path.contains(neighbour)) continue;
                List<String> newPath = new ArrayList<>(path);
                newPath.add(neighbour);
                if (neighbour.equals(to)) results.add(newPath);
                else                      queue.add(newPath);
            }
        }
        return results;
    }

    private Map<String, Set<String>> buildAdjacency(ClassLoader ifsClassLoader, String type)
            throws Exception {
        Map<String, Set<String>> map   = new HashMap<>();
        List<?>                  pairs = getPairs(ifsClassLoader, type);
        if (pairs == null) return map;

        Class<?>                pairClass = pairs.get(0).getClass();
        java.lang.reflect.Field modField  = pairClass.getDeclaredField("module");
        java.lang.reflect.Field depField  = pairClass.getDeclaredField("dependencyModule");
        modField.setAccessible(true);
        depField.setAccessible(true);

        for (Object pair : pairs) {
            String mod = (String) modField.get(pair);
            String dep = (String) depField.get(pair);
            map.computeIfAbsent(mod, k -> new HashSet<>()).add(dep);
        }
        return map;
    }

    // ── shared helpers ───────────────────────────────────────────────────────
    private ClassLoader getIfsClassLoader() {
        try {
            return Class.forName(
                    "ifs.dev.vertical.baseserver.api.DatabaseContentUtilitiesSqlLibrary",
                    false, Thread.currentThread().getContextClassLoader()
            ).getClassLoader();
        } catch (ClassNotFoundException e) {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                ClassLoader cl = t.getContextClassLoader();
                if (cl == null) continue;
                try {
                    cl.loadClass("ifs.dev.vertical.baseserver.api.DatabaseContentUtilitiesSqlLibrary");
                    return cl;
                } catch (ClassNotFoundException ignored) {}
            }
        }
        return null;
    }

    private List<?> getPairs(ClassLoader ifsClassLoader, String type) throws Exception {
        Class<?>                mgrClass      = Class.forName(
                "ifs.dev.vertical.baseserver.api.DatabaseContentManager", true, ifsClassLoader);
        java.lang.reflect.Field instancesField = mgrClass.getDeclaredField("instances");
        instancesField.setAccessible(true);
        Map<?, ?> instancesMap = (Map<?, ?>) instancesField.get(null);
        if (instancesMap == null || instancesMap.isEmpty()) return null;

        Object                   mgr    = instancesMap.values().iterator().next();
        java.lang.reflect.Method getter = "STATIC".equals(type)
                ? mgrClass.getMethod("getStaticDependencies")
                : mgrClass.getMethod("getDynamicDependecies");
        return (List<?>) getter.invoke(mgr);
    }

    private void showResult(JTree tree, String message) {
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(message)));
    }

    private JScrollPane errorPane(String msg) {
        JTextArea ta = new JTextArea(msg);
        ta.setEditable(false);
        ta.setOpaque(false);
        return new JScrollPane(ta);
    }

    public static void openWindow() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Module Dependency Tree");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(600, 700);
            frame.setLocationRelativeTo(null);
            frame.add(new ModuleDependencyTreePanel());
            frame.setVisible(true);
        });
    }
}