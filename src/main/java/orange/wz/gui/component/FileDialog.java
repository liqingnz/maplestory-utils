package orange.wz.gui.component;

import com.formdev.flatlaf.util.SystemFileChooser;
import orange.wz.gui.MainFrame;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;

public class FileDialog {
    private static final Preferences prefs = Preferences.userNodeForPackage(FileDialog.class);

    /**
     * 记忆路径的分组：加载 / 导入 / 导出 各记各的，避免导入导出互相覆盖，来回切换同一个路径
     */
    public static final String KEY_DEFAULT = "last";
    public static final String KEY_OPEN = "last.open";
    public static final String KEY_IMPORT = "last.import";
    public static final String KEY_EXPORT = "last.export";

    /**
     * 读取该分组上次使用的目录
     *
     * @param prefKey 记忆分组，见 KEY_* 常量
     * @return 上次的目录，没有记录返回 null
     */
    public static String getLastDir(String prefKey) {
        return prefs.get(prefKey, null);
    }

    /**
     * 记住该分组本次使用的目录（传入文件时记住它所在的目录）
     */
    public static void rememberDir(String prefKey, File file) {
        if (file == null) return;
        File dir = file.isDirectory() ? file : file.getParentFile();
        if (dir != null) {
            prefs.put(prefKey, dir.getAbsolutePath());
        }
    }

    /**
     * 文件选择器
     *
     * @param parent     父组件，可 null
     * @param title      对话框标题
     * @param allowMulti 是否允许多选
     * @param filters    扩展名过滤器，例如 {"txt","md"}，null = 不过滤
     * @return 用户选择的文件列表，取消选择返回空列表
     */
    public static List<File> chooseOpenFiles(Component parent, String title, boolean allowMulti, String[] filters) {
        return chooseOpenFiles(parent, title, allowMulti, filters, KEY_DEFAULT);
    }

    public static List<File> chooseOpenFiles(Component parent, String title, boolean allowMulti, String[] filters, String prefKey) {
        List<File> result = new ArrayList<>();

        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(allowMulti);

        // 恢复上次目录
        String lastDir = prefs.get(prefKey, null);
        if (lastDir != null) {
            chooser.setCurrentDirectory(new File(lastDir));
        }

        // 添加扩展名过滤
        if (filters != null && filters.length > 0) {
            String desc = String.join(", ", filters) + " " + MainFrame.i18n.get("test.temp0147");
            chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(desc, filters));
            chooser.setAcceptAllFileFilterUsed(false);
        }

        if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) {
            File[] selected = chooser.getSelectedFiles();
            if (selected != null) {
                Collections.addAll(result, selected);
            }
        }

        if (!result.isEmpty()) {
            rememberDir(prefKey, result.getFirst());
        }

        return result;
    }

    /**
     * 文件夹选择器
     *
     * @param parent     父组件，可 null
     * @param title      对话框标题
     * @param allowMulti 是否允许多选
     * @return 用户选择的文件夹列表，取消选择返回空列表
     */
    public static List<File> chooseOpenFolders(Component parent, String title, boolean allowMulti) {
        return chooseOpenFolders(parent, title, allowMulti, KEY_DEFAULT);
    }

    public static List<File> chooseOpenFolders(Component parent, String title, boolean allowMulti, String prefKey) {
        List<File> result = new ArrayList<>();

        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(allowMulti);

        // 恢复上次目录
        String lastDir = prefs.get(prefKey, null);
        if (lastDir != null) {
            chooser.setCurrentDirectory(new File(lastDir));
        }

        if (chooser.showOpenDialog(parent) == SystemFileChooser.APPROVE_OPTION) {
            File[] selected = chooser.getSelectedFiles();
            if (selected != null) {
                Collections.addAll(result, selected);
            }
        }

        if (!result.isEmpty()) {
            rememberDir(prefKey, result.getFirst());
        }

        return result;
    }

    public static List<File> chooseOpenFolders() {
        return chooseOpenFolders(null, MainFrame.i18n.get("test.temp0149"), true, KEY_OPEN);
    }

    public static List<File> chooseOpenFiles(String[] filters) {
        return chooseOpenFiles(null, MainFrame.i18n.get("test.temp0148"), true, filters, KEY_OPEN);
    }

    public static List<File> chooseOpenFiles(String[] filters, String prefKey) {
        return chooseOpenFiles(null, MainFrame.i18n.get("test.temp0148"), true, filters, prefKey);
    }

    public static File chooseOpenFolder(String title) {
        return chooseOpenFolder(title, KEY_DEFAULT);
    }

    public static File chooseOpenFolder(String title, String prefKey) {
        List<File> selected = chooseOpenFolders(null, title, false, prefKey);
        if (selected.isEmpty()) return null;
        return selected.getFirst();
    }

    public static File chooseOpenFile(String[] filters) {
        List<File> files = chooseOpenFiles(null, MainFrame.i18n.get("test.temp0148"), false, filters, KEY_DEFAULT);
        if (files.isEmpty()) {
            return null;
        }
        return files.getFirst();
    }

    public static File chooseSaveFile(Component parent, String title, File defaultFile, String[] filters) {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setSelectedFile(defaultFile);

        // 添加扩展名过滤
        boolean hasFilter = filters != null && filters.length > 0;
        if (hasFilter) {
            String desc = String.join(", ", filters) + " " + MainFrame.i18n.get("test.temp0147");
            chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(desc, filters));
            chooser.setAcceptAllFileFilterUsed(false);
        }

        File file = null;
        if (chooser.showSaveDialog(parent) == SystemFileChooser.APPROVE_OPTION) {
            file = chooser.getSelectedFile();
            if (hasFilter) { // 检查输入的后缀名
                String name = file.getName();

                boolean hasExt = false;
                for (String ext : filters) {
                    if (name.toLowerCase().endsWith("." + ext.toLowerCase())) {
                        hasExt = true;
                        break;
                    }
                }

                if (!hasExt) {
                    file = new File(file.getParentFile(), name + "." + filters[0]);
                }
            }
        }

        return file;
    }
}
