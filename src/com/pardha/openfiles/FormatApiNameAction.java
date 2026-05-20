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

        String formatted = FindApiFileAction.convertApiNameFormatted(raw);

        // Replace the selected text in the editor with the formatted name
        editor.replaceSelection(formatted);
    }
}