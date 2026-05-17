package com.pardha.openfiles;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.openide.windows.TopComponent;

/**
 * Cell renderer for the flat list view.
 *
 * <p>The list model contains two kinds of items:
 * <ul>
 *   <li>{@link TopComponent} — rendered as a file row with a × close button
 *       on the <em>left</em> edge, then the file icon, then the name and
 *       pin/note indicators.</li>
 *   <li>{@link GroupHeader} — rendered as a bold section-header row with a
 *       ▶/▼ collapse arrow, group name, and file count.</li>
 * </ul>
 *
 * <h3>Build files</h3>
 * Files whose tooltip path contains {@code /build/} are rendered in
 * <em>italic</em> with a muted slate-blue foreground to signal they are
 * auto-generated and read-only.  They remain clickable.
 */
public class OpenFilesCellRenderer extends DefaultListCellRenderer {

    // ── Colours ───────────────────────────────────────────────────────────
    static final Color ACTIVE_BG       = new Color(0, 120, 215, 40);
    static final Color MODIFIED_COLOR  = new Color(220, 120, 0);
    static final Color CLOSE_BTN_COLOR = new Color(160, 50, 50);
    static final Color CLOSE_HOVER_BG  = new Color(220, 60, 60, 50);

    /**
     * Muted slate-blue used for auto-generated build files.
     * Distinct from disabled-grey so it reads as "different" not "broken".
     */
    static final Color BUILD_FILE_COLOR = new Color(120, 140, 170);

    /** Pixels reserved on the left of every file row for the × button. */
    static final int CLOSE_BTN_WIDTH = 20;

    // ── Hover state (written by MouseMotionListener in the TC) ─────────────
    int hoveredRow = -1;

    // ── Per-cell state set during getListCellRendererComponent ─────────────
    private Color tagStripeColor = null;
    private boolean isHovered    = false;

    // ── GroupHeader sentinel ───────────────────────────────────────────────

    /**
     * Sentinel object placed into the list model to represent a collapsible
     * section header (Pinned, a user group, Ungrouped, or Generated).
     */
    static final class GroupHeader {
        final String name;
        int fileCount;

        GroupHeader(String name, int fileCount) {
            this.name      = name;
            this.fileCount = fileCount;
        }

        @Override public String toString() { return name; }
    }

    // ── Renderer ──────────────────────────────────────────────────────────

    @Override
    public Component getListCellRendererComponent(
            JList<?> list, Object value, int index,
            boolean isSelected, boolean cellHasFocus) {

        if (value instanceof GroupHeader) {
            return renderHeader(list, (GroupHeader) value, index, isSelected);
        }
        return renderFile(list, value, index, isSelected);
    }

    // ── Header row ────────────────────────────────────────────────────────

    private Component renderHeader(JList<?> list, GroupHeader header,
            int index, boolean isSelected) {
        tagStripeColor = null; // headers never have a tag stripe
        isHovered      = false;

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(3, 6, 3, 6));

        Color baseBg = UIManager.getColor("Panel.background");
        if (baseBg == null) baseBg = new Color(240, 240, 240);
        panel.setBackground(isSelected
                ? UIManager.getColor("List.selectionBackground")
                : blend(baseBg, new Color(128, 128, 128), 0.08f));
        panel.setOpaque(true);

        boolean collapsed = PluginPrefs.isGroupCollapsed(header.name);
        String arrow = collapsed ? "\u25B6 " : "\u25BC "; // ▶ or ▼

        // Special label for Generated section
        String displayName = header.name;

        JLabel label = new JLabel(arrow + displayName + "  (" + header.fileCount + ")");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(isSelected
                ? UIManager.getColor("List.selectionForeground")
                : UIManager.getColor("Label.foreground"));

        // Tint the Generated header label slightly to match file colour
        if (!isSelected && PluginPrefs.SECTION_GENERATED.equals(header.name)) {
            label.setForeground(BUILD_FILE_COLOR.darker());
        }

        panel.add(label, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(panel.getPreferredSize().width, 24));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        return panel;
    }

    // ── File row ──────────────────────────────────────────────────────────

    private Component renderFile(JList<?> list, Object value, int index,
            boolean isSelected) {

        super.getListCellRendererComponent(list, value, index, isSelected, false);

        if (!(value instanceof TopComponent)) return this;
        TopComponent tc = (TopComponent) value;

        boolean isBuild = PluginPrefs.isBuildFile(tc);
        String name = OpenFilesTopComponent.resolveDisplayNameStatic(tc);
        if (name == null) name = "(unnamed)";

        String  tcKey   = PluginPrefs.keyFor(tc);
        String  note    = PluginPrefs.getNote(tcKey);
        boolean hasNote = note != null && !note.trim().isEmpty();
        boolean pinned  = PluginPrefs.isPinned(tcKey);
        boolean inline  = PluginPrefs.NOTE_INLINE.equals(PluginPrefs.getNoteDisplay());

        // Resolve tag stripe colour for paintComponent.
        PluginPrefs.TagColor tag = PluginPrefs.getTag(tcKey);
        tagStripeColor = (tag != PluginPrefs.TagColor.NONE) ? tag.color : null;
        isHovered      = (index == hoveredRow);

        StringBuilder display = new StringBuilder();
        if (pinned)            display.append("\uD83D\uDCCC "); // 📌
        display.append(name);
        if (hasNote && inline) display.append("  \u270E");      // ✎
        setText(display.toString());

        // Font: italic for build files
        Font base = list.getFont();
        setFont(isBuild ? base.deriveFont(Font.ITALIC) : base.deriveFont(Font.PLAIN));

        // Tooltip
        if (hasNote) {
            setToolTipText("<html><b>Note:</b> " + escapeHtml(note) + "</html>");
        } else {
            String tip = tc.getToolTipText();
            setToolTipText(tip != null && !tip.isEmpty() ? tip : null);
        }

        setBorder(new EmptyBorder(2, CLOSE_BTN_WIDTH + 4, 2, 6));

        java.awt.Image img = tc.getIcon();
        if (img != null) setIcon(new ImageIcon(img));
        else             setIcon(null);

        if (!isSelected) {
            if (isBuild) {
                // Build file: slate-blue, overrides modified colour
                setForeground(BUILD_FILE_COLOR);
            } else {
                String htmlName = tc.getHtmlDisplayName();
                boolean modified = htmlName != null
                        && (htmlName.contains("<b>") || htmlName.contains("modified")
                            || htmlName.contains("bold"));
                if (modified) setForeground(MODIFIED_COLOR);

                TopComponent active = TopComponent.getRegistry().getActivated();
                if (tc == active) {
                    setBackground(ACTIVE_BG);
                    setOpaque(true);
                }
            }
        }

        return this;
    }

    // ── Paint × button + tag stripe ───────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        int h  = getHeight();
        int bx = 0;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // ── Tag stripe: 4px bar just right of the × zone ──────────────────
        if (tagStripeColor != null) {
            g2.setColor(tagStripeColor);
            g2.fillRect(CLOSE_BTN_WIDTH, 2, 4, h - 4);
        }

        // ── Close button ──────────────────────────────────────────────────
        // Background only on hover — keeps tag stripes visible at rest.
        if (isHovered) {
            g2.setColor(CLOSE_HOVER_BG);
            g2.fillRect(bx + 2, 2, CLOSE_BTN_WIDTH - 2, h - 4);
        }

        // × symbol: full red on hover, muted grey at rest so it's still
        // discoverable without dominating the row colour.
        g2.setColor(isHovered ? CLOSE_BTN_COLOR : new Color(160, 160, 160, 120));
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f));
        FontMetrics fm = g2.getFontMetrics();
        String x = "\u00D7"; // ×
        int tx = bx + (CLOSE_BTN_WIDTH - fm.stringWidth(x)) / 2;
        int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(x, tx, ty);

        g2.dispose();
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>");
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
}