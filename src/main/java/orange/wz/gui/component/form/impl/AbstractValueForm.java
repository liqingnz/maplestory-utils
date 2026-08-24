package orange.wz.gui.component.form.impl;

import lombok.Getter;
import orange.wz.gui.MainFrame;
import orange.wz.gui.component.canvas.WrapLayout;
import orange.wz.gui.component.form.FormSaveHandler;
import orange.wz.gui.component.form.data.NodeFormData;
import orange.wz.gui.component.panel.EditPane;
import orange.wz.provider.WzObject;

import javax.swing.*;
import java.awt.*;

public abstract class AbstractValueForm {
    protected final static int defaultColumns = 30;
    @Getter
    protected final JPanel valuePane = new JPanel(new BorderLayout());
    protected final JPanel topLeftPanel = new JPanel(new GridBagLayout());
    // 按钮区用 WrapLayout：面板变窄（比如打开右侧视图）时按钮自动换行，避免被裁切
    protected final JPanel bottomRightPanel = new JPanel(new WrapLayout(FlowLayout.RIGHT, 5, 5));
    protected final JTextField nameInput = new JTextField(defaultColumns);
    protected final JTextField typeInput = new JTextField(defaultColumns);

    private int topPanelRow = 0;

    private EditPane editPane;
    private WzObject curWzObject;

    protected AbstractValueForm() {
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(topLeftPanel, BorderLayout.CENTER);
        valuePane.add(topPanel, BorderLayout.NORTH);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomRightPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
        bottomPanel.add(bottomRightPanel, BorderLayout.CENTER);
        valuePane.add(bottomPanel, BorderLayout.SOUTH);

        topLeftPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        addRow(MainFrame.i18n.get("form.name"), nameInput);

        typeInput.setEditable(false);
        addRow(MainFrame.i18n.get("form.type"), typeInput);

        JButton saveBtn = new JButton(MainFrame.i18n.get("save"));
        saveBtn.addActionListener(e -> FormSaveHandler.saveClick(curWzObject, editPane));
        addButton(saveBtn);
    }

    protected JLabel addRow(String label, JComponent field) {
        // 标签 gbc
        GridBagConstraints labelGbc = baseGbc();
        labelGbc.gridx = 0;
        labelGbc.gridy = topPanelRow;
        labelGbc.weightx = 0; // 标签不拉伸
        JLabel labelComp = new JLabel(label);
        topLeftPanel.add(labelComp, labelGbc);

        // 输入框 gbc
        GridBagConstraints fieldGbc = baseGbc();
        fieldGbc.gridx = 1;
        fieldGbc.gridy = topPanelRow;
        fieldGbc.weightx = 1.0; // 输入框水平拉伸
        fieldGbc.fill = GridBagConstraints.HORIZONTAL;
        topLeftPanel.add(field, fieldGbc);

        topPanelRow++;
        return labelComp;
    }

    protected void addButton(JButton button) {
        bottomRightPanel.add(button);
    }

    protected GridBagConstraints baseGbc() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        return gbc;
    }

    protected void setData(String name, String type, WzObject wzObject, EditPane editPane) {
        nameInput.setText(name);
        typeInput.setText(type);
        this.curWzObject = wzObject;
        this.editPane = editPane;
    }

    public void onHide() {
        curWzObject = null;
        hideDiffCompare();
    }

    // 视图对比：节点有差异标记时显示对侧视图值与替换按钮 ------------------------------------------------------------------------
    private JLabel diffValueLabel;
    private JTextField diffValueField;
    private final java.util.List<JButton> diffButtons = new java.util.ArrayList<>();
    private Runnable valuesFromViewAction; // 使用视图值替换（视图 -> 本侧）
    private Runnable valuesToViewAction;   // 替换视图值（本侧 -> 视图）
    private Runnable nodeFromViewAction;   // 视图节点替换此节点（整节点，视图 -> 本侧）
    private Runnable nodeToViewAction;     // 替换视图节点（整节点，本侧 -> 视图）

    /**
     * 显示对侧视图值的行和四个替换按钮（首次调用时才创建，值行追加在子类值行之后，按钮排在保存按钮下一行）
     *
     * @param otherValue     对侧视图值的文本
     * @param valuesFromView 使用视图值替换（值，视图 -> 本侧）
     * @param valuesToView   替换视图值（值，本侧 -> 视图）
     * @param nodeFromView   视图节点替换此节点（整节点，视图 -> 本侧）
     * @param nodeToView     替换视图节点（整节点，本侧 -> 视图）
     */
    public void showDiffCompare(String otherValue, Runnable valuesFromView, Runnable valuesToView,
                                Runnable nodeFromView, Runnable nodeToView) {
        if (diffValueField == null) {
            diffValueField = new JTextField(defaultColumns);
            diffValueField.setEditable(false);
            diffValueLabel = addRow(MainFrame.i18n.get("form.diff_value"), diffValueField);

            addDiffButton(MainFrame.i18n.get("form.diff_replace"), () -> valuesFromViewAction);
            addDiffButton(MainFrame.i18n.get("form.diff_replace_to_view"), () -> valuesToViewAction);
            addDiffButton(MainFrame.i18n.get("form.diff_replace_node_from_view"), () -> nodeFromViewAction);
            addDiffButton(MainFrame.i18n.get("form.diff_replace_node_to_view"), () -> nodeToViewAction);
        }

        valuesFromViewAction = valuesFromView;
        valuesToViewAction = valuesToView;
        nodeFromViewAction = nodeFromView;
        nodeToViewAction = nodeToView;
        diffValueField.setText(otherValue);
        setDiffCompareVisible(true);

        // 当前节点不支持的替换方式置灰（例如两侧类型不同时只能整节点替换）
        Runnable[] actions = {valuesFromView, valuesToView, nodeFromView, nodeToView};
        for (int i = 0; i < diffButtons.size() && i < actions.length; i++) {
            diffButtons.get(i).setEnabled(actions[i] != null);
        }
    }

    private void addDiffButton(String text, java.util.function.Supplier<Runnable> actionSupplier) {
        JButton button = new JButton(text);
        button.addActionListener(e -> {
            Runnable action = actionSupplier.get();
            if (action != null) action.run();
        });

        bottomRightPanel.add(button);
        diffButtons.add(button);
    }

    public void hideDiffCompare() {
        valuesFromViewAction = null;
        valuesToViewAction = null;
        nodeFromViewAction = null;
        nodeToViewAction = null;
        setDiffCompareVisible(false);
        hideGhostCopy();
    }

    // 占位节点的"复制视图节点"按钮 -----------------------------------------------------------------------------------------
    private JButton ghostCopyBtn;
    private Runnable ghostCopyAction;

    /**
     * 选中占位节点时显示"复制视图节点"按钮，把对侧的真实节点完整复制到本侧
     */
    public void showGhostCopy(Runnable copyAction) {
        if (ghostCopyBtn == null) {
            ghostCopyBtn = new JButton(MainFrame.i18n.get("form.diff_copy_from_view"));
            ghostCopyBtn.addActionListener(e -> {
                if (ghostCopyAction != null) ghostCopyAction.run();
            });
            bottomRightPanel.add(ghostCopyBtn);
        }

        ghostCopyAction = copyAction;
        ghostCopyBtn.setVisible(true);
        bottomRightPanel.revalidate();
    }

    private void hideGhostCopy() {
        ghostCopyAction = null;
        if (ghostCopyBtn != null) {
            ghostCopyBtn.setVisible(false);
        }
    }

    private void setDiffCompareVisible(boolean visible) {
        if (diffValueField == null) return;
        diffValueLabel.setVisible(visible);
        diffValueField.setVisible(visible);
        diffButtons.forEach(button -> button.setVisible(visible));
    }

    public abstract NodeFormData getData();
}

