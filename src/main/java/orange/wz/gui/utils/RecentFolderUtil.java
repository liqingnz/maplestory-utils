package orange.wz.gui.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 最近打开过的文件夹，存在 Preferences 里，最多记 {@link #MAX_SIZE} 条，越靠前越新
 */
@Slf4j
public final class RecentFolderUtil {
    /**
     * 最多记录多少条
     */
    public static final int MAX_SIZE = 10;

    private static final String KEY_PREFIX = "recentFolder.";

    private static final Preferences prefs = Preferences.userNodeForPackage(RecentFolderUtil.class);

    private RecentFolderUtil() {
    }

    /**
     * 读取最近打开的文件夹，已经不存在的会被丢弃
     *
     * @return 文件夹列表，最新的在最前面
     */
    public static List<File> load() {
        List<File> folders = new ArrayList<>();

        for (int i = 0; i < MAX_SIZE; i++) {
            String path = prefs.get(KEY_PREFIX + i, null);
            if (path == null || path.isBlank()) {
                continue;
            }

            File folder = new File(path);
            if (folder.isDirectory() && !folders.contains(folder)) {
                folders.add(folder);
            }
        }

        return folders;
    }

    /**
     * 把文件夹放到记录的最前面，重复的会被提到前面而不是新增
     *
     * @param folder 文件夹
     */
    public static void add(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return;
        }

        List<File> folders = load();
        folders.remove(folder);
        folders.addFirst(folder);

        save(folders);
    }

    /**
     * 移除一条记录
     *
     * @param folder 文件夹
     */
    public static void remove(File folder) {
        List<File> folders = load();
        if (folders.remove(folder)) {
            save(folders);
        }
    }

    /**
     * 清空全部记录
     */
    public static void clear() {
        save(List.of());
    }

    private static void save(List<File> folders) {
        for (int i = 0; i < MAX_SIZE; i++) {
            if (i < folders.size()) {
                prefs.put(KEY_PREFIX + i, folders.get(i).getAbsolutePath());
            } else {
                prefs.remove(KEY_PREFIX + i);
            }
        }

        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            log.warn(e.getMessage(), e);
        }
    }
}
