package com.pardha.openfiles;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.tree.*;

public class ModuleDependencyTreePanel extends JPanel {

   public ModuleDependencyTreePanel() {
      setLayout(new BorderLayout(0, 6));
      setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

      JLabel title = new JLabel("Module Dependencies");
      title.setFont(title.getFont().deriveFont(Font.BOLD));
      add(title, BorderLayout.NORTH);

      JTabbedPane tabs = new JTabbedPane();
      tabs.addTab("Static", buildTree("STATIC"));
      tabs.addTab("Dynamic", buildTree("DYNAMIC"));
      tabs.addTab("Static Path Finder", buildPathFinder());
      add(tabs, BorderLayout.CENTER);
   }

   // ── existing flat tree (unchanged) ──────────────────────────────────────
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

         Class<?> pairClass = pairs.get(0).getClass();
         java.lang.reflect.Field modField = pairClass.getDeclaredField("module");
         java.lang.reflect.Field depField = pairClass.getDeclaredField("dependencyModule");
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
      return new JScrollPane(tree);
   }

   // ── path finder tab ──────────────────────────────────────────────────────
   private JPanel buildPathFinder() {
      JPanel panel = new JPanel(new BorderLayout(0, 8));
      panel.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));

      // Input row
      JPanel inputRow = new JPanel(new GridBagLayout());
      GridBagConstraints gc = new GridBagConstraints();
      gc.insets = new Insets(0, 4, 0, 4);
      gc.fill = GridBagConstraints.HORIZONTAL;

      JTextField fromField = new JTextField(10);
      JTextField toField = new JTextField(10);
      JButton findBtn = new JButton("Find Path");
      JLabel fromLabel = new JLabel("From:");
      JLabel toLabel = new JLabel("To:");

      fromField.setToolTipText("e.g. REQMGT");
      toField.setToolTipText("e.g. WO");

      gc.gridx = 0;
      gc.weightx = 0;
      inputRow.add(fromLabel, gc);
      gc.gridx = 1;
      gc.weightx = 1;
      inputRow.add(fromField, gc);
      gc.gridx = 2;
      gc.weightx = 0;
      inputRow.add(toLabel, gc);
      gc.gridx = 3;
      gc.weightx = 1;
      inputRow.add(toField, gc);
      gc.gridx = 4;
      gc.weightx = 0;
      inputRow.add(findBtn, gc);

      panel.add(inputRow, BorderLayout.NORTH);

      // Result area
      JTree resultTree = new JTree(new DefaultMutableTreeNode("Results will appear here"));
      resultTree.setRootVisible(true);
      resultTree.setShowsRootHandles(true);
      panel.add(new JScrollPane(resultTree), BorderLayout.CENTER);

      // Find button action
      findBtn.addActionListener(e -> {
         String from = fromField.getText().trim().toUpperCase();
         String to = toField.getText().trim().toUpperCase();

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
            List<List<String>> paths = findAllPaths(staticMap, from, to);

            if (paths.isEmpty()) {
               showResult(resultTree, "No static dependency path found between " + from + " and " + to);
               return;
            }

            DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                    from + "  →  " + to + "  [STATIC]  (" + paths.size() + " path(s))");

            for (List<String> path : paths) {
               DefaultMutableTreeNode pathNode = new DefaultMutableTreeNode(
                       String.join(" → ", path));
               addChain(pathNode, path, staticMap, "STATIC");
               root.add(pathNode);
            }

            resultTree.setModel(new DefaultTreeModel(root));
            /*for (int i = 0; i < resultTree.getRowCount(); i++) {
               resultTree.expandRow(i);
            }*/

         } catch (Exception ex) {
            showResult(resultTree, "Error: " + ex.getMessage());
            ex.printStackTrace();
         }
      });

      fromField.addActionListener(e -> findBtn.doClick());
      toField.addActionListener(e -> findBtn.doClick());

      return panel;
   }

   // adds each step of the path as child nodes showing the dependency type
   private void addChain(DefaultMutableTreeNode parent, List<String> path,
           Map<String, Set<String>> map, String depType) {
      for (int i = 0; i < path.size() - 1; i++) {
         String from = path.get(i);
         String to = path.get(i + 1);
         parent.add(new DefaultMutableTreeNode(
                 from + "  --[" + depType + "]-->  " + to));
      }
   }

   // BFS to find ALL paths from → to (capped at 20 to avoid explosion)
   private List<List<String>> findAllPaths(Map<String, Set<String>> adjacency,
           String from, String to) {
      List<List<String>> results = new ArrayList<>();
      if (!adjacency.containsKey(from)) {
         return results;
      }

      Queue<List<String>> queue = new LinkedList<>();
      queue.add(new ArrayList<>(Collections.singletonList(from)));

      while (!queue.isEmpty() && results.size() < 20) {
         List<String> path = queue.poll();
         String last = path.get(path.size() - 1);

         Set<String> neighbours = adjacency.get(last);
         if (neighbours == null) {
            continue;
         }

         for (String neighbour : neighbours) {
            if (path.contains(neighbour)) {
               continue; // avoid cycles
            }
            List<String> newPath = new ArrayList<>(path);
            newPath.add(neighbour);
            if (neighbour.equals(to)) {
               results.add(newPath);
            } else {
               queue.add(newPath);
            }
         }
      }
      return results;
   }

   // builds module → set of its dependencies (what it depends ON)
   private Map<String, Set<String>> buildAdjacency(ClassLoader ifsClassLoader, String type)
           throws Exception {
      Map<String, Set<String>> map = new HashMap<>();
      List<?> pairs = getPairs(ifsClassLoader, type);
      if (pairs == null) {
         return map;
      }

      Class<?> pairClass = pairs.get(0).getClass();
      java.lang.reflect.Field modField = pairClass.getDeclaredField("module");
      java.lang.reflect.Field depField = pairClass.getDeclaredField("dependencyModule");
      modField.setAccessible(true);
      depField.setAccessible(true);

      for (Object pair : pairs) {
         String mod = (String) modField.get(pair);
         String dep = (String) depField.get(pair);
         // mod depends on dep → edge: mod → dep
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
            if (cl == null) {
               continue;
            }
            try {
               cl.loadClass("ifs.dev.vertical.baseserver.api.DatabaseContentUtilitiesSqlLibrary");
               return cl;
            } catch (ClassNotFoundException ignored) {
            }
         }
      }
      return null;
   }

   private List<?> getPairs(ClassLoader ifsClassLoader, String type) throws Exception {
      Class<?> mgrClass = Class.forName(
              "ifs.dev.vertical.baseserver.api.DatabaseContentManager", true, ifsClassLoader);
      java.lang.reflect.Field instancesField = mgrClass.getDeclaredField("instances");
      instancesField.setAccessible(true);
      Map<?, ?> instancesMap = (Map<?, ?>) instancesField.get(null);
      if (instancesMap == null || instancesMap.isEmpty()) {
         return null;
      }

      Object mgr = instancesMap.values().iterator().next();
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
