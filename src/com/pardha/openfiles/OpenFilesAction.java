package com.pardha.openfiles;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionRegistration;

@ActionID(
    category = "Window",
    id = "com.pardha.openfiles.OpenFilesAction"
)
@ActionRegistration(
    displayName = "Open Files Navigator"
)
@ActionReference(
    path = "Menu/Window",
    position = 100
)
public final class OpenFilesAction implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        OpenFilesTopComponent tc = OpenFilesTopComponent.getInstance();
        tc.open();
        tc.requestActive();
    }
}