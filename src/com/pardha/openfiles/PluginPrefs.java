package com.pardha.openfiles;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.Locale;

/**
 * Central persistence helper for OpenFilesNavigator.
 *
 * All data is stored in the JVM user-preferences tree under the node
 * {@code /com/pardha/openfiles}.
 *
 * <h3>Storage layout</h3>
 * <pre>
 *   /com/pardha/openfiles
 *     viewMode          = "LIST" | "TREE"         (default LIST)
 *     noteDisplay       = "TOOLTIP" | "INLINE"    (default TOOLTIP)
 *     buildGrouping     = "INLINE" | "GROUPED"    (default INLINE)
 *
 *   /com/pardha/openfiles/pins
 *     0 = &lt;tcKey&gt;, 1 = &lt;tcKey&gt;, …   (ordered)
 *
 *   /com/pardha/openfiles/notes
 *     &lt;tcKey&gt; = &lt;note text&gt;
 *
 *   /com/pardha/openfiles/groups
 *     &lt;groupName&gt; = &lt;tcKey1&gt;\n&lt;tcKey2&gt;\n…
 *
 *   /com/pardha/openfiles/groupOrder
 *     0 = &lt;groupName&gt;, 1 = &lt;groupName&gt;, …
 *
 *   /com/pardha/openfiles/collapsedGroups
 *     &lt;sectionName&gt; = "true"    (only present when collapsed)
 * </pre>
 *
 * <b>TC key</b>: normalised tooltip path (full file path on disk), falling
 * back to display name. Stable across IDE restarts.
 */
public final class PluginPrefs {

   private static final String NODE_ROOT = "/com/pardha/openfiles";
   private static final String NODE_PINS = NODE_ROOT + "/pins";
   private static final String NODE_NOTES = NODE_ROOT + "/notes";
   private static final String NODE_GROUPS = NODE_ROOT + "/groups";
   private static final String NODE_GROUP_ORDER = NODE_ROOT + "/groupOrder";
   private static final String NODE_COLLAPSED_GROUPS = NODE_ROOT + "/collapsedGroups";
   private static final String NODE_CLOSED = NODE_ROOT + "/recentlyClosed";
   private static final String NODE_TAGS = NODE_ROOT + "/tags";

   private static final String KEY_VIEW_MODE = "viewMode";
   private static final String KEY_NOTE_DISPLAY = "noteDisplay";
   private static final String KEY_BUILD_GROUPING = "buildGrouping";
   private static final String KEY_TREE_GROUPING = "treeGrouping";
   private static final String KEY_LIST_SORT = "listSort";
   private static final String KEY_CLOSED_LIMIT = "closedHistoryLimit";
   private static final String KEY_CLOSED_STRIP_OPEN = "closedStripOpen";

   /**
    * Separator used inside a single closed-history preference value.
    * U+001F (ASCII Unit Separator) — legal in Preferences values, never
    * appears in Windows file paths or NetBeans display names.
    */
   private static final String CLOSED_SEP = "\u001F";

   public static final String VIEW_LIST = "LIST";
   public static final String VIEW_TREE = "TREE";
   public static final String NOTE_TOOLTIP = "TOOLTIP";
   public static final String NOTE_INLINE = "INLINE";
   public static final String NOTE_SUBTITLE = "SUBTITLE";

   /**
    * Build files shown inline in their natural folder (greyed/italic).
    */
   public static final String BUILD_INLINE = "INLINE";
   /**
    * Build files grouped under a dedicated "Generated" folder node.
    */
   public static final String BUILD_GROUPED = "GROUPED";

   /**
    * Tree ungrouped files grouped by parent two-folder path (default).
    */
   public static final String TREE_GROUP_FOLDER = "FOLDER";
   /**
    * Tree ungrouped files grouped by IFS component (segment after "workspace/").
    */
   public static final String TREE_GROUP_COMPONENT = "COMPONENT";

   /**
    * List files sorted alphabetically by name (default).
    */
   public static final String LIST_SORT_ALPHA = "ALPHA";
   /**
    * List files sorted in the order they were opened (oldest first).
    */
   public static final String LIST_SORT_OPEN_ORDER = "OPEN_ORDER";

   /**
    * Default number of recently-closed files to remember.
    */
   public static final int CLOSED_LIMIT_DEFAULT = 5;
   /**
    * Maximum allowed recently-closed history size.
    */
   public static final int CLOSED_LIMIT_MAX = 20;

   private static final String KEY_INDEX_EXTENSIONS = "indexExtensions";

   // Default — same as the current hardcoded set, comma-separated
   private static final String INDEX_EXTENSIONS_DEFAULT
           = "entity,plsql,storage,views,utility,projection,plsvc,client,svc,fragment,utility,rdf,report,cre,api,apy,apv,ddlsource,dmlsource,xetrigger,clnsource";

   public static String getIndexExtensions() {
      return root().get(KEY_INDEX_EXTENSIONS, INDEX_EXTENSIONS_DEFAULT);
   }

   public static void setIndexExtensions(String csv) {
      root().put(KEY_INDEX_EXTENSIONS, csv.trim());
      flush(root());
   }

   /**
    * Parses the stored CSV into a ready-to-use Set.
    */
   public static Set<String> getIndexExtensionSet() {
      Set<String> set = new HashSet<>();
      for (String ext : getIndexExtensions().split(",")) {
         ext = ext.trim().toLowerCase(Locale.ROOT);
         if (!ext.isEmpty()) {
            set.add(ext);
         }
      }
      return set;
   }

   // ── ClosedEntry ───────────────────────────────────────────────────────
   /**
    * Immutable snapshot of a closed editor TC — captured at close time
    * when the TopComponent object itself is no longer accessible.
    */
   public static final class ClosedEntry {

      /**
       * Display name at the time of closing (HTML tags stripped).
       */
      public final String name;
      /**
       * Normalised file path from the tooltip (forward slashes). Empty if unavailable.
       */
      public final String path;

      public ClosedEntry(String name, String path) {
         this.name = name != null ? name : "";
         this.path = path != null ? path : "";
      }

      /**
       * Returns true when the path is non-empty and points to an actual file.
       */
      public boolean hasPath() {
         return !path.isEmpty();
      }

      @Override
      public String toString() {
         return name;
      }
   }

   /**
    * Well-known section names used for collapse state.
    */
   public static final String SECTION_PINNED = "\uD83D\uDCCC Pinned";   // 📌 Pinned
   public static final String SECTION_UNGROUPED = "Ungrouped";
   public static final String SECTION_GENERATED = "Generated";

   private static final int MAX_KEY = 75;

   private PluginPrefs() {
   }

   private static Preferences root() {
      return Preferences.userRoot().node(NODE_ROOT);
   }

   // ── View mode ─────────────────────────────────────────────────────────
   public static String getViewMode() {
      String v = root().get(KEY_VIEW_MODE, VIEW_LIST);
      return VIEW_TREE.equals(v) ? VIEW_TREE : VIEW_LIST;
   }

   public static void setViewMode(String mode) {
      root().put(KEY_VIEW_MODE, mode);
      flush(root());
   }

   // ── Note display ──────────────────────────────────────────────────────
   public static String getNoteDisplay() {
      return root().get(KEY_NOTE_DISPLAY, NOTE_TOOLTIP);
   }

   public static void setNoteDisplay(String mode) {
      root().put(KEY_NOTE_DISPLAY, mode);
      flush(root());
   }

   // ── Build file grouping ───────────────────────────────────────────────
   public static String getBuildGrouping() {
      return root().get(KEY_BUILD_GROUPING, BUILD_INLINE);
   }

   public static void setBuildGrouping(String mode) {
      root().put(KEY_BUILD_GROUPING, mode);
      flush(root());
   }

   // ── Tree grouping ─────────────────────────────────────────────────────
   public static String getTreeGrouping() {
      String v = root().get(KEY_TREE_GROUPING, TREE_GROUP_FOLDER);
      return TREE_GROUP_COMPONENT.equals(v) ? TREE_GROUP_COMPONENT : TREE_GROUP_FOLDER;
   }

   public static void setTreeGrouping(String mode) {
      root().put(KEY_TREE_GROUPING, mode);
      flush(root());
   }

   // ── List sort order ───────────────────────────────────────────────────
   public static String getListSort() {
      String v = root().get(KEY_LIST_SORT, LIST_SORT_ALPHA);
      return LIST_SORT_OPEN_ORDER.equals(v) ? LIST_SORT_OPEN_ORDER : LIST_SORT_ALPHA;
   }

   public static void setListSort(String mode) {
      root().put(KEY_LIST_SORT, mode);
      flush(root());
   }

   // ── Closed history limit ──────────────────────────────────────────────
   /**
    * How many recently-closed entries to retain (1–{@value #CLOSED_LIMIT_MAX}).
    */
   public static int getClosedHistoryLimit() {
      int v = root().getInt(KEY_CLOSED_LIMIT, CLOSED_LIMIT_DEFAULT);
      return Math.max(1, Math.min(CLOSED_LIMIT_MAX, v));
   }

   public static void setClosedHistoryLimit(int limit) {
      limit = Math.max(1, Math.min(CLOSED_LIMIT_MAX, limit));
      root().putInt(KEY_CLOSED_LIMIT, limit);
      flush(root());
      // Trim existing history to the new limit immediately.
      java.util.List<ClosedEntry> current = getClosedHistory();
      if (current.size() > limit) {
         saveClosedHistory(current.subList(0, limit));
      }
   }

   // ── Closed-strip collapse state ───────────────────────────────────────
   public static boolean isClosedStripOpen() {
      return root().getBoolean(KEY_CLOSED_STRIP_OPEN, false);
   }

   public static void setClosedStripOpen(boolean open) {
      root().putBoolean(KEY_CLOSED_STRIP_OPEN, open);
      flush(root());
   }

   // ── Closed history entries ────────────────────────────────────────────
   /**
    * Returns the recently-closed history, most-recently-closed first.
    * The list is capped at {@link #getClosedHistoryLimit()} entries.
    */
   public static java.util.List<ClosedEntry> getClosedHistory() {
      java.util.List<ClosedEntry> result = new java.util.ArrayList<>();
      try {
         Preferences node = Preferences.userRoot().node(NODE_CLOSED);
         String[] rawKeys = node.keys();
         java.util.List<Integer> indices = new java.util.ArrayList<>();
         for (String k : rawKeys) {
            try {
               indices.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
         }
         Collections.sort(indices);
         for (int i : indices) {
            String raw = node.get(String.valueOf(i), null);
            if (raw == null) {
               continue;
            }
            int sep = raw.indexOf(CLOSED_SEP);
            if (sep < 0) {
               result.add(new ClosedEntry(raw, ""));
            } else {
               result.add(new ClosedEntry(raw.substring(0, sep), raw.substring(sep + 1)));
            }
         }
      } catch (BackingStoreException ignored) {
      }
      return result;
   }

   /**
    * Adds a new entry to the front of the closed history, evicting the oldest
    * entry if the list would exceed the current limit.
    * Duplicate paths (file closed and reopened then closed again) are moved
    * to the front rather than duplicated.
    */
   public static void pushClosedEntry(ClosedEntry entry) {
      java.util.List<ClosedEntry> list = getClosedHistory();
      // Remove any existing entry with the same path so it moves to the front.
      if (entry.hasPath()) {
         list.removeIf(e -> e.path.equals(entry.path));
      }
      list.add(0, entry);
      int limit = getClosedHistoryLimit();
      if (list.size() > limit) {
         list = list.subList(0, limit);
      }
      saveClosedHistory(list);
   }

   /**
    * Removes a single entry by path match (used when a file is reopened).
    */
   public static void removeClosedEntry(String path) {
      java.util.List<ClosedEntry> list = getClosedHistory();
      if (list.removeIf(e -> e.path.equals(path))) {
         saveClosedHistory(list);
      }
   }

   /**
    * Wipes the entire recently-closed history.
    */
   public static void clearClosedHistory() {
      try {
         Preferences node = Preferences.userRoot().node(NODE_CLOSED);
         node.clear();
         flush(node);
      } catch (BackingStoreException ignored) {
      }
   }

   private static void saveClosedHistory(java.util.List<ClosedEntry> list) {
      try {
         Preferences node = Preferences.userRoot().node(NODE_CLOSED);
         node.clear();
         for (int i = 0; i < list.size(); i++) {
            ClosedEntry e = list.get(i);
            node.put(String.valueOf(i), e.name + CLOSED_SEP + e.path);
         }
         flush(node);
      } catch (BackingStoreException ignored) {
      }
   }

   // ── Color tags ────────────────────────────────────────────────────────
   /**
    * Named color tags available for file rows.
    * {@code NONE} means no tag is assigned (transparent).
    */
   public enum TagColor {
      NONE(null),
      RED(new java.awt.Color(210, 60, 60)),
      ORANGE(new java.awt.Color(220, 140, 30)),
      GREEN(new java.awt.Color(60, 160, 60)),
      BLUE(new java.awt.Color(50, 130, 210)),
      PURPLE(new java.awt.Color(140, 70, 200));

      /**
       * The actual paint colour, or {@code null} for {@code NONE}.
       */
      public final java.awt.Color color;

      TagColor(java.awt.Color c) {
         this.color = c;
      }
   }

   /**
    * Returns the tag for the given TC key, never {@code null} (defaults to {@code NONE}).
    */
   public static TagColor getTag(String tcKey) {
      String v = Preferences.userRoot().node(NODE_TAGS).get(tcKey, null);
      if (v == null) {
         return TagColor.NONE;
      }
      try {
         return TagColor.valueOf(v);
      } catch (IllegalArgumentException ignored) {
         return TagColor.NONE;
      }
   }

   /**
    * Sets or clears the tag for the given TC key.
    */
   public static void setTag(String tcKey, TagColor tag) {
      Preferences node = Preferences.userRoot().node(NODE_TAGS);
      if (tag == null || tag == TagColor.NONE) {
         node.remove(tcKey);
      } else {
         node.put(tcKey, tag.name());
      }
      flush(node);
   }

   // ── TC key ────────────────────────────────────────────────────────────
   public static String keyFor(org.openide.windows.TopComponent tc) {
      String tip = tc.getToolTipText();
      if (tip != null && !tip.trim().isEmpty()) {
         tip = tip.replace('\\', '/').trim();
         tip = tip.replaceAll("<[^>]+>", "").trim();
         if (!tip.isEmpty()) {
            return truncate(tip);
         }
      }
      String name = OpenFilesTopComponent.resolveDisplayNameStatic(tc);
      if (name != null && !name.isEmpty()) {
         return truncate(name);
      }
      return truncate(tc.getClass().getName());
   }

   /**
    * Returns true when the TC's file path contains {@code /build/},
    * indicating an auto-generated file.
    */
   public static boolean isBuildFile(org.openide.windows.TopComponent tc) {
      String tip = tc.getToolTipText();
      if (tip == null || tip.trim().isEmpty()) {
         return false;
      }
      String normalised = tip.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
      return normalised.contains("/build/");
   }

   private static String truncate(String s) {
      if (s.length() <= MAX_KEY) {
         return s;
      }
      return "\u2026" + s.substring(s.length() - MAX_KEY + 1);
   }

   // ── Pins ──────────────────────────────────────────────────────────────
   public static Set<String> getPins() {
      try {
         Preferences node = Preferences.userRoot().node(NODE_PINS);
         String[] rawKeys = node.keys();
         java.util.List<Integer> indices = new java.util.ArrayList<>();
         for (String k : rawKeys) {
            try {
               indices.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
         }
         Collections.sort(indices);
         Set<String> result = new LinkedHashSet<>();
         for (int i : indices) {
            String v = node.get(String.valueOf(i), null);
            if (v != null) {
               result.add(v);
            }
         }
         return result;
      } catch (BackingStoreException e) {
         return new LinkedHashSet<>();
      }
   }

   public static void setPins(java.util.List<String> ordered) {
      try {
         Preferences node = Preferences.userRoot().node(NODE_PINS);
         node.clear();
         for (int i = 0; i < ordered.size(); i++) {
            node.put(String.valueOf(i), ordered.get(i));
         }
         flush(node);
      } catch (BackingStoreException ignored) {
      }
   }

   public static void addPin(String key) {
      Set<String> pins = getPins();
      if (pins.contains(key)) {
         return;
      }
      java.util.List<String> list = new java.util.ArrayList<>(pins);
      list.add(key);
      setPins(list);
   }

   public static void removePin(String key) {
      java.util.List<String> list = new java.util.ArrayList<>(getPins());
      list.remove(key);
      setPins(list);
   }

   public static boolean isPinned(String key) {
      return getPins().contains(key);
   }

   // ── Notes ─────────────────────────────────────────────────────────────
   public static String getNote(String tcKey) {
      return Preferences.userRoot().node(NODE_NOTES).get(tcKey, "");
   }

   public static void setNote(String tcKey, String note) {
      Preferences node = Preferences.userRoot().node(NODE_NOTES);
      if (note == null || note.trim().isEmpty()) {
         node.remove(tcKey);
      } else {
         if (note.length() > 500) {
            note = note.substring(0, 500);
         }
         node.put(tcKey, note);
      }
      flush(node);
   }

   public static void removeNote(String tcKey) {
      Preferences node = Preferences.userRoot().node(NODE_NOTES);
      node.remove(tcKey);
      flush(node);
   }

   // ── Groups ────────────────────────────────────────────────────────────
   public static Map<String, java.util.List<String>> getGroups() {
      Map<String, java.util.List<String>> result = new LinkedHashMap<>();
      try {
         Preferences gNode = Preferences.userRoot().node(NODE_GROUPS);
         for (String name : getGroupOrder()) {
            result.put(name, splitKeys(gNode.get(name, "")));
         }
         for (String k : gNode.keys()) {
            if (!result.containsKey(k)) {
               result.put(k, splitKeys(gNode.get(k, "")));
            }
         }
      } catch (BackingStoreException ignored) {
      }
      return result;
   }

   public static java.util.List<String> getGroupOrder() {
      try {
         Preferences oNode = Preferences.userRoot().node(NODE_GROUP_ORDER);
         String[] rawKeys = oNode.keys();
         java.util.List<Integer> indices = new java.util.ArrayList<>();
         for (String k : rawKeys) {
            try {
               indices.add(Integer.parseInt(k));
            } catch (NumberFormatException ignored) {
            }
         }
         Collections.sort(indices);
         java.util.List<String> names = new java.util.ArrayList<>();
         for (int i : indices) {
            String v = oNode.get(String.valueOf(i), null);
            if (v != null) {
               names.add(v);
            }
         }
         return names;
      } catch (BackingStoreException e) {
         return new java.util.ArrayList<>();
      }
   }

   public static void saveGroups(Map<String, java.util.List<String>> groups) {
      try {
         Preferences gNode = Preferences.userRoot().node(NODE_GROUPS);
         Preferences oNode = Preferences.userRoot().node(NODE_GROUP_ORDER);
         gNode.clear();
         oNode.clear();
         int idx = 0;
         for (Map.Entry<String, java.util.List<String>> e : groups.entrySet()) {
            gNode.put(e.getKey(), joinKeys(e.getValue()));
            oNode.put(String.valueOf(idx++), e.getKey());
         }
         flush(gNode);
         flush(oNode);
      } catch (BackingStoreException ignored) {
      }
   }

   public static void createGroup(String groupName) {
      Map<String, java.util.List<String>> groups = getGroups();
      if (!groups.containsKey(groupName)) {
         groups.put(groupName, new java.util.ArrayList<>());
         saveGroups(groups);
      }
   }

   public static void renameGroup(String oldName, String newName) {
      if (oldName.equals(newName)) {
         return;
      }
      Map<String, java.util.List<String>> groups = getGroups();
      if (!groups.containsKey(oldName) || groups.containsKey(newName)) {
         return;
      }
      Map<String, java.util.List<String>> rebuilt = new LinkedHashMap<>();
      for (Map.Entry<String, java.util.List<String>> e : groups.entrySet()) {
         rebuilt.put(e.getKey().equals(oldName) ? newName : e.getKey(), e.getValue());
      }
      saveGroups(rebuilt);
      boolean wasCollapsed = isGroupCollapsed(oldName);
      setGroupCollapsed(oldName, false);
      if (wasCollapsed) {
         setGroupCollapsed(newName, true);
      }
   }

   public static void deleteGroup(String groupName) {
      Map<String, java.util.List<String>> groups = getGroups();
      groups.remove(groupName);
      saveGroups(groups);
      setGroupCollapsed(groupName, false);
   }

   public static void addToGroup(String groupName, String tcKey) {
      Map<String, java.util.List<String>> groups = getGroups();
      groups.computeIfAbsent(groupName, k -> new java.util.ArrayList<>());
      if (!groups.get(groupName).contains(tcKey)) {
         groups.get(groupName).add(tcKey);
         saveGroups(groups);
      }
   }

   public static void removeFromGroup(String groupName, String tcKey) {
      Map<String, java.util.List<String>> groups = getGroups();
      if (groups.containsKey(groupName)) {
         groups.get(groupName).remove(tcKey);
         saveGroups(groups);
      }
   }

   public static String groupOf(String tcKey) {
      for (Map.Entry<String, java.util.List<String>> e : getGroups().entrySet()) {
         if (e.getValue().contains(tcKey)) {
            return e.getKey();
         }
      }
      return null;
   }

   public static Set<String> allGroupedKeys() {
      Set<String> result = new LinkedHashSet<>();
      for (java.util.List<String> keys : getGroups().values()) {
         result.addAll(keys);
      }
      return result;
   }

   // ── Collapsed sections ────────────────────────────────────────────────
   public static boolean isGroupCollapsed(String sectionName) {
      return Preferences.userRoot().node(NODE_COLLAPSED_GROUPS)
              .getBoolean(sanitiseKey(sectionName), false);
   }

   public static void setGroupCollapsed(String sectionName, boolean collapsed) {
      Preferences node = Preferences.userRoot().node(NODE_COLLAPSED_GROUPS);
      String k = sanitiseKey(sectionName);
      if (collapsed) {
         node.putBoolean(k, true);
      } else {
         node.remove(k);
      }
      flush(node);
   }

   public static void setAllGroupsCollapsed(boolean collapsed) {
      for (String name : getGroupOrder()) {
         setGroupCollapsed(name, collapsed);
      }
      setGroupCollapsed(SECTION_PINNED, collapsed);
      setGroupCollapsed(SECTION_UNGROUPED, collapsed);
      setGroupCollapsed(SECTION_GENERATED, collapsed);
   }

   private static String sanitiseKey(String s) {
      return s.replaceAll("[^\\x20-\\x7E]", "_").trim();
   }

   // ── Helpers ───────────────────────────────────────────────────────────
   private static java.util.List<String> splitKeys(String raw) {
      java.util.List<String> list = new java.util.ArrayList<>();
      if (raw == null || raw.trim().isEmpty()) {
         return list;
      }
      for (String part : raw.split("\n")) {
         part = part.trim();
         if (!part.isEmpty()) {
            list.add(part);
         }
      }
      return list;
   }

   private static String joinKeys(java.util.List<String> keys) {
      return String.join("\n", keys);
   }

   private static void flush(Preferences node) {
      try {
         node.flush();
      } catch (BackingStoreException ignored) {
      }
   }
}
