package orange.wz.gui.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import orange.wz.gui.MainFrame;
import orange.wz.provider.WzDirectory;
import orange.wz.provider.WzFolder;
import orange.wz.provider.WzImage;
import orange.wz.provider.WzImageProperty;
import orange.wz.provider.WzObject;
import orange.wz.provider.properties.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 视图对比引擎：递归对比两侧的同路径节点，产出差异列表并把差异节点登记到左右两侧的标记表。
 * <p>
 * 递归骨架仿照 {@link ChineseUtil}：按名字配对、逐层递归、懒解析节点先 parse()。
 * 由于每个加载单元（文件夹 / wz / img）的 {@code getPath()} 都是独立路径空间，
 * 这里对比过程自行维护两侧的"树锚定"完整路径，供结果列表跳转和树展开使用。
 */
@Slf4j
public final class WzDiffUtil {

    private WzDiffUtil() {
    }

    /**
     * 对比上下文：收集差异结果，并维护左右两侧的节点标记表（供树渲染器着色）
     */
    public static final class DiffContext {
        @Getter
        private final List<DiffResult> results = Collections.synchronizedList(new ArrayList<>());
        /** 失败的对比任务（解析失败 / 读取异常等），结果不完整时不能报告"对比完成" */
        @Getter
        private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
        private final Map<WzObject, DiffKind> thisMarks;
        private final Map<WzObject, DiffKind> otherMarks;

        public void addFailure(String message) {
            failures.add(message == null ? "unknown" : message);
        }

        public boolean hasFailures() {
            return !failures.isEmpty();
        }

        /**
         * @param thisMarks  本侧（发起对比一侧）的标记表
         * @param otherMarks 对侧的标记表
         */
        public DiffContext(Map<WzObject, DiffKind> thisMarks, Map<WzObject, DiffKind> otherMarks) {
            this.thisMarks = thisMarks;
            this.otherMarks = otherMarks;
        }

        private void added(WzObject thisObj, String thisPath, WzObject otherParent, String otherGhostPath) {
            results.add(new DiffResult(thisPath, null, DiffKind.ADDED, valueText(thisObj), "", otherGhostPath));
            mark(thisMarks, thisObj, DiffKind.ADDED);
            markAncestors(thisMarks, thisObj);
            mark(otherMarks, otherParent, DiffKind.PARENT);
            markAncestors(otherMarks, otherParent);
        }

        private void missing(WzObject otherObj, String otherPath, WzObject thisParent, String thisGhostPath) {
            results.add(new DiffResult(null, otherPath, DiffKind.MISSING, "", valueText(otherObj), thisGhostPath));
            mark(otherMarks, otherObj, DiffKind.MISSING);
            markAncestors(otherMarks, otherObj);
            mark(thisMarks, thisParent, DiffKind.PARENT);
            markAncestors(thisMarks, thisParent);
        }

        private void modified(WzObject thisObj, String thisPath, WzObject otherObj, String otherPath) {
            record(thisObj, thisPath, otherObj, otherPath, DiffKind.MODIFIED);
        }

        private void typeChanged(WzObject thisObj, String thisPath, WzObject otherObj, String otherPath) {
            record(thisObj, thisPath, otherObj, otherPath, DiffKind.TYPE_CHANGED);
        }

        private void record(WzObject thisObj, String thisPath, WzObject otherObj, String otherPath, DiffKind kind) {
            results.add(new DiffResult(thisPath, otherPath, kind, valueText(thisObj), valueText(otherObj), null));
            mark(thisMarks, thisObj, kind);
            markAncestors(thisMarks, thisObj);
            mark(otherMarks, otherObj, kind);
            markAncestors(otherMarks, otherObj);
        }

        private void mark(Map<WzObject, DiffKind> marks, WzObject obj, DiffKind kind) {
            if (obj == null) return;
            DiffKind old = marks.get(obj);
            if (old == null || old == DiffKind.PARENT) {
                marks.put(obj, kind);
            }
        }

        private void markAncestors(Map<WzObject, DiffKind> marks, WzObject obj) {
            if (obj == null) return;
            for (WzObject p = obj.getParent(); p != null; p = p.getParent()) {
                if (marks.containsKey(p)) continue;
                marks.put(p, DiffKind.PARENT);
            }
        }
    }

    /**
     * 对比入口：文件夹层级先展开成互相独立的文件对任务并行执行（每个 wz/img 文件有自己的 reader，跨文件并行安全；
     * 同一文件内部 reader 有共享读取位置，保持串行），非文件夹则单任务直接跑。
     */
    public static void diffRoot(WzObject a, WzObject b, String aPath, String bPath, DiffContext ctx) {
        List<Callable<Void>> tasks = new ArrayList<>();
        collectDiffTasks(a, b, aPath, bPath, ctx, tasks);

        if (tasks.size() <= 1) {
            try {
                for (Callable<Void> task : tasks) task.call();
            } catch (Exception e) {
                // 失败要记进上下文，否则调用方会把不完整的结果当成"对比完成"
                log.error("对比任务失败: {}", e.getMessage());
                ctx.addFailure(aPath + ": " + e.getMessage());
            }
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    log.error("对比任务失败: {}", cause.getMessage());
                    ctx.addFailure(cause.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.addFailure("对比被中断");
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 把文件夹递归展开成文件对任务；文件夹层级本身的增删差异直接记录
     */
    private static void collectDiffTasks(WzObject a, WzObject b, String aPath, String bPath, DiffContext ctx, List<Callable<Void>> tasks) {
        if (a == null || b == null) return;

        if (a.getType() != b.getType()) {
            ctx.typeChanged(a, aPath, b, bPath);
            return;
        }

        if (a instanceof WzFolder fa && b instanceof WzFolder fb) {
            Map<String, WzObject> bMap = new LinkedHashMap<>();
            fb.getChildren().forEach(child -> bMap.put(child.getName(), child));

            for (WzObject aChild : fa.getChildren()) {
                String name = aChild.getName();
                WzObject bChild = bMap.remove(name);
                if (bChild == null) {
                    ctx.added(aChild, aPath + "/" + name, fb, bPath + "/" + name);
                } else {
                    collectDiffTasks(aChild, bChild, aPath + "/" + name, bPath + "/" + name, ctx, tasks);
                }
            }
            bMap.values().forEach(bChild -> ctx.missing(bChild, bPath + "/" + bChild.getName(), fa, aPath + "/" + bChild.getName()));
        } else {
            tasks.add(() -> {
                diff(a, b, aPath, bPath, ctx);
                return null;
            });
        }
    }

    /**
     * 递归对比两个同名节点，a 为本侧（发起对比一侧），b 为对侧
     *
     * @param aPath a 的树锚定完整路径
     * @param bPath b 的树锚定完整路径
     */
    public static void diff(WzObject a, WzObject b, String aPath, String bPath, DiffContext ctx) {
        if (a == null || b == null) return;

        if (a.getType() != b.getType()) {
            ctx.typeChanged(a, aPath, b, bPath);
            return;
        }

        if (a instanceof WzFolder fa && b instanceof WzFolder fb) {
            diffFolder(fa, fb, aPath, bPath, ctx);
        } else if (a instanceof WzDirectory da && b instanceof WzDirectory db) {
            diffDirectory(da, db, aPath, bPath, ctx);
        } else if (a instanceof WzImage ia && b instanceof WzImage ib) {
            diffImage(ia, ib, aPath, bPath, ctx);
        } else if (a instanceof WzImageProperty pa && b instanceof WzImageProperty pb) {
            diffProperty(pa, pb, aPath, bPath, ctx);
        }
    }

    // 容器递归 ----------------------------------------------------------------------------------------------------------
    private static void diffFolder(WzFolder a, WzFolder b, String aPath, String bPath, DiffContext ctx) {
        Map<String, WzObject> bMap = new LinkedHashMap<>();
        b.getChildren().forEach(child -> bMap.put(child.getName(), child));

        for (WzObject aChild : a.getChildren()) {
            String name = aChild.getName();
            WzObject bChild = bMap.remove(name);
            if (bChild == null) {
                ctx.added(aChild, aPath + "/" + name, b, bPath + "/" + name);
            } else {
                diff(aChild, bChild, aPath + "/" + name, bPath + "/" + name, ctx);
            }
        }
        bMap.values().forEach(bChild -> ctx.missing(bChild, bPath + "/" + bChild.getName(), a, aPath + "/" + bChild.getName()));
    }

    private static void diffDirectory(WzDirectory a, WzDirectory b, String aPath, String bPath, DiffContext ctx) {
        if (a.isWzFile() && !a.getWzFile().parse()) {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", a.getWzFile().getName(), a.getWzFile().getStatus().getMessage()));
            throw new RuntimeException();
        }
        if (b.isWzFile() && !b.getWzFile().parse()) {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", b.getWzFile().getName(), b.getWzFile().getStatus().getMessage()));
            throw new RuntimeException();
        }

        for (WzDirectory aDir : a.getDirectories()) {
            String name = aDir.getName();
            WzDirectory bDir = b.getDirectory(name);
            if (bDir == null) {
                ctx.added(aDir, aPath + "/" + name, b, bPath + "/" + name);
            } else {
                diffDirectory(aDir, bDir, aPath + "/" + name, bPath + "/" + name, ctx);
            }
        }
        for (WzImage aImg : a.getImages()) {
            String name = aImg.getName();
            WzImage bImg = b.getImage(name);
            if (bImg == null) {
                ctx.added(aImg, aPath + "/" + name, b, bPath + "/" + name);
            } else {
                diffImage(aImg, bImg, aPath + "/" + name, bPath + "/" + name, ctx);
            }
        }
        for (WzDirectory bDir : b.getDirectories()) {
            if (a.getDirectory(bDir.getName()) == null) {
                ctx.missing(bDir, bPath + "/" + bDir.getName(), a, aPath + "/" + bDir.getName());
            }
        }
        for (WzImage bImg : b.getImages()) {
            if (a.getImage(bImg.getName()) == null) {
                ctx.missing(bImg, bPath + "/" + bImg.getName(), a, aPath + "/" + bImg.getName());
            }
        }
    }

    private static void diffImage(WzImage a, WzImage b, String aPath, String bPath, DiffContext ctx) {
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("diff.status.running", aPath));

        // 两侧各自的 reader 互相独立，并行解析
        CompletableFuture<Boolean> aParse = CompletableFuture.supplyAsync(a::parse);
        boolean bOk = b.parse();
        boolean aOk = aParse.join();

        if (!aOk) {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", a.getName(), a.getStatus().getMessage()));
            throw new RuntimeException();
        }
        if (!bOk) {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", b.getName(), b.getStatus().getMessage()));
            throw new RuntimeException();
        }

        diffPropertyChildren(a.getChildren(), b.getChildren(), a, b, aPath, bPath, ctx);
    }

    private static void diffPropertyChildren(List<WzImageProperty> aChildren, List<WzImageProperty> bChildren,
                                             WzObject aParent, WzObject bParent,
                                             String aPath, String bPath, DiffContext ctx) {
        Map<String, WzImageProperty> bMap = new LinkedHashMap<>();
        bChildren.forEach(child -> bMap.put(child.getName(), child));

        for (WzImageProperty aChild : aChildren) {
            String name = aChild.getName();
            WzImageProperty bChild = bMap.remove(name);
            if (bChild == null) {
                ctx.added(aChild, aPath + "/" + name, bParent, bPath + "/" + name);
            } else {
                diff(aChild, bChild, aPath + "/" + name, bPath + "/" + name, ctx);
            }
        }
        bMap.values().forEach(bChild -> ctx.missing(bChild, bPath + "/" + bChild.getName(), aParent, aPath + "/" + bChild.getName()));
    }

    // 属性对比 ----------------------------------------------------------------------------------------------------------
    private static void diffProperty(WzImageProperty a, WzImageProperty b, String aPath, String bPath, DiffContext ctx) {
        switch (a) {
            case WzCanvasProperty ca -> {
                WzCanvasProperty cb = (WzCanvasProperty) b;
                if (canvasDiffers(ca, cb)) {
                    ctx.modified(ca, aPath, cb, bPath);
                }
            }
            case WzIntProperty pa -> {
                if (pa.getValue() != ((WzIntProperty) b).getValue()) ctx.modified(a, aPath, b, bPath);
            }
            case WzShortProperty pa -> {
                if (pa.getValue() != ((WzShortProperty) b).getValue()) ctx.modified(a, aPath, b, bPath);
            }
            case WzLongProperty pa -> {
                if (pa.getValue() != ((WzLongProperty) b).getValue()) ctx.modified(a, aPath, b, bPath);
            }
            case WzFloatProperty pa -> {
                if (Float.compare(pa.getValue(), ((WzFloatProperty) b).getValue()) != 0) ctx.modified(a, aPath, b, bPath);
            }
            case WzDoubleProperty pa -> {
                if (Double.compare(pa.getValue(), ((WzDoubleProperty) b).getValue()) != 0) ctx.modified(a, aPath, b, bPath);
            }
            case WzStringProperty pa -> {
                if (!Objects.equals(pa.getValue(), ((WzStringProperty) b).getValue())) ctx.modified(a, aPath, b, bPath);
            }
            case WzUOLProperty pa -> {
                if (!Objects.equals(pa.getValue(), ((WzUOLProperty) b).getValue())) ctx.modified(a, aPath, b, bPath);
            }
            case WzVectorProperty pa -> {
                WzVectorProperty pb = (WzVectorProperty) b;
                if (pa.getX() != pb.getX() || pa.getY() != pb.getY()) ctx.modified(a, aPath, b, bPath);
            }
            case WzSoundProperty pa -> {
                WzSoundProperty pb = (WzSoundProperty) b;
                if (pa.getLenMs() != pb.getLenMs()
                        || !Arrays.equals(pa.getSoundBytes(false), pb.getSoundBytes(false))) {
                    ctx.modified(a, aPath, b, bPath);
                }
            }
            case WzLuaProperty pa -> {
                if (!Objects.equals(pa.getString(), ((WzLuaProperty) b).getString())) ctx.modified(a, aPath, b, bPath);
            }
            case WzRawDataProperty pa -> {
                WzRawDataProperty pb = (WzRawDataProperty) b;
                if (pa.getDataType() != pb.getDataType()
                        || !Arrays.equals(pa.getBytes(false), pb.getBytes(false))) {
                    ctx.modified(a, aPath, b, bPath);
                }
            }
            case WzNullProperty ignored -> {
                // Null 节点没有值，同名即视为一致
            }
            default -> {
            }
        }

        if (a.isListProperty() && b.isListProperty()) {
            diffPropertyChildren(a.getChildren(), b.getChildren(), a, b, aPath, bPath, ctx);
        }
    }

    /**
     * Canvas 是否有差异：先比元信息，再比压缩字节（相同即像素相同，免解码），
     * 压缩字节不同再解码比像素（两侧密钥或压缩方式不同时压缩字节会不同但像素可能一致）
     */
    private static boolean canvasDiffers(WzCanvasProperty a, WzCanvasProperty b) {
        if (a.getWidth() != b.getWidth()
                || a.getHeight() != b.getHeight()
                || a.getFormat() != b.getFormat()
                || a.getScale() != b.getScale()) {
            return true;
        }

        byte[] compressedA = a.getCompressedBytes(false);
        byte[] compressedB = b.getCompressedBytes(false);
        if (compressedA != null && compressedB != null && Arrays.equals(compressedA, compressedB)) {
            return false;
        }

        BufferedImage imgA = a.getPngImage(false);
        BufferedImage imgB = b.getPngImage(false);
        try {
            if (imgA == null || imgB == null) {
                return imgA != imgB;
            }
            int width = imgA.getWidth();
            int height = imgA.getHeight();
            int[] pixelsA = imgA.getRGB(0, 0, width, height, null, 0, width);
            int[] pixelsB = imgB.getRGB(0, 0, width, height, null, 0, width);
            return !Arrays.equals(pixelsA, pixelsB);
        } finally {
            // 释放解码缓存，避免全量对比时内存膨胀。
            // 只清可以重新取回的图片：XML 导入/新建且尚未写入 wz 的 canvas，内存里的图片是唯一数据源，清掉就没了
            a.clearImageIfRecoverable();
            b.clearImageIfRecoverable();
        }
    }

    // 值替换 ----------------------------------------------------------------------------------------------------------

    /**
     * 把 from 节点的值复制到 to 节点（类型必须一致）。支持 8 种值类型和 Canvas 图片。
     *
     * @return 是否复制成功（不支持的类型返回 false）
     */
    public static boolean copyValue(WzObject from, WzObject to) {
        if (from.getType() != to.getType()) return false;

        switch (to) {
            case WzStringProperty p -> p.setValue(((WzStringProperty) from).getValue());
            case WzIntProperty p -> p.setValue(((WzIntProperty) from).getValue());
            case WzShortProperty p -> p.setValue(((WzShortProperty) from).getValue());
            case WzLongProperty p -> p.setValue(((WzLongProperty) from).getValue());
            case WzFloatProperty p -> p.setValue(((WzFloatProperty) from).getValue());
            case WzDoubleProperty p -> p.setValue(((WzDoubleProperty) from).getValue());
            case WzVectorProperty p -> {
                WzVectorProperty f = (WzVectorProperty) from;
                p.setX(f.getX());
                p.setY(f.getY());
            }
            case WzUOLProperty p -> p.setValue(((WzUOLProperty) from).getValue());
            case WzCanvasProperty p -> {
                WzCanvasProperty f = (WzCanvasProperty) from;
                BufferedImage image = f.getPngImage(false);
                if (image == null) return false;
                p.setPng(image, f.getFormat(), f.getScale());
                f.clearImageIfRecoverable(); // 来源图片可能是内存里的唯一副本，不可恢复时保留
            }
            default -> {
                return false;
            }
        }

        if (to instanceof WzImageProperty prop) {
            prop.setTempChanged(true);
            if (prop.getWzImage() != null) {
                prop.getWzImage().setChanged(true);
                prop.getWzImage().setTempChanged(true);
            }
        }
        return true;
    }

    /**
     * 批量替换：递归把 from（对侧视图）子树里被标记"值不同"的节点值复制到 to（本侧）子树。
     * 只处理按名字能配对且类型一致的节点，不增删节点；无标记的子树直接跳过。
     * 替换成功的节点会清掉两侧标记，"含差异"标记自底向上跟着清理。
     *
     * @return 替换的节点数
     */
    public static int applyValues(WzObject from, WzObject to, Map<WzObject, DiffKind> toMarks, Map<WzObject, DiffKind> fromMarks) {
        DiffKind mark = toMarks.get(to);
        if (mark == null) return 0;
        if (from.getType() != to.getType()) return 0;

        int count = 0;

        // 自身值
        if (mark == DiffKind.MODIFIED && copyValue(from, to)) {
            toMarks.remove(to);
            fromMarks.remove(from);
            count++;
        }

        // 子节点递归（按名字配对）
        List<? extends WzObject> toChildren = childrenOf(to);
        if (toChildren != null) {
            Map<String, WzObject> fromMap = new LinkedHashMap<>();
            List<? extends WzObject> fromChildren = childrenOf(from);
            if (fromChildren != null) {
                fromChildren.forEach(child -> fromMap.put(child.getName(), child));
            }

            for (WzObject toChild : toChildren) {
                WzObject fromChild = fromMap.get(toChild.getName());
                if (fromChild != null) {
                    count += applyValues(fromChild, toChild, toMarks, fromMarks);
                }
            }
        }

        // 子树差异都清完后，清掉两侧的"含差异"标记
        cleanupParentMark(to, toMarks);
        cleanupParentMark(from, fromMarks);

        return count;
    }

    /**
     * 递归清掉 obj 及其全部子孙的差异标记（整节点替换后两侧内容一致，标记随之失效）
     */
    public static void clearMarks(WzObject obj, Map<WzObject, DiffKind> marks) {
        marks.remove(obj);
        List<? extends WzObject> children = childrenOf(obj);
        if (children == null) return;
        for (WzObject child : children) {
            clearMarks(child, marks);
        }
    }

    private static void cleanupParentMark(WzObject obj, Map<WzObject, DiffKind> marks) {
        if (marks.get(obj) != DiffKind.PARENT) return;

        List<? extends WzObject> children = childrenOf(obj);
        if (children != null) {
            for (WzObject child : children) {
                if (marks.containsKey(child)) return;
            }
        }
        marks.remove(obj);
        obj.setTempChanged(true); // 子树差异全部消除，标脏提示（与替换过的节点同色）
    }

    /**
     * 统一取子节点列表（未解析的容器返回当前已有的子节点，不触发解析）
     */
    public static List<? extends WzObject> childrenOf(WzObject obj) {
        return switch (obj) {
            case WzFolder folder -> folder.getChildren();
            case WzDirectory dir -> dir.getChildren();
            case WzImage img -> img.getChildren();
            case WzImageProperty prop when prop.isListProperty() -> prop.getChildren();
            default -> null;
        };
    }

    /**
     * 节点值摘要（用于结果列表展示）
     */
    public static String valueText(WzObject obj) {
        return switch (obj) {
            case WzFolder folder -> "[" + folder.countChildren() + "]";
            case WzDirectory dir -> "[" + dir.getChildren().size() + "]";
            case WzImage img -> "[" + img.getChildren().size() + "]";
            case WzCanvasProperty prop ->
                    prop.getWidth() + " x " + prop.getHeight() + " " + prop.getFormat() + " scale" + prop.getScale();
            case WzDoubleProperty prop -> String.valueOf(prop.getValue());
            case WzFloatProperty prop -> String.valueOf(prop.getValue());
            case WzIntProperty prop -> String.valueOf(prop.getValue());
            case WzLongProperty prop -> String.valueOf(prop.getValue());
            case WzShortProperty prop -> String.valueOf(prop.getValue());
            case WzStringProperty prop -> prop.getValue();
            case WzUOLProperty prop -> prop.getValue();
            case WzVectorProperty prop -> "(" + prop.getX() + ", " + prop.getY() + ")";
            case WzSoundProperty prop -> prop.getLenMs() + "ms";
            case WzRawDataProperty prop -> "raw[" + prop.getLength() + "]";
            case WzImageProperty prop when prop.isListProperty() -> "[" + prop.getChildren().size() + "]";
            default -> "";
        };
    }
}
