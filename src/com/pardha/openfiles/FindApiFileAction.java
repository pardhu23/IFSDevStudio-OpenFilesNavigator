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
        id = "com.pardha.openfiles.FindApiFileAction"
)
@ActionRegistration(
        displayName = "Find File"
)
@ActionReferences({
   @ActionReference(
           path = "Editors/text/test-plsql/Popup",
           position = 320
   ),
   @ActionReference(
           path = "Editors/text/test-plsvc/Popup",
           position = 320
   ),
   @ActionReference(
           path = "Editors/text/x-plsql/Popup",
           position = 320
   )
})
public final class FindApiFileAction implements ActionListener {

   @Override
   public void actionPerformed(ActionEvent e) {
      JTextComponent editor = Utilities.getFocusedComponent();
      if (editor == null) {
         return;
      }
      String sel = editor.getSelectedText();

      if (sel == null || sel.trim().isEmpty()) {
         QuickFileSearchDialog.showDialog(null);
         return;
      }
      String converted = convertApiName(sel.trim());
      QuickFileSearchDialog.showDialog(converted);
   }

   static String convertApiName(String raw) {
      if (raw == null || raw.isEmpty()) {
         return raw;
      }
      String stripped = raw;
      if (stripped.toUpperCase(Locale.ROOT).endsWith("_API")) {
         stripped = stripped.substring(0, stripped.length() - 4);
      }
      if (stripped.toUpperCase(Locale.ROOT).endsWith("_SYS")) {
         stripped = stripped.substring(0, stripped.length() - 4);
      }
      String[] parts = stripped.split("_", -1);
      StringBuilder sb = new StringBuilder();
      for (String part : parts) {
         if (part.isEmpty()) {
            continue;
         }
         sb.append(Character.toUpperCase(part.charAt(0)));
         sb.append(part.substring(1).toLowerCase(Locale.ROOT));
      }
      return sb.toString();
   }
}
