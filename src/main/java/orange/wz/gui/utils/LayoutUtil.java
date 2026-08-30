package orange.wz.gui.utils;

import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 记忆布局：窗口大小、视图是否打开、两侧各自加载了哪些文件，都存在 Preferences 里
 */
@Slf4j
public final class LayoutUtil {
    /**
     * 每侧最多记多少个文件
     */
    private static final int MAX_FILES = 50;

    private static final String KEY_WIDTH = "window.width";
    private static final String KEY_HEIGHT = "window.height";
    private static final String KEY_MAXIMIZED = "window.maximized";
    private static final String KEY_RIGHT_SHOWING = "view.rightShowing";
    private static final String KEY_LEFT_FILES = "files.left.";
    private static final String KEY_RIGHT_FILES = "files.right.";

    private static final Preferences prefs = Preferences.userNodeForPackage(LayoutUtil.class);

    private LayoutUtil() {
    }

    // 窗口 -------------------------------------------------------------------------------------------------------------

    /**
     * 上次关闭时的窗口大小
     *
     * @param defWidth  没有记录时的宽
     * @param defHeight 没有记录时的高
     */
    public static Dimension loadWindowSize(int defWidth, int defHeight) {
        int width = prefs.getInt(KEY_WIDTH, defWidth);
        int height = prefs.getInt(KEY_HEIGHT, defHeight);

        // 记录坏掉或者窗口被拖得过小的时候回到默认值
        if (width < 640 || height < 480) {
            return new Dimension(defWidth, defHeight);
        }

        // 换了小屏幕的话别让窗口比屏幕还大
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Dimension(Math.min(width, screen.width), Math.min(height, screen.height));
    }

    public static boolean loadWindowMaximized() {
        return prefs.getBoolean(KEY_MAXIMIZED, false);
    }

    /**
     * @param width     窗口宽
     * @param height    窗口高
     * @param maximized 是否最大化，最大化时 width/height 是屏幕尺寸，不覆盖上次记下的正常尺寸
     */
    public static void saveWindow(int width, int height, boolean maximized) {
        prefs.putBoolean(KEY_MAXIMIZED, maximized);
        if (!maximized) {
            prefs.putInt(KEY_WIDTH, width);
            prefs.putInt(KEY_HEIGHT, height);
        }
        flush();
    }

    // 视图 -------------------------------------------------------------------------------------------------------------
    public static boolean loadRightShowing() {
        return prefs.getBoolean(KEY_RIGHT_SHOWING, false);
    }

    public static void saveRightShowing(boolean showing) {
        prefs.putBoolean(KEY_RIGHT_SHOWING, showing);
        flush();
    }

    // 文件 -------------------------------------------------------------------------------------------------------------
    public static List<File> loadLeftFiles() {
        return loadFiles(KEY_LEFT_FILES);
    }

    public static List<File> loadRightFiles() {
        return loadFiles(KEY_RIGHT_FILES);
    }

    public static void saveLeftFiles(List<String> paths) {
        saveFiles(KEY_LEFT_FILES, paths);
    }

    public static void saveRightFiles(List<String> paths) {
        saveFiles(KEY_RIGHT_FILES, paths);
    }

    /**
     * 读一侧记下的文件，已经不存在的会被丢弃
     */
    private static List<File> loadFiles(String keyPrefix) {
        List<File> files = new ArrayList<>();

        for (int i = 0; i < MAX_FILES; i++) {
            String path = prefs.get(keyPrefix + i, null);
            if (path == null || path.isBlank()) {
                continue;
            }

            File file = new File(path);
            if (file.exists()) {
                files.add(file);
            } else {
                log.warn("上次加载的文件已不存在，跳过: {}", path);
            }
        }

        return files;
    }

    private static void saveFiles(String keyPrefix, List<String> paths) {
        for (int i = 0; i < MAX_FILES; i++) {
            if (i < paths.size()) {
                prefs.put(keyPrefix + i, paths.get(i));
            } else {
                prefs.remove(keyPrefix + i);
            }
        }
        flush();
    }

    private static void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            log.warn(e.getMessage(), e);
        }
    }
}
