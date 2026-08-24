package orange.wz.gui.component.dialog;

import orange.wz.gui.MainFrame;
import orange.wz.gui.component.form.data.StepIntFormData;
import orange.wz.gui.component.panel.EditPane;

import javax.swing.*;

/**
 * 阶梯修改 int：输入节点名、起始值、结束值，按树顺序等分给选中的各节点
 */
public final class StepIntDialog extends NodeDialog {
    private final JTextField startField = new JTextField(20);
    private final JTextField endField = new JTextField(20);

    public StepIntDialog(String title, EditPane editPane) {
        super(title, editPane);

        addRow(MainFrame.i18n.get("stepint.start"), startField);
        addRow(MainFrame.i18n.get("stepint.end"), endField);
    }

    @Override
    public StepIntFormData getData() {
        if (showDialog() != JOptionPane.OK_OPTION) {
            return null;
        }

        int start;
        int end;
        try {
            start = Integer.parseInt(startField.getText().trim());
            end = Integer.parseInt(endField.getText().trim());
        } catch (NumberFormatException e) {
            return null;
        }

        return new StepIntFormData(nameField.getText().trim(), start, end);
    }
}
