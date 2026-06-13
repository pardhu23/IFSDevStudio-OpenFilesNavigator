// Context: see docs/ifs-actions.md
package com.pardha.openfiles;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import org.netbeans.editor.Utilities;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.filesystems.FileObject;

@ActionID(
        category = "Edit",
        id = "com.pardha.openfiles.IfsHeaderCommentAction"
)
@ActionRegistration(
        displayName = "IFS: Insert Header Comment"
)
@ActionReferences({
    @ActionReference(path = "Editors/Popup", position = 322)
})
public final class IfsHeaderCommentAction implements ActionListener {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyMMdd");

    @Override
    public void actionPerformed(ActionEvent e) {
        JTextComponent editor = Utilities.getFocusedComponent();
        if (editor == null) {
            return;
        }

        String devId  = PluginPrefs.getDeveloperId();
        String custId = PluginPrefs.getCustomizationId();
        if (devId.isEmpty() || custId.isEmpty()) {
            JOptionPane.showMessageDialog(editor,
                    "Set Developer ID and Customization ID in Settings (⚙ Open Files panel) before using this action.",
                    "IFS: Insert Header Comment",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String prefix = detectPrefix(editor);
        String date   = LocalDate.now().format(DATE_FMT);
        String line   = prefix + "  " + date + "  " + devId + " " + custId + ": ";

        javax.swing.text.Document doc = editor.getDocument();
        try {
            String full  = doc.getText(0, doc.getLength());
            int    caret = editor.getCaretPosition();

            int lineStart = caret;
            while (lineStart > 0 && full.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }

            doc.insertString(lineStart, line + "\n", null);
            editor.setCaretPosition(lineStart + line.length());

        } catch (BadLocationException ex) {
            // editor state was inconsistent
        }
    }

    private static String detectPrefix(JTextComponent editor) {
        Object sdp = editor.getDocument().getProperty(
                javax.swing.text.Document.StreamDescriptionProperty);
        if (sdp instanceof FileObject) {
            String ext = ((FileObject) sdp).getExt().toLowerCase(Locale.ROOT);
            switch (ext) {
                case "java": case "entity": case "utility": case "enumeration":
                    return "//";
            }
        }
        return "--";
    }
}
