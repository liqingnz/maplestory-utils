package orange.wz.gui.utils;

/**
 * 视图对比的单条差异
 * <p>
 * 两侧的路径都是"树锚定"的完整路径（从树 Root 的直接子节点开始，/ 分隔）。
 * 由于两侧的文件夹层级可能不同（一侧直接加载 wz，另一侧从文件夹加载），前缀可能不一样。
 *
 * @param thisPath   本侧（发起对比一侧）路径，仅对侧存在时为 null
 * @param otherPath  对侧路径，仅本侧存在时为 null
 * @param kind       差异类型
 * @param thisValue  本侧值摘要
 * @param otherValue 对侧值摘要
 * @param ghostPath  占位节点路径：节点缺失的那一侧应插入半透明占位节点的位置（ADDED 时在对侧、MISSING 时在本侧），其余为 null
 */
public record DiffResult(String thisPath, String otherPath, DiffKind kind, String thisValue, String otherValue,
                         String ghostPath) {

    /** 用于列表展示的路径 */
    public String displayPath() {
        return thisPath != null ? thisPath : otherPath;
    }
}
