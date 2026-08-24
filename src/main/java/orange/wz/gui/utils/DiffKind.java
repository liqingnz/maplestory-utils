package orange.wz.gui.utils;

import java.awt.*;

/**
 * 视图对比的差异类型
 */
public enum DiffKind {
    /** 仅本侧存在（发起对比的一侧） */
    ADDED(new Color(0, 153, 0)),
    /** 仅对侧存在 */
    MISSING(new Color(30, 110, 220)),
    /** 值不同 */
    MODIFIED(new Color(230, 120, 0)),
    /** 同名但类型不同 */
    TYPE_CHANGED(new Color(220, 0, 60)),
    /** 自身无差异，但子孙节点含差异（仅用于树标注，不进结果列表） */
    PARENT(new Color(130, 60, 220));

    private final Color color;

    DiffKind(Color color) {
        this.color = color;
    }

    public Color getColor() {
        return color;
    }

    public String getI18nKey() {
        return "diff.kind." + name().toLowerCase();
    }
}
