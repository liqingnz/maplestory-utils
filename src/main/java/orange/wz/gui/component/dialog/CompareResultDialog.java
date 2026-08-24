package orange.wz.gui.component.dialog;

import orange.wz.gui.MainFrame;
import orange.wz.gui.component.panel.EditPane;
import orange.wz.gui.utils.DiffKind;
import orange.wz.gui.utils.DiffResult;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 视图对比结果对话框：展示差异列表，双击跳转到左右两个视图里的对应节点
 */
public final class CompareResultDialog extends JDialog {

    private final JTable table;
    private final TableRowSorter<DefaultTableModel> sorter;
    private String filterText = "";

    public CompareResultDialog(Frame owner, String title, List<DiffResult> items, EditPane thisPane, EditPane otherPane) {
        super(owner, title, false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(800, 450);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        // 搜索框
        JTextField searchField = new JTextField();
        searchField.setToolTipText(MainFrame.i18n.get("diff.filter.tip"));
        add(searchField, BorderLayout.NORTH);

        String[] columns = {
                MainFrame.i18n.get("diff.col.kind"),
                MainFrame.i18n.get("diff.col.path"),
                MainFrame.i18n.get("diff.col.this"),
                MainFrame.i18n.get("diff.col.other")
        };
        Object[][] data = new Object[items.size()][4];
        for (int i = 0; i < items.size(); i++) {
            DiffResult r = items.get(i);
            data[i][0] = MainFrame.i18n.get(r.kind().getI18nKey());
            data[i][1] = r.displayPath();
            data[i][2] = r.thisValue();
            data[i][3] = r.otherValue();
        }

        DefaultTableModel model = new DefaultTableModel(data, columns) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(0).setMaxWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(380);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);

        // 按差异类型着色 + 过滤高亮渲染器
        TableCellRenderer highlightRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                                                           boolean isSelected, boolean hasFocus,
                                                           int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String text = value != null ? value.toString() : "";
                setToolTipText(text.isEmpty() ? null : text);

                setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
                if (isSelected) {
                    setForeground(table.getSelectionForeground());
                } else {
                    int modelRow = table.convertRowIndexToModel(row);
                    DiffKind kind = items.get(modelRow).kind();
                    setForeground(kind.getColor());
                }

                if (!filterText.isEmpty()) {
                    String regex = "(?i)" + Pattern.quote(filterText);
                    String displayText = text.replaceAll(regex, "<span style='background:yellow;color:" +
                            (isSelected ? "black" : "red") + "'>$0</span>");
                    setText("<html>" + displayText + "</html>");
                } else {
                    setText(text);
                }

                return this;
            }
        };

        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(highlightRenderer);
        }

        // 双击跳转到左右视图里的对应节点
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        int modelRow = table.convertRowIndexToModel(row);
                        DiffResult r = items.get(modelRow);
                        if (r.thisPath() != null) thisPane.focusNodeByPath(r.thisPath());
                        if (r.otherPath() != null) otherPane.focusNodeByPath(r.otherPath());
                    }
                }
            }
        });

        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);

        JScrollPane scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);

        // 搜索框实时过滤和高亮
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void updateFilter() {
                filterText = searchField.getText().trim();
                if (filterText.isEmpty()) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(filterText)));
                }
                table.repaint();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateFilter();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateFilter();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateFilter();
            }
        });

        // 设置 Ctrl+F 快捷键
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), "focusSearch");
        am.put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
        });
    }
}
