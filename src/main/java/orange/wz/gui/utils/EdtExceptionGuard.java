package orange.wz.gui.utils;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;

/**
 * 把界面线程和后台线程里没人接的异常写进日志
 * <p>
 * EDT 上抛出的异常不会走 Thread 的默认处理器，AWT 自己在事件泵里 catch 掉再 printStackTrace 到 stderr，
 * 而打包成 windowed exe 之后根本没有控制台，等于直接丢掉，出问题时日志里一片空白。
 */
@Slf4j
public final class EdtExceptionGuard {

    private EdtExceptionGuard() {
    }

    /**
     * 安装异常处理，需要在 EDT 上调用
     */
    public static void install() {
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
            @Override
            protected void dispatchEvent(AWTEvent event) {
                try {
                    super.dispatchEvent(event);
                } catch (Throwable t) {
                    // 和 AWT 自己的行为保持一致：吞掉继续跑，区别只是这里留了日志
                    log.error("界面线程异常", t);
                }
            }
        });

        Thread.setDefaultUncaughtExceptionHandler((thread, t) -> log.error("线程 {} 未捕获异常", thread.getName(), t));
    }
}
