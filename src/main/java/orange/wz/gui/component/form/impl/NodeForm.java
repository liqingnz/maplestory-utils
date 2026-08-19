package orange.wz.gui.component.form.impl;

import lombok.extern.slf4j.Slf4j;
import orange.wz.gui.MainFrame;
import orange.wz.gui.component.form.data.NodeFormData;
import orange.wz.gui.component.panel.EditPane;
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

@Slf4j
public class NodeForm extends AbstractValueForm {
    /**
     * info 下优先展示的节点，按这里的顺序排在最前面
     */
    private static final List<String> INFO_HEAD_NODES = List.of("icon", "iconRaw", "cash");

    /**
     * 图片行的固定边长，避免切换节点时行高跟着图片尺寸变化导致内容上下跳动
     */
    private static final int CANVAS_BOX_SIZE = 64;

    private final JPanel infoPane = new JPanel(new GridBagLayout());
    private final JScrollPane infoScrollPane = new JScrollPane(infoPane);
    private int infoRow = 0;

    public NodeForm() {
        super();

        infoScrollPane.setBorder(BorderFactory.createTitledBorder(MainFrame.i18n.get("form.character.info")));
        infoScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        infoScrollPane.setVisible(false);

        valuePane.add(infoScrollPane, BorderLayout.CENTER);
    }

    public void setData(String name, String type, WzObject wzObject, EditPane editPane) {
        super.setData(name, type, wzObject, editPane);
        // 默认不展示 info，img 节点会在之后单独调用 setImageInfo
        clearImageInfo();
    }

    /**
     * 尝试解析 img 下的 info 节点，把它的直接子节点展示到名称/类型的下方
     *
     * @param wzImage 目标 img，为 null 时只清空
     */
    public void setImageInfo(WzImage wzImage) {
        clearImageInfo();
        if (wzImage == null) return;

        boolean parsed;
        try {
            parsed = wzImage.parse();
        } catch (Exception e) {
            log.error("解析 img 失败: {}", wzImage.getPath(), e);
            parsed = false;
        }
        if (!parsed) {
            addHintRow(MainFrame.i18n.get("form.character.parse_failed", wzImage.getStatus().getMessage()));
            showImageInfo();
            return;
        }

        WzImageProperty info = wzImage.getChild("info");
        if (info == null) {
            addHintRow(MainFrame.i18n.get("form.character.no_info"));
            showImageInfo();
            return;
        }

        // 值节点的 children 是 null，只有 List 类型的节点才能取子节点
        List<WzImageProperty> children = info.isListProperty() ? info.getChildren() : List.of();
        if (children.isEmpty()) {
            addHintRow(MainFrame.i18n.get("form.character.empty_info"));
        } else {
            for (WzImageProperty child : sortInfoChildren(children)) {
                addInfoRow(child.getName(), createValueComponent(child));
            }
        }

        addFiller();
        showImageInfo();
    }

    @Override
    public void onHide() {
        super.onHide();
        clearImageInfo();
    }

    @Override
    public NodeFormData getData() {
        return new NodeFormData(nameInput.getText(), typeInput.getText());
    }

    /**
     * icon / iconRaw 排到最前面，其余节点保持 img 里的原始顺序
     */
    private List<WzImageProperty> sortInfoChildren(List<WzImageProperty> children) {
        List<WzImageProperty> sorted = new ArrayList<>(children);
        sorted.sort(Comparator.comparingInt(child -> headIndex(child.getName())));
        return sorted;
    }

    private int headIndex(String name) {
        for (int i = 0; i < INFO_HEAD_NODES.size(); i++) {
            if (INFO_HEAD_NODES.get(i).equalsIgnoreCase(name)) return i;
        }
        return INFO_HEAD_NODES.size();
    }

    private void clearImageInfo() {
        infoPane.removeAll();
        infoRow = 0;
        infoScrollPane.setVisible(false);
        valuePane.revalidate();
        valuePane.repaint();
    }

    private void showImageInfo() {
        infoScrollPane.setVisible(true);
        valuePane.revalidate();
        valuePane.repaint();
    }

    /**
     * 和名称/类型一样的一行 label + 值
     */
    private void addInfoRow(String label, JComponent value) {
        GridBagConstraints labelGbc = baseGbc();
        labelGbc.gridx = 0;
        labelGbc.gridy = infoRow;
        labelGbc.weightx = 0; // 标签不拉伸
        labelGbc.anchor = GridBagConstraints.NORTHWEST;
        infoPane.add(new JLabel(label + ":"), labelGbc);

        GridBagConstraints valueGbc = baseGbc();
        valueGbc.gridx = 1;
        valueGbc.gridy = infoRow;
        valueGbc.anchor = GridBagConstraints.NORTHWEST;
        if (value instanceof JLabel) { // 图片和提示文字保持原始大小
            valueGbc.weightx = 1.0;
            valueGbc.fill = GridBagConstraints.NONE;
        } else {
            valueGbc.weightx = 1.0;
            valueGbc.fill = GridBagConstraints.HORIZONTAL;
        }
        infoPane.add(value, valueGbc);

        infoRow++;
    }

    private void addHintRow(String hint) {
        GridBagConstraints gbc = baseGbc();
        gbc.gridx = 0;
        gbc.gridy = infoRow;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        infoPane.add(new JLabel(hint), gbc);

        infoRow++;
    }

    /**
     * 占位行，把上面的内容顶到面板顶部
     */
    private void addFiller() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = infoRow;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        infoPane.add(Box.createVerticalGlue(), gbc);

        infoRow++;
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
            case WzSoundProperty p -> readOnlyField(MainFrame.i18n.get("form.character.sound", p.getLenMs()));
            case WzRawDataProperty p -> readOnlyField(MainFrame.i18n.get("form.character.raw", p.getLength()));
            case WzListProperty p -> readOnlyField(MainFrame.i18n.get("form.character.list", p.getChildren().size()));
            case WzConvexProperty p -> readOnlyField(MainFrame.i18n.get("form.character.list", p.getChildren().size()));
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
                label.setToolTipText(MainFrame.i18n.get("form.character.canvas", canvas.getWidth(), canvas.getHeight()));
                return label;
            }
        } catch (Exception e) {
            log.error("解析图片失败: {}", canvas.getPath(), e);
        }
        return readOnlyField(MainFrame.i18n.get("form.character.decode_failed"));
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
