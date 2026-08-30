package orange.wz.gui.component.form.impl;

import lombok.extern.slf4j.Slf4j;
import orange.wz.gui.MainFrame;
import orange.wz.gui.component.form.data.NodeFormData;
import orange.wz.gui.component.panel.EditPane;
import orange.wz.provider.WzDirectory;
import orange.wz.provider.WzFolder;
import orange.wz.provider.WzImage;
import orange.wz.provider.WzImageProperty;
import orange.wz.provider.WzObject;
import orange.wz.provider.properties.*;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Slf4j
public class NodeForm extends AbstractValueForm {
    /**
     * 优先展示的节点，按这里的顺序排在最前面
     */
    private static final List<String> HEAD_NODES = List.of("icon", "iconRaw", "cash");

    /**
     * 一次最多展示多少行，节点太多时只展示前面这些
     */
    private static final int MAX_ROWS = 100;

    /**
     * 图片行的固定边长，避免切换节点时行高跟着图片尺寸变化导致内容上下跳动
     */
    private static final int CANVAS_BOX_SIZE = 64;

    private final JPanel previewPane = new JPanel(new GridBagLayout());
    private final JScrollPane previewScrollPane = new JScrollPane(previewPane);
    private int previewRow = 0;

    public NodeForm() {
        super();

        previewScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        previewScrollPane.setVisible(false);

        valuePane.add(previewScrollPane, BorderLayout.CENTER);
    }

    public void setData(String name, String type, WzObject wzObject, EditPane editPane) {
        super.setData(name, type, wzObject, editPane);
        // 默认不展示预览，需要预览的节点会在之后单独调用 setInfoPreview / setChildrenPreview
        clearPreview();
    }

    /**
     * 找节点下的 info 子节点，img 会先解析；没有 info 时返回 null
     *
     * @param wzObject 目标节点
     */
    public static WzImageProperty findInfo(WzObject wzObject) {
        return switch (wzObject) {
            // img 的子节点要先解析出来
            case WzImage image -> parse(image) ? image.getChild("info") : null;
            // 值节点的 children 是 null，只有 List 类型的节点才能取子节点
            case WzImageProperty property -> property.isListProperty() ? property.getChild("info") : null;
            case null, default -> null;
        };
    }

    /**
     * info 预览：把 info 节点的直接子节点展示到名称/类型的下方
     *
     * @param info 目标节点下的 info 节点，为 null 时只清空
     */
    public void setInfoPreview(WzImageProperty info) {
        clearPreview();
        if (info == null) return;

        setPreviewTitle(MainFrame.i18n.get("form.preview.info"));

        // 值节点的 children 是 null，只有 List 类型的节点才能取子节点
        List<WzImageProperty> children = info.isListProperty() ? info.getChildren() : List.of();
        if (children.isEmpty()) {
            addHintRow(MainFrame.i18n.get("form.preview.empty_info"));
        } else {
            addPropertyRows(children);
        }

        addFiller();
        showPreview();
    }

    /**
     * 默认预览：把节点的下一级展示到名称/类型的下方
     *
     * @param wzObject 目标节点，为 null 时只清空
     */
    public void setChildrenPreview(WzObject wzObject) {
        clearPreview();
        if (wzObject == null) return;

        setPreviewTitle(MainFrame.i18n.get("form.preview.children"));

        switch (wzObject) {
            case WzImage image -> {
                // img 的子节点要先解析出来
                if (!parse(image)) {
                    addHintRow(MainFrame.i18n.get("form.preview.parse_failed", image.getStatus().getMessage()));
                    showPreview();
                    return;
                }
                addPropertyRows(image.getChildren());
            }
            // 值节点的 children 是 null，只有 List 类型的节点才能取子节点
            case WzImageProperty property -> addPropertyRows(property.isListProperty() ? property.getChildren() : List.of());
            case WzDirectory directory -> addObjectRows(directory.getChildren());
            // 文件夹的 getChildren() 会顺带把目录下的 wz 都读进来，没展开过就不要在单击时触发
            case WzFolder folder -> addObjectRows(folder.countChildren() == 0 ? List.of() : folder.getChildren());
            default -> addHintRow(MainFrame.i18n.get("form.preview.empty"));
        }

        addFiller();
        showPreview();
    }

    @Override
    public void onHide() {
        super.onHide();
        clearPreview();
    }

    @Override
    public NodeFormData getData() {
        return new NodeFormData(nameInput.getText(), typeInput.getText());
    }

    private static boolean parse(WzImage wzImage) {
        try {
            return wzImage.parse();
        } catch (Exception e) {
            log.error("解析 img 失败: {}", wzImage.getPath(), e);
            return false;
        }
    }

    /**
     * WzImageProperty 子节点，值按类型渲染
     */
    private void addPropertyRows(List<WzImageProperty> children) {
        if (children == null || children.isEmpty()) {
            addHintRow(MainFrame.i18n.get("form.preview.empty"));
            return;
        }

        addRows(children, WzImageProperty::getName, this::createValueComponent);
    }

    /**
     * 文件夹 / wz 目录下的子节点，只展示类型
     * <p>
     * 这两种节点是展开时才把子节点读进来的，没有子节点基本等于还没展开
     */
    private void addObjectRows(List<WzObject> children) {
        if (children == null || children.isEmpty()) {
            addHintRow(MainFrame.i18n.get("form.preview.not_loaded"));
            return;
        }

        addRows(children, WzObject::getName, child -> readOnlyField(child.getType().name()));
    }

    private <T> void addRows(List<T> children, Function<T, String> nameGetter, Function<T, JComponent> valueGetter) {
        List<T> sorted = sortByHeadNodes(children, nameGetter);

        int shown = Math.min(sorted.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            T child = sorted.get(i);
            addInfoRow(nameGetter.apply(child), valueGetter.apply(child));
        }

        if (sorted.size() > shown) {
            addHintRow(MainFrame.i18n.get("form.preview.more", sorted.size() - shown));
        }
    }

    /**
     * icon 类节点排到最前面，其余节点保持原始顺序
     */
    private <T> List<T> sortByHeadNodes(List<T> children, Function<T, String> nameGetter) {
        List<T> sorted = new ArrayList<>(children);
        sorted.sort(Comparator.comparingInt((T child) -> headIndex(nameGetter.apply(child))));
        return sorted;
    }

    private int headIndex(String name) {
        for (int i = 0; i < HEAD_NODES.size(); i++) {
            if (HEAD_NODES.get(i).equalsIgnoreCase(name)) return i;
        }
        // 其它带 icon 的节点，例如 iconD / icon2 / stand1Icon
        if (name != null && name.toLowerCase().contains("icon")) return HEAD_NODES.size();
        return HEAD_NODES.size() + 1;
    }

    private void setPreviewTitle(String title) {
        previewScrollPane.setBorder(BorderFactory.createTitledBorder(title));
    }

    private void clearPreview() {
        previewPane.removeAll();
        previewRow = 0;
        previewScrollPane.setVisible(false);
        valuePane.revalidate();
        valuePane.repaint();
    }

    private void showPreview() {
        previewScrollPane.setVisible(true);
        previewScrollPane.getVerticalScrollBar().setValue(0);
        valuePane.revalidate();
        valuePane.repaint();
    }

    /**
     * 和名称/类型一样的一行 label + 值
     */
    private void addInfoRow(String label, JComponent value) {
        GridBagConstraints labelGbc = baseGbc();
        labelGbc.gridx = 0;
        labelGbc.gridy = previewRow;
        labelGbc.weightx = 0; // 标签不拉伸
        labelGbc.anchor = GridBagConstraints.NORTHWEST;
        previewPane.add(new JLabel(label + ":"), labelGbc);

        GridBagConstraints valueGbc = baseGbc();
        valueGbc.gridx = 1;
        valueGbc.gridy = previewRow;
        valueGbc.anchor = GridBagConstraints.NORTHWEST;
        if (value instanceof JLabel) { // 图片和提示文字保持原始大小
            valueGbc.weightx = 1.0;
            valueGbc.fill = GridBagConstraints.NONE;
        } else {
            valueGbc.weightx = 1.0;
            valueGbc.fill = GridBagConstraints.HORIZONTAL;
        }
        previewPane.add(value, valueGbc);

        previewRow++;
    }

    private void addHintRow(String hint) {
        GridBagConstraints gbc = baseGbc();
        gbc.gridx = 0;
        gbc.gridy = previewRow;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        previewPane.add(new JLabel(hint), gbc);

        previewRow++;
    }

    /**
     * 占位行，把上面的内容顶到面板顶部
     */
    private void addFiller() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = previewRow;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        previewPane.add(Box.createVerticalGlue(), gbc);

        previewRow++;
    }

    private JComponent createValueComponent(WzImageProperty property) {
        return switch (property) {
            case WzCanvasProperty p -> createCanvasComponent(p);
            case WzIntProperty p -> readOnlyField(String.valueOf(p.getValue()));
            case WzShortProperty p -> readOnlyField(String.valueOf(p.getValue()));
            case WzLongProperty p -> readOnlyField(String.valueOf(p.getValue()));
            case WzFloatProperty p -> readOnlyField(String.valueOf(p.getValue()));
            case WzDoubleProperty p -> readOnlyField(String.valueOf(p.getValue()));
            case WzStringProperty p -> readOnlyField(p.getValue());
            case WzUOLProperty p -> readOnlyField(p.getValue());
            case WzLuaProperty p -> readOnlyField(p.getString());
            case WzVectorProperty p -> readOnlyField(p.getX() + ", " + p.getY());
            case WzSoundProperty p -> readOnlyField(MainFrame.i18n.get("form.preview.sound", p.getLenMs()));
            case WzRawDataProperty p -> readOnlyField(MainFrame.i18n.get("form.preview.raw", p.getLength()));
            case WzListProperty p -> readOnlyField(MainFrame.i18n.get("form.preview.list", p.getChildren().size()));
            case WzConvexProperty p -> readOnlyField(MainFrame.i18n.get("form.preview.list", p.getChildren().size()));
            case WzNullProperty ignored -> readOnlyField("");
            default -> readOnlyField(property.getType().name());
        };
    }

    private JComponent createCanvasComponent(WzCanvasProperty canvas) {
        try {
            BufferedImage image = canvas.getPngImage(true);
            if (image != null) {
                JLabel label = new JLabel(new ImageIcon(fitToBox(image)));
                label.setHorizontalAlignment(SwingConstants.CENTER);
                label.setVerticalAlignment(SwingConstants.CENTER);

                // 不论图片多大，行都占同样的高度
                Dimension box = new Dimension(CANVAS_BOX_SIZE, CANVAS_BOX_SIZE);
                label.setPreferredSize(box);
                label.setMinimumSize(box);

                label.setBorder(BorderFactory.createLineBorder(Color.GRAY));
                label.setToolTipText(MainFrame.i18n.get("form.preview.canvas", canvas.getWidth(), canvas.getHeight()));
                return label;
            }
        } catch (Exception e) {
            log.error("解析图片失败: {}", canvas.getPath(), e);
        }
        return readOnlyField(MainFrame.i18n.get("form.preview.decode_failed"));
    }

    /**
     * 图片超过固定边长时等比缩小，没超过就保持原始大小
     */
    private BufferedImage fitToBox(BufferedImage image) {
        int longSide = Math.max(image.getWidth(), image.getHeight());
        if (longSide <= CANVAS_BOX_SIZE) return image;

        double ratio = (double) CANVAS_BOX_SIZE / longSide;
        int width = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(image.getHeight() * ratio));

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(image, 0, 0, width, height, null);
        g2.dispose();

        return scaled;
    }

    private JTextField readOnlyField(String text) {
        JTextField field = new JTextField(text == null ? "" : text, defaultColumns);
        field.setEditable(false);
        field.setCaretPosition(0);
        return field;
    }
}
