package orange.wz.gui.utils;

import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Swing 的组件和模型只能在 EDT 上碰
 * <p>
 * 后台线程直接改树，会让 JTree 内部那份行坐标缓存和实际内容对不上，表现就是列表画花、行叠在一起、整片空白，
 * 而且只坏正在被改的那一块区域。解析这种耗时活留在后台线程，碰界面的部分一律用这里切回 EDT。
 */
public final class EdtUtil {

    private EdtUtil() {
    }

    /**
     * 在 EDT 上同步执行，已经在 EDT 上就直接跑
     * <p>
     * 注意：调用方如果正持有 EDT 在等的锁，会死锁，别在 EDT 等待后台线程的场景里用
     *
     * @param task 要执行的操作
     */
    public static void run(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
        }
    }

    /**
     * 在 EDT 上同步执行并把返回值带回来
     *
     * @param task 要执行的操作
     * @return 操作的返回值
     */
    public static <T> T call(Supplier<T> task) {
        if (SwingUtilities.isEventDispatchThread()) return task.get();

        AtomicReference<T> result = new AtomicReference<>();
        run(() -> result.set(task.get()));
        return result.get();
    }

    /**
     * 丢到 EDT 上排队执行，不等结果
     * <p>
     * 状态栏、进度条这类更新用它，避免后台线程被界面拖住
     *
     * @param task 要执行的操作
     */
    public static void later(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }

        SwingUtilities.invokeLater(task);
    }
}
