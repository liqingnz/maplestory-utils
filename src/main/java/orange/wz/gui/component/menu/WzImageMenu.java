package orange.wz.gui.component.menu;

import orange.wz.gui.component.panel.EditPane;

import javax.swing.*;

public final class WzImageMenu extends TreeMenu {
    public WzImageMenu(EditPane editPane) {
        super(editPane);

        add(btnSubNodeForList);
        add(btnCopy);
        add(btnPaste);
        add(btnDelete);
        add(btnExport);
        add(btnLocalize);
        add(btnImgCompare);
        add(btnDiff);
        add(btnDiffWithSelection);
        add(btnLocateInView);
        add(btnExpandAll);
        add(btnCollapseAll);
        add(btnExpandDiff);
        add(btnImgFinder);
        add(btnOutlink);
        add(btnOrderAndRename);
        add(btnDelChild);
        add(btnChangeCavFmt);
        add(btnScaleImg);
        add(btnChangeNodeName);
        add(btnChangeIntNodeValue);
        add(btnStepInt);
        add(btnRawToIcon);
        add(btnChangeCavOrigin);
    }

    public JMenuItem getBtnPaste() {
        return btnPaste;
    }

    public JMenuItem getBtnDelete() {
        return btnDelete;
    }

    public JMenuItem getBtnCopy() {
        return btnCopy;
    }
}
