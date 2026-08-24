package orange.wz.gui.utils;

import orange.wz.provider.WzObject;
import orange.wz.provider.tools.WzType;

/**
 * 视图对比的占位节点：本侧缺失、对侧存在的节点，在本侧树里用它显示一个半透明的名字提示。
 * <p>
 * 只存在于 JTree 里，不挂进 Wz 数据模型（父节点的 children 不含它），因此保存/导出不受影响。
 */
public final class DiffGhostObject extends WzObject {

    public DiffGhostObject(String name) {
        super(name, WzType.NULL_PROPERTY, null);
    }

    @Override
    public WzObject deepClone(WzObject parent) {
        throw new UnsupportedOperationException("占位节点不支持克隆");
    }
}
