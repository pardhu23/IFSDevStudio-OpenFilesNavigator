// Context: see docs/ifs-actions.md
package com.pardha.openfiles;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import javax.swing.JOptionPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import org.netbeans.editor.Utilities;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;

@ActionID(
        category = "Edit",
        id = "com.pardha.openfiles.IfsInlineCommentAction"
)
@ActionRegistration(
        displayName = "IFS: Wrap with (+) inline code comment"
)
@ActionReferences({
    @ActionReference(path = "Editors/Popup", position = 324)
})
public final class IfsInlineCommentAction implements ActionListener {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyMMdd");

    @Override
    public void actionPerformed(ActionEvent e) {
        JTextComponent editor = Utilities.getFocusedComponent();
        if (editor == null) {
            return;
        }

        int selStart = editor.getSelectionStart();
        int selEnd   = editor.getSelectionEnd();
        if (selStart == selEnd) {
            return;
        }

        String devId  = PluginPrefs.getDeveloperId();
        String custId = PluginPrefs.getCustomizationId();
        if (devId.isEmpty() || custId.isEmpty()) {
            JOptionPane.showMessageDialog(editor,
                    "Set Developer ID and Customization ID in Settings (⚙ Open Files panel) before using this action.",
                    "IFS: Wrap with (+) Change Block",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String date        = LocalDate.now().format(DATE_FMT);
        String startMarker = "--(+) " + date + " " + devId + " " + custId + " (START)";
        String endMarker   = "--(+) " + date + " " + devId + " " + custId + " (FINISH)";

        javax.swing.text.Document doc = editor.getDocument();
        try {
            String full = doc.getText(0, doc.getLength());

            // Expand to full-line boundaries so markers always start at column 0.
            int lineStart = selStart;
            while (lineStart > 0 && full.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }

            int lineEnd = selEnd;
            // If selEnd already sits at the start of the next line, don't consume it.
            if (lineEnd > lineStart && lineEnd <= full.length()
                    && full.charAt(lineEnd - 1) == '\n') {
                // already on a line boundary — leave lineEnd as-is
            } else {
                while (lineEnd < full.length() && full.charAt(lineEnd) != '\n') {
                    lineEnd++;
                }
                if (lineEnd < full.length()) {
                    lineEnd++; // include the trailing '\n'
                }
            }

            int indentEnd = lineStart;
            while (indentEnd < full.length()
                    && (full.charAt(indentEnd) == ' ' || full.charAt(indentEnd) == '\t')) {
                indentEnd++;
            }
            String indent = full.substring(lineStart, indentEnd);

            String block   = full.substring(lineStart, lineEnd);
            String wrapped = indent + startMarker + "\n"
                    + block
                    + (block.endsWith("\n") ? "" : "\n")
                    + indent + endMarker + "\n";

            editor.select(lineStart, lineEnd);
            editor.replaceSelection(wrapped);

        } catch (BadLocationException ex) {
            // nothing to do — editor state was inconsistent
        }
    }
}
