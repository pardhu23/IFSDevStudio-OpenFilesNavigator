// Context: see docs/ifs-actions.md
package com.pardha.openfiles;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Locale;
import javax.swing.text.JTextComponent;
import org.netbeans.editor.Utilities;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;

@ActionID(
        category = "Edit",
        id = "com.pardha.openfiles.FormatApiNameAction"
)
@ActionRegistration(
        displayName = "Format IFS Package Name(_API/_SYS)"
)
@ActionReferences({
    @ActionReference(
            path = "Editors/text/test-plsql/Popup",
            position = 322
    ),
    @ActionReference(
            path = "Editors/text/test-plsvc/Popup",
            position = 322
    ),
    @ActionReference(
            path = "Editors/text/x-plsql/Popup",
            position = 322
    )
})
public final class FormatApiNameAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        JTextComponent editor = Utilities.getFocusedComponent();
        if (editor == null) return;

        String sel = editor.getSelectedText();
        if (sel == null || sel.trim().isEmpty()) return;

        String raw = sel.trim();
        String upper = raw.toUpperCase(Locale.ROOT);

        // Only act on _API or _SYS selections — silently ignore anything else
        if (!upper.endsWith("_API") && !upper.endsWith("_SYS")) return;

        String formatted = convertApiNameFormatted(raw);

        // Replace the selected text in the editor with the formatted name
        editor.replaceSelection(formatted);
    }
    
    /**
     * Converts snake_case API/SYS names to a formatted display string,
     * preserving underscores, capitalising each word, uppercasing the suffix.
     *
     * Examples:
     *   customer_order_api  →  Customer_Order_API
     *   client_sys          →  Client_SYS
     *   shipment_line_api   →  Shipment_Line_API
     */
    static String convertApiNameFormatted(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        String upper = raw.toUpperCase(Locale.ROOT);
        String suffix = "";
        String body = raw;

        if (upper.endsWith("_API")) {
            suffix = "_API";
            body = raw.substring(0, raw.length() - 4);
        } else if (upper.endsWith("_SYS")) {
            suffix = "_SYS";
            body = raw.substring(0, raw.length() - 4);
        }

        String[] parts = body.split("_", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            if (i > 0) sb.append("_");
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        sb.append(suffix); // _API or _SYS already uppercased
        return sb.toString();
    }
}