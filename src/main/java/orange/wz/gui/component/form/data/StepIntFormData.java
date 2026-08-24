package orange.wz.gui.component.form.data;

import lombok.Getter;

/**
 * 阶梯修改 int 的输入：节点名 + 起始值 + 结束值（等分给选中的各节点）
 */
@Getter
public class StepIntFormData extends NodeFormData {
    private final int start;
    private final int end;

    public StepIntFormData(String name, int start, int end) {
        super(name, "Int");
        this.start = start;
        this.end = end;
    }
}
