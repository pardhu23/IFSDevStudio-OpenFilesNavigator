package com.pardha.openfiles;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.util.NbBundle.Messages;

/**
 * Action that opens the Quick File Search popup (Ctrl+P).
 *
 * Registered via annotation so layer.xml is generated automatically.
 * The shortcut Ctrl+P is bound in layer.xml under Shortcuts/.
 */
@ActionID(
    category = "File",
    id = "com.pardha.openfiles.QuickFileSearchAction"
)
@ActionRegistration(
    displayName = "#CTL_QuickFileSearchAction"
)
@ActionReferences({
    @ActionReference(path = "Menu/File", position = 1300, separatorBefore = 1290),
    @ActionReference(path = "Shortcuts", name = "D-P")   // D- = Ctrl on all platforms
})
@Messages("CTL_QuickFileSearchAction=Quick File Search")
public final class QuickFileSearchAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        QuickFileSearchDialog.showDialog();
    }
}
