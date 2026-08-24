package orange.wz.gui.component.panel;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import orange.wz.gui.Clipboard;
import orange.wz.gui.MainFrame;
import orange.wz.gui.component.FileDialog;
import orange.wz.gui.component.canvas.CanvasWall;
import orange.wz.gui.component.dialog.*;
import orange.wz.gui.component.form.data.*;
import orange.wz.gui.component.form.impl.*;
import orange.wz.gui.component.menu.*;
import orange.wz.gui.utils.*;
import orange.wz.model.Pair;
import orange.wz.mcp.resolve.NodePathResolver;
import orange.wz.provider.*;
import orange.wz.provider.properties.*;
import orange.wz.provider.tools.*;
import orange.wz.provider.tools.wzkey.WzKey;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static orange.wz.gui.Icons.*;

@Getter
@Slf4j
public final class EditPane extends JSplitPane {
    private static final Pattern INDEXED_NODE_SEGMENT = Pattern.compile("^(.*)\\[(\\d+)]$");

    private JTree tree;
    private DefaultMutableTreeNode treeRoot;
    private DefaultTreeModel treeModel;

    private JPanel formCards;

    private WzFileMenu wzFilePopupMenu;
    private WzFolderMenu wzFolderPopupMenu;
    private WzImageFileMenu wzImageFilePopupMenu;
    private WzXmlFileMenu wzXmlFilePopupMenu;
    private WzDirectoryMenu wzDirectoryPopupMenu;
    private WzImageMenu wzImagePopupMenu;
    private WzListPropertyMenu wzListPropertyPopupMenu;
    private WzValuePropertyMenu wzValuePropertyPopupMenu;

    @Getter
    private String currentFormName;
    @Getter
    private final Map<String, AbstractValueForm> nodeForms = Map.ofEntries(
        Map.entry("node", new NodeForm()),
        Map.entry("canvas", new CanvasForm(this)),
        Map.entry("double", new DoubleForm()),
        Map.entry("float", new FloatForm()),
        Map.entry("int", new IntForm()),
        Map.entry("long", new LongForm()),
        Map.entry("short", new ShortForm()),
        Map.entry("sound", new SoundForm()),
        Map.entry("string", new StringForm()),
        Map.entry("uolCanvas", new UolCanvasForm(this)),
        Map.entry("uolSound", new UolSoundForm()),
        Map.entry("vector", new VectorForm()),
        Map.entry("lua", new LuaForm())
    );

    private final SearchDialog searchDialog = new SearchDialog(MainFrame.i18n.get("search"), this);
    private final List<SearchResult> searchResults = new ArrayList<>();

    // 视图对比的差异标记表：key 为差异节点，渲染器据此着色。弱引用，节点卸载后自动清理
    private final Map<WzObject, DiffKind> diffMarks = Collections.synchronizedMap(new WeakHashMap<>());

    // 视图对比的节点绑定：本侧树锚定路径 -> 对侧树锚定路径。对比时建立，定位/表单对比/替换优先走绑定，
    // 使两侧文件名/路径不一致时也能对应
    private final Map<String, String> diffBindings = new ConcurrentHashMap<>();

    // 视图对比插入的占位节点（本侧缺失、对侧存在），只挂在树上、不进数据模型，清标记时一并移除
    private final List<DefaultMutableTreeNode> ghostNodes = new ArrayList<>();

    // 按键搜索
    private final StringBuilder inputBuffer = new StringBuilder();
    private Instant lastInputTime = Instant.EPOCH;

    public NodeForm getNodeForm() {
        return (NodeForm) nodeForms.get("node");
    }

    public CanvasForm getCanvasForm() {
        return (CanvasForm) nodeForms.get("canvas");
    }

    public DoubleForm getDoubleForm() {
        return (DoubleForm) nodeForms.get("double");
    }

    public FloatForm getFloatForm() {
        return (FloatForm) nodeForms.get("float");
    }

    public IntForm getIntForm() {
        return (IntForm) nodeForms.get("int");
    }

    public LongForm getLongForm() {
        return (LongForm) nodeForms.get("long");
    }

    public ShortForm getShortForm() {
        return (ShortForm) nodeForms.get("short");
    }

    public SoundForm getSoundForm() {
        return (SoundForm) nodeForms.get("sound");
    }

    public StringForm getStringForm() {
        return (StringForm) nodeForms.get("string");
    }

    public UolCanvasForm getUolCanvasForm() {
        return (UolCanvasForm) nodeForms.get("uolCanvas");
    }

    public UolSoundForm getUolSoundForm() {
        return (UolSoundForm) nodeForms.get("uolSound");
    }

    public VectorForm getVectorForm() {
        return (VectorForm) nodeForms.get("vector");
    }

    public LuaForm getLuaForm() {
        return (LuaForm) nodeForms.get("lua");
    }

    public EditPane(boolean oneTouchExpandable) {
        super();

        JScrollPane treePanel = createTreePane();
        treePanel.setMinimumSize(new Dimension(260, 0)); // 宽度最小 260，高度不限

        setOrientation(JSplitPane.HORIZONTAL_SPLIT);
        setLeftComponent(treePanel);
        setRightComponent(createValuePane());
        setDividerLocation(260);
        setOneTouchExpandable(oneTouchExpandable); // 增加小箭头快速展开/收起
    }

    private JScrollPane createTreePane() {
        treeRoot = new DefaultMutableTreeNode("root");
        tree = new JTree(treeRoot);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true); // 隐藏自带的展开/收缩图标
        tree.setToggleClickCount(0);
        treeModel = (DefaultTreeModel) tree.getModel();

        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new FileDropTransferHandler(this));

        // 节点自定义渲染器
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                setFont(tree.getFont()); // 渲染器组件被复用，先重置字体，避免占位节点的斜体泄漏到其他节点

                // 对比占位节点：半透明名字提示，无图标
                if (value instanceof DefaultMutableTreeNode ghostTreeNode && ghostTreeNode.getUserObject() instanceof DiffGhostObject ghost) {
                    setIcon(null);
                    DiffKind ghostKind = diffMarks.get(ghost);
                    Color base = ghostKind != null ? ghostKind.getColor() : Color.GRAY;
                    setForeground(new Color(base.getRed(), base.getGreen(), base.getBlue(), 110));
                    setFont(getFont().deriveFont(Font.ITALIC));
                    setText(ghost.getName() + "  (" + MainFrame.i18n.get("diff.ghost.hint") + ")");
                    return this;
                }

                if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof WzObject obj) {
                    Icon icon = switch (obj.getType()) {
                        case FOLDER -> FcFolderIcon;
                        case DIRECTORY -> {
                            if (((WzDirectory) obj).isWzFile()) {
                                yield AiOutlineFileWordIcon;
                            } else {
                                yield FcFolderBlueIcon;
                            }
                        }
                        case IMAGE -> {
                            if (obj instanceof WzImageFile) {
                                yield AiOutlineFileMarkdownIcon;
                            } else if (obj instanceof WzXmlFile) {
                                yield AiOutlineFileExcelIcon;
                            } else {
                                yield ImgIcon;
                            }
                        }
                        case CANVAS_PROPERTY -> PngIcon;
                        case CONVEX_PROPERTY -> ConvexIcon;
                        case DOUBLE_PROPERTY -> DoubleIcon;
                        case FLOAT_PROPERTY -> FloatIcon;
                        case INT_PROPERTY -> IntIcon;
                        case LIST_PROPERTY -> ListIcon;
                        case LONG_PROPERTY -> LongIcon;
                        case NULL_PROPERTY -> NullIcon;
                        case RAW_DATA_PROPERTY -> RawIcon;
                        case SHORT_PROPERTY -> ShortIcon;
                        case SOUND_PROPERTY -> WavIcon;
                        case STRING_PROPERTY -> StrIcon;
                        case UOL_PROPERTY -> UolIcon;
                        case VECTOR_PROPERTY -> VectorIcon;
                        case LUA_PROPERTY -> LuaIcon;
                        case WZ_FILE, PNG_PROPERTY -> null;
                    };
                    setIcon(icon);
                    String extraText = "&nbsp;&nbsp;";
                    switch (obj) {
                        case WzFolder wzFolder -> extraText += "[" + wzFolder.countChildren() + "]";
                        case WzDirectory wzDir -> extraText += "[" + wzDir.getChildren().size() + "]";
                        case WzImage wzImg -> extraText += "[" + wzImg.getChildren().size() + "]";
                        case WzCanvasProperty prop -> extraText += prop.getWidth() + " x " + prop.getHeight();
                        case WzDoubleProperty prop -> extraText += prop.getValue();
                        case WzFloatProperty prop -> extraText += prop.getValue();
                        case WzIntProperty prop -> extraText += prop.getValue();
                        case WzLongProperty prop -> extraText += prop.getValue();
                        case WzShortProperty prop -> extraText += prop.getValue();
                        case WzSoundProperty prop -> extraText += prop.getLenMs() + "ms";
                        case WzStringProperty prop -> extraText += prop.getValue();
                        case WzUOLProperty prop -> extraText += prop.getValue();
                        case WzVectorProperty prop -> extraText += "(" + prop.getX() + ", " + prop.getY() + ")";
                        case WzImageProperty prop when prop.isListProperty() ->
                            extraText += "[" + prop.getChildren().size() + "]";
                        default -> {
                        }
                    }

                    setText(
                        "<html>" +
                            obj.getName() +
                            " <font color='#808080'>" +
                            extraText +
                            "</font></html>"
                    );
                    if (obj.isTempChanged()) {
                        setForeground(Color.MAGENTA);
                    }
                    DiffKind diffKind = diffMarks.get(obj);
                    if (diffKind != null) {
                        setForeground(diffKind.getColor());
                    }
                    if (obj instanceof WzImageFile file && file.isErrorStatus()) {
                        setForeground(Color.RED);
                    } else if (obj instanceof WzDirectory dir && dir.isWzFile() && dir.getWzFile().isErrorStatus()) {
                        setForeground(Color.RED);
                    }
                }

                return this;
            }
        };
        tree.setCellRenderer(renderer);

        // 禁止跨区域多选
        SameLevelTreeSelectionModel selectionModel = new SameLevelTreeSelectionModel();
        selectionModel.onReject(() -> JMessageUtil.warn(MainFrame.i18n.get("warn"), MainFrame.i18n.get("warn.cross_region_select")));
        tree.setSelectionModel(selectionModel);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);

        // 选中节点触发事件
        tree.addTreeSelectionListener(e -> {
            TreePath selectedPath = tree.getSelectionPath();
            if (selectedPath != null) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
                WzObject wzObject = (WzObject) node.getUserObject();
                handleTreeClick(node);
                MainFrame.getInstance().getCenterPane().getAnotherPane(EditPane.this).syncTreeClick(wzObject);
            }
        });

        // 右键菜单
        wzFilePopupMenu = new WzFileMenu(this);
        wzFolderPopupMenu = new WzFolderMenu(this, tree);
        wzImageFilePopupMenu = new WzImageFileMenu(this);
        wzXmlFilePopupMenu = new WzXmlFileMenu(this);
        wzDirectoryPopupMenu = new WzDirectoryMenu(this);
        wzImagePopupMenu = new WzImageMenu(this);
        wzListPropertyPopupMenu = new WzListPropertyMenu(this);
        wzValuePropertyPopupMenu = new WzValuePropertyMenu(this);
        tree.addMouseListener(new MouseAdapter() {
            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;

                TreePath path = getTreePath(e);
                if (path == null) return;

                // 右键目标不是选中对象，则单独选中目标并右键
                if (!tree.isPathSelected(path)) {
                    tree.setSelectionPath(path);
                }

                // 显示菜单
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                WzObject wzObject = (WzObject) node.getUserObject();
                if (wzObject instanceof DiffGhostObject) {
                    return; // 对比占位节点没有可用操作
                }
                if (wzObject instanceof WzFolder) {
                    wzFolderPopupMenu.show(tree, e.getX(), e.getY());
                } else if (wzObject instanceof WzDirectory directory) {
                    if (directory.isWzFile()) {
                        wzFilePopupMenu.show(tree, e.getX(), e.getY());
                    } else {
                        wzDirectoryPopupMenu.show(tree, e.getX(), e.getY());
                    }
                } else if (wzObject instanceof WzImageFile) {
                    wzImageFilePopupMenu.show(tree, e.getX(), e.getY());
                } else if (wzObject instanceof WzXmlFile) {
                    wzXmlFilePopupMenu.show(tree, e.getX(), e.getY());
                } else if (wzObject instanceof WzImage) {
                    wzImagePopupMenu.show(tree, e.getX(), e.getY());
                } else if (wzObject instanceof WzImageProperty prop) {
                    if (prop.isListProperty()) {
                        wzListPropertyPopupMenu.show(tree, e.getX(), e.getY());
                    } else {
                        wzValuePropertyPopupMenu.show(tree, e.getX(), e.getY());
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showPopup(e);
                    return;
                }

                if (SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = getTreePath(e);
                    if (path == null) return;

                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();

                    // 左键单击
                    // 移动到选中事件中去了
                    // if (e.getClickCount() == 1) {
                    //     handleTreeClick((WzObject) node.getUserObject());
                    // }

                    // 左键双击
                    if (e.getClickCount() == 2) {
                        WzObject wzObject = (WzObject) node.getUserObject();
                        EditPaneAction finalAction;
                        if (node.isLeaf()) {
                            // 叶子节点：执行业务逻辑
                            handleTreeDoubleClick(node);
                            finalAction = EditPaneAction.ACTION;
                        } else {
                            // 非叶子节点：手动切换展开状态
                            if (tree.isExpanded(path)) {
                                collapseAll(path);
                                finalAction = EditPaneAction.COLLAPSE;
                            } else {
                                tree.expandPath(path);
                                finalAction = EditPaneAction.EXPAND;
                            }
                        }
                        MainFrame.getInstance().getCenterPane().getAnotherPane(EditPane.this).syncTreeDoubleClick(wzObject, finalAction);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showPopup(e);
                }
            }
        });

        addKeyEvent();

        return new JScrollPane(tree);
    }

    private void collapseAll(TreePath parent) {
        if (!tree.isExpanded(parent)) return;

        Object node = parent.getLastPathComponent();
        TreeModel model = treeModel;

        int childCount = model.getChildCount(node);
        for (int i = 0; i < childCount; i++) {
            Object child = model.getChild(node, i);
            TreePath childPath = parent.pathByAddingChild(child);
            collapseAll(childPath);
        }

        tree.collapsePath(parent);
    }

    private JPanel createValuePane() {
        // CardLayout 容器
        formCards = new JPanel(new CardLayout());
        // 这两行是为了防止拉伸右侧边框后导致SplitPanel左侧的视图无法再放大
        formCards.setMinimumSize(new Dimension(0, 0));
        formCards.setPreferredSize(new Dimension(0, 0));

        for (var obj : nodeForms.entrySet()) {
            formCards.add(obj.getValue().getValuePane(), obj.getKey());
        }

        // 默认显示 node 表单
        ((CardLayout) formCards.getLayout()).show(formCards, "node");

        return formCards;
    }

    private void switchForm(String formName) {
        CardLayout cl = (CardLayout) (formCards.getLayout());
        cl.show(formCards, formName);

        if (currentFormName != null && !currentFormName.equals(formName)) {
            nodeForms.get(currentFormName).onHide();
        }
        currentFormName = formName;
    }

    /**
     * 重新触发一次当前选中节点的单击事件
     * <p>
     * 用于预览开关切换后立刻刷新右侧表单
     */
    public void refreshCurrentSelection() {
        TreePath selectedPath = tree.getSelectionPath();
        if (selectedPath == null) return;
        handleTreeClick((DefaultMutableTreeNode) selectedPath.getLastPathComponent());
    }

    /**
     * 默认预览开关打开时，把节点的下一级展示到右边
     *
     * @param wzObject 当前选中的节点
     */
    private void previewChildren(WzObject wzObject) {
        if (!MainFrame.getInstance().isDefaultPreview()) return;
        getNodeForm().setChildrenPreview(wzObject);
    }

    /**
     * node 单击事件
     */
    private void handleTreeClick(DefaultMutableTreeNode node) {
        WzObject wzObject = (WzObject) node.getUserObject();

        // 对比占位节点：展示名字和提示，并提供"复制视图节点"按钮把对侧真实节点复制过来
        if (wzObject instanceof DiffGhostObject ghost) {
            getNodeForm().setData(ghost.getName(), MainFrame.i18n.get("diff.ghost.hint"), null, this);
            switchForm("node");
            getNodeForm().hideDiffCompare();

            EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
            Pair<WzObject, String> other = resolveOtherSide(otherPane, node);
            if (other != null) {
                getNodeForm().showGhostCopy(() -> replaceNodeContent(node, other.getLeft(), otherPane, other.getRight()));
            }

            MainFrame.getInstance().setStatusText(ghost.getName() + "  " + MainFrame.i18n.get("diff.ghost.hint"));
            return;
        }

        String npcAction = null;
        switch (wzObject) {
            case WzFolder obj -> {
                getNodeForm().setData(obj.getName(), WzType.FOLDER.name(), wzObject, this);
                previewChildren(obj);
                switchForm("node");
            }
            case WzDirectory obj -> {
                if (obj.isWzFile()) {
                    getNodeForm().setData(obj.getName(), WzType.WZ_FILE.name(), wzObject, this);
                } else {
                    getNodeForm().setData(obj.getName(), WzType.DIRECTORY.name(), wzObject, this);
                }
                previewChildren(obj);
                switchForm("node");
            }
            case WzImage obj -> {
                getNodeForm().setData(obj.getName(), WzType.IMAGE.name(), wzObject, this);
                if (MainFrame.getInstance().isCharacterPreview()) {
                    getNodeForm().setImageInfo(obj);
                } else {
                    previewChildren(obj);
                }
                switchForm("node");
            }
            case WzCanvasProperty obj -> {
                getCanvasForm().setData(obj.getName(), WzType.CANVAS_PROPERTY.name(), obj.getPngImage(true), obj.getWidth(), obj.getHeight(), obj.getFormat(), obj.getScale(), wzObject, this);
                switchForm("canvas");
            }
            case WzConvexProperty obj -> {
                getNodeForm().setData(obj.getName(), WzType.CONVEX_PROPERTY.name(), wzObject, this);
                previewChildren(obj);
                switchForm("node");
            }
            case WzDoubleProperty obj -> {
                getDoubleForm().setData(obj.getName(), WzType.DOUBLE_PROPERTY.name(), obj.getValue(), wzObject, this);
                switchForm("double");
            }
            case WzFloatProperty obj -> {
                getFloatForm().setData(obj.getName(), WzType.FLOAT_PROPERTY.name(), obj.getValue(), wzObject, this);
                switchForm("float");
            }
            case WzIntProperty obj -> {
                getIntForm().setData(obj.getName(), WzType.INT_PROPERTY.name(), obj.getValue(), wzObject, this);
                switchForm("int");
            }
            case WzListProperty obj -> {
                getNodeForm().setData(obj.getName(), WzType.LIST_PROPERTY.name(), wzObject, this);
                previewChildren(obj);
                switchForm("node");
            }
            case WzLongProperty obj -> {
                getLongForm().setData(obj.getName(), WzType.LONG_PROPERTY.name(), obj.getValue(), wzObject, this);
                switchForm("long");
            }
            case WzNullProperty obj -> {
                getNodeForm().setData(obj.getName(), WzType.NULL_PROPERTY.name(), wzObject, this);
                switchForm("node");
            }
            case WzShortProperty obj -> {
                getShortForm().setData(obj.getName(), WzType.SHORT_PROPERTY.name(), obj.getValue(), wzObject, this);
                switchForm("short");
            }
            case WzSoundProperty obj -> {
                getSoundForm().setData(obj.getName(), WzType.SOUND_PROPERTY.name(), obj.getSoundBytes(), obj.getLenMs(), wzObject, this);
                switchForm("sound");
            }
            case WzStringProperty obj -> {
                getStringForm().setData(obj.getName(), WzType.STRING_PROPERTY.name(), obj.getValue(), wzObject, this);
                switchForm("string");
                if (obj.getWzImage().getName().equals("Npc.img") && !obj.getName().equals("name") && !obj.getName().equals("func")) {
                    npcAction = TreeNodeUtil.getNpcActionName(node);
                    if (npcAction == null) npcAction = MainFrame.i18n.get("warn.action_is_not_exists");
                }
            }
            case WzUOLProperty obj -> {
                WzObject target = obj.getUolTarget();
                if (target instanceof WzCanvasProperty cav) {
                    getUolCanvasForm().setData(obj.getName(), WzType.UOL_PROPERTY.name(), obj.getValue(), cav, wzObject, this);
                    switchForm("uolCanvas");
                } else if (target instanceof WzSoundProperty sound) {
                    getUolSoundForm().setData(obj.getName(), WzType.UOL_PROPERTY.name(), obj.getValue(), sound, wzObject, this);
                    switchForm("uolSound");
                }
            }
            case WzVectorProperty obj -> {
                getVectorForm().setData(obj.getName(), WzType.VECTOR_PROPERTY.name(), obj.getX(), obj.getY(), wzObject, this);
                switchForm("vector");
            }
            case WzLuaProperty obj -> {
                getLuaForm().setData(obj.getName(), WzType.LUA_PROPERTY.name(), obj.getString(), wzObject, this);
                switchForm("lua");
            }
            default -> {
                MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("warn.unknow_type", wzObject.getName(), wzObject.getClass().getSimpleName()));
                return;
            }
        }

        // 值节点带"值不同"标记时，在表单里追加显示对侧视图值和替换按钮
        setupDiffCompare(node, wzObject);

        // 更新状态栏
        String text = getNodePathText(wzObject, npcAction);
        MainFrame.getInstance().setStatusText(text);
    }

    /**
     * 节点有差异标记时，让当前表单显示对侧视图的值，并提供"使用视图值替换"按钮。
     * 值节点：替换自身的值；容器节点（img/目录/List/Canvas 等）：递归替换子树里所有"值不同"的节点值。
     */
    private void setupDiffCompare(DefaultMutableTreeNode node, WzObject wzObject) {
        AbstractValueForm form = nodeForms.get(currentFormName);
        form.hideDiffCompare();

        DiffKind mark = diffMarks.get(wzObject);
        if (mark == null) return;

        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        Pair<WzObject, String> other = resolveOtherSide(otherPane, node);
        if (other == null) return;

        WzObject otherObj = other.getLeft();
        String otherPath = other.getRight();

        // 值替换：要求两侧类型一致；值节点还要求确实是"值不同"，容器节点则递归替换子树的值
        boolean singleValue = isDiffReplaceSupported(wzObject);
        boolean bulk = isBulkReplaceSupported(wzObject);
        boolean sameType = otherObj.getType() == wzObject.getType();
        boolean valueReplace = sameType && (singleValue ? mark == DiffKind.MODIFIED : bulk);

        // 整节点替换：不要求两侧类型一致（类型不同的节点正需要整个换掉），只要求父容器能接收对方
        boolean nodeFromViewOk = isNodeReplaceSupported(wzObject, otherObj);
        boolean nodeToViewOk = isNodeReplaceSupported(otherObj, wzObject);

        if (!valueReplace && !nodeFromViewOk && !nodeToViewOk) return;

        Runnable valuesFromView = null;
        Runnable valuesToView = null;
        if (valueReplace) {
            valuesFromView = singleValue
                    ? () -> applyOtherViewValue(node, wzObject, otherObj, otherPath)
                    : () -> applyOtherViewValues(node, wzObject, otherObj, otherPath);
            valuesToView = singleValue
                    ? () -> applyValueToView(node, wzObject, otherObj, otherPath)
                    : () -> applyValuesToView(node, wzObject, otherObj, otherPath);
        }

        form.showDiffCompare(WzDiffUtil.valueText(otherObj),
                valuesFromView,
                valuesToView,
                nodeFromViewOk ? () -> replaceNodeFromView(node, otherObj, otherPath) : null,
                nodeToViewOk ? () -> replaceViewNode(otherPath, node, wzObject) : null);
    }

    /**
     * 能否用 source 整个替换掉 target：两边都不是文件夹，且 target 的父容器能接收 source 这种类型
     */
    private boolean isNodeReplaceSupported(WzObject target, WzObject source) {
        if (target instanceof WzFolder || source instanceof WzFolder) return false;

        WzObject parentObj = target.getParent();
        if (parentObj == null) return false;

        // 与 addWzObjChild 支持的父子组合保持一致
        return switch (parentObj) {
            case WzDirectory ignored -> source instanceof WzDirectory || source instanceof WzImage;
            case WzImage ignored -> source instanceof WzImageProperty;
            case WzImageProperty pProp when pProp.isListProperty() -> source instanceof WzImageProperty;
            default -> false;
        };
    }

    private boolean isDiffReplaceSupported(WzObject wzObject) {
        return wzObject instanceof WzStringProperty
                || wzObject instanceof WzIntProperty
                || wzObject instanceof WzShortProperty
                || wzObject instanceof WzLongProperty
                || wzObject instanceof WzFloatProperty
                || wzObject instanceof WzDoubleProperty
                || wzObject instanceof WzVectorProperty
                || wzObject instanceof WzUOLProperty;
    }

    private boolean isBulkReplaceSupported(WzObject wzObject) {
        return wzObject instanceof WzFolder
                || wzObject instanceof WzDirectory
                || wzObject instanceof WzImage
                || (wzObject instanceof WzImageProperty prop && prop.isListProperty());
    }

    /**
     * 用对侧视图的值覆盖本侧节点的值，同步清掉两侧的差异标记并刷新界面
     */
    private void applyOtherViewValue(DefaultMutableTreeNode node, WzObject thisObj, WzObject otherObj, String otherPath) {
        if (!WzDiffUtil.copyValue(otherObj, thisObj)) return;

        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        diffMarks.remove(thisObj);
        otherPane.getDiffMarks().remove(otherObj);
        refreshAfterDiffReplace(node, otherPane, otherPath);
    }

    /**
     * 反向：用本侧节点的值覆盖对侧视图节点的值
     */
    private void applyValueToView(DefaultMutableTreeNode node, WzObject thisObj, WzObject otherObj, String otherPath) {
        if (!WzDiffUtil.copyValue(thisObj, otherObj)) return;

        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        diffMarks.remove(thisObj);
        otherPane.getDiffMarks().remove(otherObj);
        refreshAfterDiffReplace(node, otherPane, otherPath);
    }

    /**
     * 批量替换：把对侧视图子树里的值递归写入本侧子树所有"值不同"的节点
     */
    private void applyOtherViewValues(DefaultMutableTreeNode node, WzObject thisObj, WzObject otherObj, String otherPath) {
        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        runBulkValueReplace(() -> WzDiffUtil.applyValues(otherObj, thisObj, diffMarks, otherPane.getDiffMarks()), node, otherPane, otherPath);
    }

    /**
     * 反向批量替换：把本侧子树里的值递归写入对侧视图子树所有"值不同"的节点
     */
    private void applyValuesToView(DefaultMutableTreeNode node, WzObject thisObj, WzObject otherObj, String otherPath) {
        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        runBulkValueReplace(() -> WzDiffUtil.applyValues(thisObj, otherObj, otherPane.getDiffMarks(), diffMarks), node, otherPane, otherPath);
    }

    private void runBulkValueReplace(java.util.function.Supplier<Integer> replaceTask, DefaultMutableTreeNode node, EditPane otherPane, String otherPath) {
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() {
                return replaceTask.get();
            }

            @Override
            protected void done() {
                int count;
                try {
                    count = get();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }

                refreshAfterDiffReplace(node, otherPane, otherPath);
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("diff.replace.done", count));
            }
        }.execute();
    }

    private void refreshAfterDiffReplace(DefaultMutableTreeNode node, EditPane otherPane, String otherPath) {
        cleanupAncestorMarks(node);
        otherPane.cleanupAncestorMarksByPath(otherPath);
        tree.repaint();
        otherPane.getTree().repaint();
        refreshCurrentSelection();
        otherPane.refreshCurrentSelection();
    }

    /**
     * 替换操作后向上递归清理"含差异"标记：某一级的全部子节点（含占位节点、未物化的模型子节点）
     * 都不再带差异标记时，该级的标记清除并标脏（与替换过的节点同色），继续向上一级判断
     */
    private void cleanupAncestorMarks(DefaultMutableTreeNode node) {
        if (node == null) return;
        for (DefaultMutableTreeNode p = (DefaultMutableTreeNode) node.getParent(); p != null; p = (DefaultMutableTreeNode) p.getParent()) {
            if (!(p.getUserObject() instanceof WzObject obj)) return;
            if (diffMarks.get(obj) != DiffKind.PARENT) return;
            if (hasMarkedChild(p, obj)) return;

            diffMarks.remove(obj);
            obj.setTempChanged(true);
        }
    }

    private void cleanupAncestorMarksByPath(String path) {
        cleanupAncestorMarks(findTreeNodeByPath(path));
    }

    private boolean hasMarkedChild(DefaultMutableTreeNode node, WzObject obj) {
        // 模型子节点（覆盖树上未物化的部分）
        List<? extends WzObject> children = WzDiffUtil.childrenOf(obj);
        if (children != null) {
            for (WzObject child : children) {
                if (diffMarks.containsKey(child)) return true;
            }
        }
        // 树子节点（覆盖只挂在树上的占位节点）
        for (int i = 0; i < node.getChildCount(); i++) {
            if (((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject() instanceof DiffGhostObject ghost
                    && diffMarks.containsKey(ghost)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 整节点替换：用对侧视图节点完整替换本侧选中节点（深克隆，包含全部子孙结构）
     */
    private void replaceNodeFromView(DefaultMutableTreeNode thisNode, WzObject otherObj, String otherPath) {
        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        replaceNodeContent(thisNode, otherObj, otherPane, otherPath);
    }

    /**
     * 整节点替换（反向）：用本侧节点完整替换对侧视图的对应节点
     */
    private void replaceViewNode(String otherPath, DefaultMutableTreeNode thisNode, WzObject thisObj) {
        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        DefaultMutableTreeNode otherNode = otherPane.revealDiffPath(otherPath);
        if (otherNode == null) {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("diff.error.no_target", otherPath));
            return;
        }
        otherPane.replaceNodeContent(otherNode, thisObj, this, anchoredNodePath(thisNode));
    }

    /**
     * 在本面板里用 sourceObj 的深克隆完整替换 targetNode（保留原节点名和位置），接线方式与剪贴板粘贴一致
     *
     * @param sourcePane sourceObj 所在的面板（用于清理其子树的差异标记）
     * @param sourcePath sourceObj 在其面板里的树锚定路径（用于向上清理"含差异"标记）
     */
    private void replaceNodeContent(DefaultMutableTreeNode targetNode, WzObject sourceObj, EditPane sourcePane, String sourcePath) {
        WzObject targetObj = (WzObject) targetNode.getUserObject();
        DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) targetNode.getParent();

        boolean parentSupported = parentNode != null
                && parentNode.getUserObject() instanceof WzObject parentCheck
                && (parentCheck instanceof WzDirectory
                || parentCheck instanceof WzImage
                || (parentCheck instanceof WzImageProperty pp && pp.isListProperty()));
        if (!parentSupported || targetObj instanceof WzFolder || sourceObj instanceof WzFolder) {
            MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("diff.replace.unsupported", targetObj.getName()));
            return;
        }
        WzObject parentObj = (WzObject) parentNode.getUserObject();

        new SwingWorker<WzObject, Void>() {
            @Override
            protected WzObject doInBackground() {
                return sourceObj.deepClone(null); // 深克隆较重（Canvas 全部解码进内存），放后台
            }

            @Override
            protected void done() {
                WzObject clone;
                try {
                    clone = get();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }

                clone.setNameAnyway(targetObj.getName());
                clone.setParent(parentObj);

                // 接线 reader / wzImage，与 doPaste 一致
                if (parentObj instanceof WzDirectory wzDirectory) {
                    setPasteWzFileAndReader(List.of(clone), wzDirectory.getWzFile());
                } else if (parentObj instanceof WzImage wzImg) {
                    setPasteWzImage(List.of(clone), wzImg);
                } else if (parentObj instanceof WzImageProperty wzProp) {
                    setPasteWzImage(List.of(clone), wzProp.getWzImage());
                }
                clone.setTempChanged(true);

                int index = parentNode.getIndex(targetNode);
                if (targetObj instanceof DiffGhostObject) {
                    // 占位节点不在数据模型里，只清理树和标记
                    diffMarks.remove(targetObj);
                    ghostNodes.remove(targetNode);
                } else {
                    removeWzObjChild(parentObj, targetObj);
                }
                removeNodeFromTree(targetNode);
                addWzObjChild(parentObj, clone);
                DefaultMutableTreeNode newNode = insertNodeToTree(parentNode, clone, true, index);

                // 两侧内容已一致，清掉来源子树的差异标记（目标侧旧对象随移除自动失效），并向上递归清理"含差异"标记
                WzDiffUtil.clearMarks(sourceObj, sourcePane.getDiffMarks());
                cleanupAncestorMarks(newNode);
                sourcePane.cleanupAncestorMarksByPath(sourcePath);

                tree.repaint();
                sourcePane.getTree().repaint();
                tree.setSelectionPath(new TreePath(newNode.getPath()));
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("diff.replace.node_done", clone.getName()));
            }
        }.execute();
    }

    private String getNodePathText(WzObject wzObject, String npcAction) {
        String text = wzObject.getPath();
        switch (wzObject) {
            case WzFolder obj -> text = text + "  /  " + obj.getKeyBoxName();
            case WzDirectory obj when obj.isWzFile() ->
                text = text + "  /  " + obj.getWzFile().getKeyBoxName() + "  /  " + MainFrame.i18n.get("version") + " " + obj.getWzFile().getHeader().getFileVersion();
            case WzImageFile obj -> text = text + "  /  " + obj.getKeyBoxName();
            default -> {
            }
        }

        if (npcAction != null) {
            text = text + " (" + npcAction + ")";
        }
        return text;
    }

    /**
     * 双击事件
     *
     * @param node 节点
     * @return SwingWorker 用于捕获异常
     */
    private SwingWorker<Void, Void> handleTreeDoubleClick(DefaultMutableTreeNode node) {
        WzObject wzObject = (WzObject) node.getUserObject();
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.loading", wzObject.getName()));

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                expandTreeNode(node, true, true, false);
                tree.expandPath(new TreePath(node.getPath()));
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.loaded", wzObject.getName()));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        worker.execute();
        return worker;
    }

    /**
     * 展开叶子节点，加载子节点进来
     *
     * @param node     叶子节点
     * @param parseWz  如果是 wz 是否解析
     * @param parseImg 如果是 img 是否解析
     * @param expand   是否展开节点
     */
    public void expandTreeNode(DefaultMutableTreeNode node, boolean parseWz, boolean parseImg, boolean expand) {
        if (!node.isLeaf()) return;

        WzObject wzObject = (WzObject) node.getUserObject();
        switch (wzObject) {
            case WzFolder folder -> {
                List<WzObject> children = folder.getChildren();
                WzTool.sortWzObjects(children);
                children.forEach(child -> insertNodeToTree(node, child, expand));
            }
            case WzDirectory wzDir -> {
                if (wzDir.isWzFile() && parseWz && !wzDir.getWzFile().parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzDir.getName(), wzDir.getWzFile().getStatus().getMessage()));
                    throw new RuntimeException();
                }
                addChildrenRecursively(node, wzDir, expand);
            }
            case WzImage wzImg -> {
                if (parseImg && !wzImg.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImg.getName(), wzImg.getStatus().getMessage()));
                    throw new RuntimeException();
                }
                addChildrenRecursively(node, wzImg, expand);
            }
            case WzImageProperty property -> addChildrenRecursively(node, property, expand); // 粘贴后的 List 节点
            default -> {
            }
        }
    }

    /**
     * 递归操作，将 WzObject 的 children 递归加入到节点来
     * <p>
     * 提示：不涉及解析，为解析的节点，children 为 0
     *
     * @param node     要展开的节点
     * @param wzObject 目标 WzObject
     * @param expand   是否展开
     */
    private void addChildrenRecursively(DefaultMutableTreeNode node, WzObject wzObject, boolean expand) {
        if (node.getChildCount() > 0) return;

        List<? extends WzObject> children = null;
        if (wzObject instanceof WzDirectory wzDir) {
            children = wzDir.getChildren();
        } else if (wzObject instanceof WzImage wzImg) {
            children = wzImg.getChildren();
        } else if (wzObject instanceof WzImageProperty prop) {
            children = prop.getChildren();
        }

        if (children == null || children.isEmpty()) return;

        WzTool.sortWzObjects(children);

        for (WzObject child : children) {
            DefaultMutableTreeNode childNode = insertNodeToTree(node, child, expand);
            addChildrenRecursively(childNode, child, false);
        }
    }

    /**
     * 支持空白处选中节点
     *
     * @param e MouseEvent
     * @return TreePath
     */
    private TreePath getTreePath(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();

        TreePath path = tree.getClosestPathForLocation(x, y);
        if (path == null) return null;

        // 判断 y 是否真的落在该行高度范围内
        Rectangle bounds = tree.getPathBounds(path);
        if (bounds == null || y < bounds.y || y > bounds.y + bounds.height) {
            return null;
        }

        return path;
    }

    /**
     * 快捷键
     */
    private void addKeyEvent() {
        // 获取 JTree 的输入映射和动作映射
        InputMap im = tree.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = tree.getActionMap();

        // 空格键 单击事件
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "space");
        am.put("space", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath[] selectedPaths = tree.getSelectionPaths();
                if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

                TreePath path = selectedPaths[0];
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (!node.isLeaf()) {
                    if (tree.isExpanded(path)) {
                        collapseAll(path);
                    } else {
                        tree.expandPath(path);
                        Rectangle bounds = tree.getPathBounds(path);
                        if (bounds == null) return;

                        Rectangle visible = tree.getVisibleRect();

                        // 计算当前可视区域的中线
                        int middleY = visible.y + visible.height / 2;

                        // 如果 node 在“下半区域”
                        if (bounds.y > middleY) {
                            tree.scrollRectToVisible(
                                new Rectangle(0, bounds.y, 1, visible.height)
                            );
                        }
                    }
                }
            }
        });

        // Delete 删除
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete");
        am.put("delete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath[] selectedPaths = tree.getSelectionPaths();
                if (selectedPaths == null || selectedPaths.length == 0) return;

                DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
                WzObject wzObject = (WzObject) node.getUserObject();
                if (wzObject instanceof WzFolder) {
                    log.warn(MainFrame.i18n.get("warn.folder_not_supported_delete"));
                } else if (wzObject instanceof WzDirectory directory) {
                    if (directory.isWzFile()) {
                        log.warn(MainFrame.i18n.get("warn.wz_not_supported_delete"));
                    } else {
                        wzDirectoryPopupMenu.getBtnDelete().doClick();
                    }
                } else if (wzObject instanceof WzImageFile) {
                    log.warn(MainFrame.i18n.get("warn.img_not_supported_delete"));
                } else if (wzObject instanceof WzXmlFile) {
                    log.warn(MainFrame.i18n.get("warn.xml_not_supported_delete"));
                } else if (wzObject instanceof WzImage) {
                    wzImagePopupMenu.getBtnDelete().doClick();
                } else if (wzObject instanceof WzImageProperty prop) {
                    if (prop.isListProperty()) {
                        wzListPropertyPopupMenu.getBtnDelete().doClick();
                    } else {
                        wzValuePropertyPopupMenu.getBtnDelete().doClick();
                    }
                }
            }
        });

        // Ctrl+C 复制
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy");
        am.put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath[] selectedPaths = tree.getSelectionPaths();
                if (selectedPaths == null || selectedPaths.length == 0) return;

                DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
                WzObject wzObject = (WzObject) node.getUserObject();
                if (wzObject instanceof WzFolder) {
                    log.warn(MainFrame.i18n.get("warn.folder_not_supported_copy"));
                } else if (wzObject instanceof WzDirectory directory) {
                    if (directory.isWzFile()) {
                        log.warn(MainFrame.i18n.get("warn.wz_not_supported_copy"));
                    } else {
                        wzDirectoryPopupMenu.getBtnCopy().doClick();
                    }
                } else if (wzObject instanceof WzImageFile) {
                    wzImageFilePopupMenu.getBtnCopy().doClick();
                } else if (wzObject instanceof WzXmlFile) {
                    wzXmlFilePopupMenu.getBtnCopy().doClick();
                } else if (wzObject instanceof WzImage) {
                    wzImagePopupMenu.getBtnCopy().doClick();
                } else if (wzObject instanceof WzImageProperty prop) {
                    if (prop.isListProperty()) {
                        wzListPropertyPopupMenu.getBtnCopy().doClick();
                    } else {
                        wzValuePropertyPopupMenu.getBtnCopy().doClick();
                    }
                }
            }
        });

        // Ctrl+V 粘贴
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste");
        am.put("paste", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath[] selectedPaths = tree.getSelectionPaths();
                if (selectedPaths == null) return;

                DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
                WzObject wzObject = (WzObject) node.getUserObject();
                if (wzObject instanceof WzFolder) {
                    log.warn(MainFrame.i18n.get("warn.folder_not_supported_paste"));
                } else if (wzObject instanceof WzDirectory directory) {
                    if (directory.isWzFile()) {
                        wzFilePopupMenu.getBtnPaste().doClick();
                    } else {
                        wzDirectoryPopupMenu.getBtnPaste().doClick();
                    }
                } else if (wzObject instanceof WzImageFile) {
                    wzImageFilePopupMenu.getBtnPaste().doClick();
                } else if (wzObject instanceof WzXmlFile) {
                    wzXmlFilePopupMenu.getBtnPaste().doClick();
                } else if (wzObject instanceof WzImage) {
                    wzImagePopupMenu.getBtnPaste().doClick();
                } else if (wzObject instanceof WzImageProperty prop) {
                    if (prop.isListProperty()) {
                        wzListPropertyPopupMenu.getBtnPaste().doClick();
                    } else {
                        log.warn(MainFrame.i18n.get("warn.prop_is_not_list"));
                    }
                }
            }
        });

        // Ctrl+F 搜索
        EditPane editPane = this;
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "search");
        am.put("search", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                SearchFormData option;
                while (true) {
                    option = searchDialog.getData();
                    if (option == null) break;

                    if (!option.nameMod() && !option.valueMod()) {
                        JMessageUtil.warn(MainFrame.i18n.get("warn.search_name_or_value"));
                        continue;
                    }

                    TreePath[] selectedPaths;
                    if (option.globalMod()) {
                        int childCount = treeRoot.getChildCount();
                        selectedPaths = new TreePath[childCount];
                        for (int i = 0; i < childCount; i++) {
                            DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
                            selectedPaths[i] = new TreePath(child.getPath());
                        }
                    } else {
                        selectedPaths = tree.getSelectionPaths();
                    }

                    if (selectedPaths == null || selectedPaths.length == 0) {
                        JMessageUtil.error(MainFrame.i18n.get("warn.select_search_target_or_check_global"));
                        continue;
                    }

                    searchResults.clear();
                    for (TreePath treePath : selectedPaths) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                        SearchUtil.search(
                            option.search(),
                            option.nameMod(),
                            option.valueMod(),
                            option.equalMod(),
                            option.lowMod(),
                            option.parseImgMod(),
                            searchResults,
                            node,
                            editPane
                        );
                    }

                    String title = MainFrame.i18n.get("search.result") + option.search() + "'";
                    SearchResultDialog dialog = new SearchResultDialog(null, title, searchResults, editPane);
                    dialog.setVisible(true);
                    break;
                }
            }
        });

        // Ctrl+S 保存
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "save");
        am.put("save", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                save();
            }
        });

        // 按键搜索
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                TreePath path = tree.getSelectionPath();
                if (path == null) return;

                char c = e.getKeyChar();
                if (!Character.isLetterOrDigit(c)) return;

                Instant now = Instant.now();
                if (now.minusMillis(500).isAfter(lastInputTime)) {
                    inputBuffer.setLength(0); // 超时 → 重置
                }
                lastInputTime = now;

                inputBuffer.append(c);
                String prefix = inputBuffer.toString().toLowerCase();


                selectNextMatchingSibling(prefix, path, true);
            }
        });
    }

    // Tree 操作 --------------------------------------------------------------------------------------------------------

    /**
     * 将 WzObject 插入到指定节点的末尾
     *
     * @param parentNode 目标节点
     * @param object     要插入的 WzObject
     * @param expand     插入后展开/刷新上级节点
     * @return 插入后生成的新 Node
     */
    public DefaultMutableTreeNode insertNodeToTree(DefaultMutableTreeNode parentNode, WzObject object, boolean expand) {
        return insertNodeToTree(parentNode, object, expand, -1);
    }

    /**
     * 将 WzObject 插入到指定节点的指定位置
     *
     * @param parentNode 目标节点
     * @param object     要插入的 WzObject
     * @param expand     插入后刷新/展开目标节点
     * @param index      插入位置
     * @return 插入后生成的新 Node
     */
    public DefaultMutableTreeNode insertNodeToTree(DefaultMutableTreeNode parentNode, WzObject object, boolean expand, int index) {
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(object);
        treeModel.insertNodeInto(newNode, parentNode, index == -1 ? parentNode.getChildCount() : index);

        if (expand) {
            tree.expandPath(new TreePath(parentNode.getPath()));
        }

        return newNode;
    }

    /**
     * 从树里移除节点
     *
     * @param node 任意节点
     */
    public void removeNodeFromTree(DefaultMutableTreeNode node) {
        if (node == null) return;
        if (node.getParent() == null) return;

        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            removeNodeFromTree(child);
        }
        node.setUserObject(null);
        treeModel.removeNodeFromParent(node);
    }

    /**
     * 根据路径在树里查找 WzObject
     *
     * @param path 用 / 隔开，不含 Root
     * @return WzObject
     */
    public WzObject findWzObjectInTreeByPath(String path) {
        DefaultMutableTreeNode node = treeRoot;
        String[] paths = path.split("/");
        for (int i = 0; i < paths.length; i++) {
            node = findTreeNodeByName(node, paths[i]);
            if (node == null) break;

            if (i == paths.length - 1) {
                return (WzObject) node.getUserObject();
            } else {
                if (node.isLeaf()) {
                    handleTreeDoubleClick(node);
                } else {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
        }

        return null;
    }

    /**
     * 根据路径直接在 WzObject 数据模型里查找对象（必要时触发 parse），不依赖树节点是否已展开。
     * <p>
     * 与 {@link #findWzObjectInTreeByPath(String)} 的区别：后者靠树节点寻路，未展开的层级依赖异步展开，
     * 从后台线程调用时可能因为节点尚未物化而找不到；本方法在数据模型上同步寻路，适合后台任务使用。
     *
     * @param path 用 / 隔开，不含 Root
     * @return WzObject，找不到返回 null
     */
    public WzObject findWzObjectByModelPath(String path) {
        String[] paths = path.split("/");

        WzObject current = null;
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            WzObject obj = (WzObject) ((DefaultMutableTreeNode) treeRoot.getChildAt(i)).getUserObject();
            if (obj.getName().equals(paths[0])) {
                current = obj;
                break;
            }
        }

        for (int i = 1; i < paths.length && current != null; i++) {
            current = findModelChildByName(current, paths[i]);
        }
        return current;
    }

    private WzObject findModelChildByName(WzObject parent, String name) {
        switch (parent) {
            case WzFolder folder -> {
                for (WzObject child : folder.getChildren()) {
                    if (child.getName().equals(name)) return child;
                }
                return null;
            }
            case WzDirectory wzDir -> {
                if (wzDir.isWzFile() && !wzDir.getWzFile().parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzDir.getName(), wzDir.getWzFile().getStatus().getMessage()));
                    return null;
                }
                WzDirectory dir = wzDir.getDirectory(name);
                return dir != null ? dir : wzDir.getImage(name);
            }
            case WzImage wzImg -> {
                if (!wzImg.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImg.getName(), wzImg.getStatus().getMessage()));
                    return null;
                }
                return wzImg.getChild(name);
            }
            case WzImageProperty prop -> {
                return prop.getChild(name);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 跳转到 path 对应的节点
     *
     * @param paths 节点路径，不含 Root
     */
    public void focusNodeByPath(List<String> paths) {
        navigateToPath(paths, true, false, true);
    }

    public void focusNodeByPath(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        ensureRootNodeParsed(path);
        navigateToPath(List.of(path.split("/")), true, false, true);
    }

    public void focusNodeByReference(String rootPath, String nodePath) {
        DefaultMutableTreeNode node = findTreeNodeByReference(rootPath, nodePath);
        if (node == null) {
            return;
        }
        TreePath treePath = new TreePath(node.getPath());
        tree.setSelectionPath(treePath);
        tree.scrollPathToVisible(treePath);
    }

    /**
     * 搜索 node 的下一级，查找符合名称的节点
     *
     * @param parent 要查找的 node
     * @param name   要查找的名称 全匹配
     * @return child node
     */
    public DefaultMutableTreeNode findTreeNodeByName(DefaultMutableTreeNode parent, String name) {
        for (int j = 0; j < parent.getChildCount(); j++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(j);
            if (name.equals(((WzObject) child.getUserObject()).getName())) {
                return child;
            }
        }
        return null;
    }

    public DefaultMutableTreeNode findTreeNodeByPath(String path) {
        DefaultMutableTreeNode node = treeRoot;
        String[] paths = path.split("/");
        for (String item : paths) {
            node = findTreeNodeByName(node, item);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    public DefaultMutableTreeNode findTreeNodeByReference(String rootPath, String nodePath) {
        DefaultMutableTreeNode node = findRootTreeNodeByRootPath(rootPath);
        if (node == null) {
            return null;
        }
        ensureRootNodeParsed(node);
        if (nodePath == null || nodePath.isBlank()) {
            return node;
        }
        String[] paths = nodePath.split("/");
        for (String item : paths) {
            if (item.isBlank()) {
                continue;
            }
            if (node.isLeaf()) {
                waitForWorker(handleTreeDoubleClick(node));
            } else {
                tree.expandPath(new TreePath(node.getPath()));
            }
            node = findTreeNodeBySegment(node, item);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    public void ensureRootNodeParsed(String path) {
        DefaultMutableTreeNode rootNode = findRootTreeNode(path);
        ensureRootNodeParsed(rootNode);
    }

    public void ensureRootNodeParsedByRootPath(String rootPath) {
        DefaultMutableTreeNode rootNode = findRootTreeNodeByRootPath(rootPath);
        ensureRootNodeParsed(rootNode);
    }

    private void ensureRootNodeParsed(DefaultMutableTreeNode rootNode) {
        if (rootNode == null || !rootNode.isLeaf()) {
            return;
        }
        expandTreeNode(rootNode, true, true, false);
    }

    public void reloadFilePreservingState(String path) {
        DefaultMutableTreeNode node = findRootTreeNode(path);
        if (node == null) {
            return;
        }
        WzKey key = extractNodeKey(node);
        if (key == null) {
            return;
        }
        reloadFilePreservingState(node, key);
    }

    public void reloadFilePreservingStateByRootPath(String rootPath) {
        DefaultMutableTreeNode node = findRootTreeNodeByRootPath(rootPath);
        if (node == null) {
            return;
        }
        WzKey key = extractNodeKey(node);
        if (key == null) {
            return;
        }
        reloadFilePreservingState(node, key);
    }

    private boolean navigateToPath(List<String> paths, boolean selectTarget, boolean expandTarget, boolean scrollTarget) {
        if (paths == null || paths.isEmpty()) {
            return false;
        }

        DefaultMutableTreeNode node = treeRoot;
        for (int i = 0; i < paths.size(); i++) {
            node = findTreeNodeByName(node, paths.get(i));
            if (node == null) {
                return false;
            }

            TreePath treePath = new TreePath(node.getPath());
            boolean isLast = i == paths.size() - 1;
            if (isLast) {
                if (expandTarget) {
                    if (node.isLeaf()) {
                        waitForWorker(handleTreeDoubleClick(node));
                    } else {
                        tree.expandPath(treePath);
                    }
                }
                if (selectTarget) {
                    tree.setSelectionPath(treePath);
                    if (scrollTarget) {
                        tree.scrollPathToVisible(treePath);
                    }
                }
                continue;
            }

            if (node.isLeaf()) {
                waitForWorker(handleTreeDoubleClick(node));
            } else {
                tree.expandPath(treePath);
            }
        }
        return true;
    }

    private void waitForWorker(SwingWorker<Void, Void> worker) {
        try {
            worker.get();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private DefaultMutableTreeNode findRootTreeNode(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] paths = path.split("/");
        if (paths.length == 0 || paths[0].isBlank()) {
            return null;
        }
        return findTreeNodeByName(treeRoot, paths[0]);
    }

    private DefaultMutableTreeNode findRootTreeNodeByRootPath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) {
            return null;
        }
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
            if (child.getUserObject() instanceof WzObject wzObject
                && NodePathResolver.sameRootPath(NodePathResolver.rootPathOf(wzObject), rootPath)) {
                return child;
            }
        }
        return null;
    }

    private DefaultMutableTreeNode findTreeNodeBySegment(DefaultMutableTreeNode parent, String segment) {
        SegmentSelector selector = parseSegment(selectorText(segment));
        int sameNameIndex = 0;
        for (int j = 0; j < parent.getChildCount(); j++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(j);
            if (!(child.getUserObject() instanceof WzObject wzObject)
                || !selector.name().equalsIgnoreCase(wzObject.getName())) {
                continue;
            }
            if (selector.index() == null || selector.index() == sameNameIndex) {
                return child;
            }
            sameNameIndex++;
        }
        return null;
    }

    private String selectorText(String segment) {
        return segment == null ? "" : segment;
    }

    private SegmentSelector parseSegment(String segment) {
        Matcher matcher = INDEXED_NODE_SEGMENT.matcher(segment);
        if (!matcher.matches()) {
            return new SegmentSelector(segment, null);
        }
        return new SegmentSelector(matcher.group(1), Integer.parseInt(matcher.group(2)));
    }

    private record SegmentSelector(String name, Integer index) {
    }

    private WzKey extractNodeKey(DefaultMutableTreeNode node) {
        if (node == null || !(node.getUserObject() instanceof WzObject wzObject)) {
            return null;
        }
        if (wzObject instanceof WzFolder folder) {
            return new WzKey(-1, folder.getKeyBoxName(), folder.getIv(), folder.getKey());
        }
        if (wzObject instanceof WzDirectory dir && dir.isWzFile()) {
            WzFile wzFile = dir.getWzFile();
            return new WzKey(-1, wzFile.getKeyBoxName(), wzFile.getIv(), wzFile.getKey());
        }
        if (wzObject instanceof WzSavableFile file) {
            return new WzKey(-1, file.getKeyBoxName(), file.getIv(), file.getKey());
        }
        return null;
    }

    private void reloadFilePreservingState(DefaultMutableTreeNode node, WzKey key) {
        String rootPath = getNodePath(node);
        if (rootPath == null) {
            return;
        }

        List<String> expandedPaths = snapshotExpandedRelativePaths(node, rootPath);
        String selectedPath = snapshotSelectedPath(rootPath);
        String selectedRelativePath = toRelativePath(selectedPath, rootPath);

        DefaultMutableTreeNode newNode = reloadFile(node, key);
        if (newNode == null) {
            return;
        }

        String newRootPath = getNodePath(newNode);
        if (newRootPath == null) {
            return;
        }

        ensureRootNodeParsed(newRootPath);
        restoreExpandedRelativePaths(newRootPath, expandedPaths);
        if (selectedRelativePath != null) {
            focusNodeByPath(joinRelativePath(newRootPath, selectedRelativePath));
        }
    }

    private List<String> snapshotExpandedRelativePaths(DefaultMutableTreeNode rootNode, String rootPath) {
        List<String> result = new ArrayList<>();
        collectExpandedRelativePaths(rootNode, rootPath, result);
        return result;
    }

    private void collectExpandedRelativePaths(DefaultMutableTreeNode node, String rootPath, List<String> collector) {
        String currentPath = getNodePath(node);
        if (currentPath == null) {
            return;
        }

        TreePath treePath = new TreePath(node.getPath());
        if (tree.isExpanded(treePath)) {
            collector.add(toRelativePath(currentPath, rootPath));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectExpandedRelativePaths((DefaultMutableTreeNode) node.getChildAt(i), rootPath, collector);
        }
    }

    private void restoreExpandedRelativePaths(String rootPath, List<String> expandedPaths) {
        for (String relativePath : expandedPaths) {
            if (relativePath == null) {
                continue;
            }
            String fullPath = joinRelativePath(rootPath, relativePath);
            if (relativePath.isBlank()) {
                DefaultMutableTreeNode node = findTreeNodeByPath(fullPath);
                if (node != null) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
                continue;
            }
            navigateToPath(List.of(fullPath.split("/")), false, true, false);
        }
    }

    private String snapshotSelectedPath(String rootPath) {
        TreePath selectedPath = tree.getSelectionPath();
        if (selectedPath == null) {
            return null;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
        String path = getNodePath(node);
        if (path == null || !isPathUnderRoot(path, rootPath)) {
            return null;
        }
        return path;
    }

    private boolean isPathUnderRoot(String path, String rootPath) {
        return path.equals(rootPath) || path.startsWith(rootPath + "/");
    }

    private String toRelativePath(String path, String rootPath) {
        if (path == null || rootPath == null || !isPathUnderRoot(path, rootPath)) {
            return null;
        }
        if (path.equals(rootPath)) {
            return "";
        }
        return path.substring(rootPath.length() + 1);
    }

    private String joinRelativePath(String rootPath, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return rootPath;
        }
        return rootPath + "/" + relativePath;
    }

    private String getNodePath(DefaultMutableTreeNode node) {
        if (node == null || !(node.getUserObject() instanceof WzObject wzObject)) {
            return null;
        }
        return wzObject.getPath();
    }

    public List<WzObject> snapshotRootObjects() {
        List<WzObject> roots = new ArrayList<>();
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) treeRoot.getChildAt(i);
            Object userObject = child.getUserObject();
            if (userObject instanceof WzObject wzObject) {
                roots.add(wzObject);
            }
        }
        return roots;
    }

    /**
     * 在当前选中节点的同级节点中：
     * 1. 先从“当前节点之后”找第一个以 prefix 开头的
     * 2. 如果没找到，再从“当前节点之前”找第一个
     */
    private void selectNextMatchingSibling(String prefix, TreePath path, boolean toNext) {
        DefaultMutableTreeNode current = (DefaultMutableTreeNode) path.getLastPathComponent();
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) current.getParent();
        if (parent == null) return;

        int count = parent.getChildCount();
        int currentIndex = toNext ? parent.getIndex(current) : parent.getChildCount();

        DefaultMutableTreeNode target = null;
        // 从当前之后查找
        if (toNext) {
            for (int i = currentIndex; i < count; i++) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getChildAt(i);
                WzObject wzObject = (WzObject) node.getUserObject();
                if (wzObject.getName().toLowerCase().startsWith(prefix)) {
                    target = node;
                    break;
                }
            }
        }

        if (target == null) {
            // 从当前之前查找
            for (int i = 0; i < currentIndex; i++) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) parent.getChildAt(i);
                WzObject wzObject = (WzObject) node.getUserObject();
                if (wzObject.getName().toLowerCase().startsWith(prefix)) {
                    target = node;
                    break;
                }
            }
        }

        if (target != null) {
            TreePath newPath = new TreePath(target.getPath());
            tree.setSelectionPath(newPath);
            tree.scrollPathToVisible(newPath);
        } else {
            // 找不到的情况下，试着往下一级去找
            if (!current.isLeaf() && tree.isExpanded(path)) {
                selectNextMatchingSibling(prefix, new TreePath(((DefaultMutableTreeNode) current.getFirstChild()).getPath()), false);
            }
        }
    }

    private static class FileDropTransferHandler extends TransferHandler {
        private final EditPane editPane;

        public FileDropTransferHandler(EditPane editPane) {
            this.editPane = editPane;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;

            try {
                List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);

                if (files.isEmpty()) return false;

                for (File file : files) {
                    if (file.isFile()) {

                        String name = file.getName().toLowerCase();
                        if (!(name.endsWith(".xml") || name.endsWith(".wz") || name.endsWith(".img"))) {
                            JMessageUtil.error(MainFrame.i18n.get("test.temp0001"));
                            return false;
                        }
                    }
                }

                JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
                TreePath path = dl.getPath();

                if (path == null) {
                    editPane.loadFiles(files);
                } else {
                    DefaultMutableTreeNode parent = (DefaultMutableTreeNode) path.getLastPathComponent();
                    WzObject wzObject = (WzObject) parent.getUserObject();
                    if (wzObject instanceof WzFolder) {
                        editPane.attachFolder(parent, files);
                    } else if (wzObject instanceof WzDirectory) {
                        List<File> imgFiles = new ArrayList<>();
                        List<File> xmlFiles = new ArrayList<>();
                        List<File> dirs = new ArrayList<>();
                        for (File file : files) {
                            String filename = file.getName();
                            if (file.isDirectory()) {
                                dirs.add(file);
                            } else if (filename.endsWith(".img")) {
                                imgFiles.add(file);
                            } else if (filename.endsWith(".xml")) {
                                xmlFiles.add(file);
                            } else {
                                log.warn(MainFrame.i18n.get("test.temp0002"));
                            }
                        }

                        editPane.attachWzDir(parent, dirs);
                        editPane.attachImg(parent, imgFiles);
                        editPane.attachXml(parent, xmlFiles);
                    } else {
                        log.warn(MainFrame.i18n.get("test.temp0003"));
                    }
                }


                return true;
            } catch (Exception e) {
                log.error(MainFrame.i18n.get("test.temp0004", e.getMessage()));
            }
            return false;
        }
    }

    /**
     * 根据 node 定位到文件层 node
     */
    public DefaultMutableTreeNode findFileNode(DefaultMutableTreeNode node) {
        DefaultMutableTreeNode p = (DefaultMutableTreeNode) node.getParent();
        WzObject wzObject = (WzObject) p.getUserObject();
        if (wzObject instanceof WzSavableFile) {
            return p;
        } else return findFileNode(p);
    }

    // 编辑框 -----------------------------------------------------------------------------------------------------------

    /**
     * 重置编辑框，避免编辑框里的 WzObject 占着已卸载的对象，无法释放内存
     */
    public void resetValueForm() {
        getNodeForm().setData("", "", null, this);
        switchForm("node");
    }

    private void clear() {
        resetValueForm();
        System.gc();
    }

    // 同步 -------------------------------------------------------------------------------------------------------------

    /**
     * 在树里查找到 WzObject 对象（根据 WzObject.getPath 相对路径），并同步单击操作
     *
     * @param object 目标 WzObject
     */
    public void syncTreeClick(WzObject object) {
        if (!MainFrame.getInstance().getCenterPane().isRightShowing()) return;
        if (!MainFrame.getInstance().getCenterPane().isSync()) return;

        DefaultMutableTreeNode node = treeRoot;
        String[] paths = object.getPath().split("/");
        for (int i = 0; i < paths.length; i++) {
            node = findTreeNodeByName(node, paths[i]);
            if (node == null) break;

            if (i == paths.length - 1) {
                tree.setSelectionPath(new TreePath(node.getPath()));
            } else {
                if (node.isLeaf()) {
                    handleTreeDoubleClick(node);
                } else {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
        }
    }

    /**
     * 在树里查找到 WzObject 对象（根据 WzObject.getPath 相对路径），并同步展开/收缩/双击操作
     *
     * @param object      目标 WzObject
     * @param finalAction 要同步的操作
     */
    public void syncTreeDoubleClick(WzObject object, EditPaneAction finalAction) {
        if (!MainFrame.getInstance().getCenterPane().isRightShowing()) return;
        if (!MainFrame.getInstance().getCenterPane().isSync()) return;

        DefaultMutableTreeNode node = treeRoot;
        String[] paths = object.getPath().split("/");
        for (int i = 0; i < paths.length; i++) {
            node = findTreeNodeByName(node, paths[i]);
            if (node == null) break;

            if (i == paths.length - 1) {
                if (finalAction == EditPaneAction.ACTION) {
                    handleTreeDoubleClick(node);
                } else if (finalAction == EditPaneAction.COLLAPSE) {
                    collapseAll(new TreePath(node.getPath()));
                } else if (finalAction == EditPaneAction.EXPAND) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            } else {
                if (node.isLeaf()) {
                    handleTreeDoubleClick(node);
                } else {
                    tree.expandPath(new TreePath(node.getPath()));
                }
            }
        }
    }

    // 加载 -------------------------------------------------------------------------------------------------------------
    private void loadFiles(DefaultMutableTreeNode pNode, List<File> files, WzKey key) {
        files.forEach(f -> {
            if (f.isFile()) {
                if (f.getName().endsWith("List.wz")) {
                    new ListEditor(f.getAbsolutePath(), key);
                } else if (f.getName().endsWith(".wz")) {
                    WzFile wzFile = new WzFile(f.getAbsolutePath(), (short) -1, key.getName(), key.getIv(), key.getUserKey());
                    insertNodeToTree(pNode, wzFile.getWzDirectory(), true);
                } else if (f.getName().endsWith(".img")) {
                    WzImageFile wzImageFile = new WzImageFile(f.getName(), f.getAbsolutePath(), key.getName(), key.getIv(), key.getUserKey());
                    insertNodeToTree(pNode, wzImageFile, true);
                } else if (f.getName().endsWith(".xml")) {
                    WzXmlFile wzXmlFile = new WzXmlFile(f.getName(), f.getAbsolutePath(), key.getName(), key.getIv(), key.getUserKey());
                    insertNodeToTree(pNode, wzXmlFile, true);
                }
            } else if (f.isDirectory()) {
                WzFolder folder = new WzFolder(f.getAbsolutePath(), key.getName(), key.getIv(), key.getUserKey());
                insertNodeToTree(pNode, folder, true);
            }
        });
    }

    /**
     * 将文件加载到树里
     *
     * @param files 要打开的文件列表
     */
    public void loadFiles(List<File> files) {
        WzKey key = (WzKey) MainFrame.getInstance().getKeyBox().getSelectedItem();
        if (key == null) {
            MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("warn.not_select_key"));
            return;
        }

        loadFiles(treeRoot, files, key);

        // 记录打开过的文件夹，供菜单栏的"最近打开的文件夹"使用
        files.stream()
                .filter(File::isDirectory)
                .forEach(RecentFolderUtil::add);
    }

    // 重载 -------------------------------------------------------------------------------------------------------------
    private DefaultMutableTreeNode reloadFile(DefaultMutableTreeNode node, WzKey key) {
        DefaultMutableTreeNode pNode = (DefaultMutableTreeNode) node.getParent();
        int index = pNode.getIndex(node);
        WzObject oldObject = (WzObject) node.getUserObject();
        WzObject newObject = null;

        if (oldObject instanceof WzFolder oldFolder) {
            newObject = new WzFolder(oldFolder.getFilePath(), key.getName(), key.getIv(), key.getUserKey());
        } else if (oldObject instanceof WzDirectory wzDir && wzDir.isWzFile()) {
            if (wzDir.getWzFile().isNewFile()) {
                JMessageUtil.error(MainFrame.i18n.get("error.try_reload_new_file"));
                return null;
            }
            WzFile oldWzFile = wzDir.getWzFile();
            String filePath = oldWzFile.getFilePath();
            WzFile newWzFile = new WzFile(filePath, (short) -1, key.getName(), key.getIv(), key.getUserKey());
            newObject = newWzFile.getWzDirectory();
        } else if (oldObject instanceof WzImageFile oldImg) {
            if (oldImg.isNewFile()) {
                JMessageUtil.error(MainFrame.i18n.get("error.try_reload_new_file"));
                return null;
            }
            Path filePath = Path.of(oldImg.getFilePath());
            String filename = filePath.getFileName().toString();
            newObject = new WzImageFile(filename, filePath.toString(), key.getName(), key.getIv(), key.getUserKey());
        } else if (oldObject instanceof WzXmlFile oldXml) {
            Path filePath = Path.of(oldXml.getFilePath());
            String filename = filePath.getFileName().toString();
            newObject = new WzXmlFile(filename, filePath.toString(), key.getName(), key.getIv(), key.getUserKey());
        }

        removeNodeFromTree(node);
        DefaultMutableTreeNode newNode = insertNodeToTree(pNode, newObject, true, index);

        if (pNode.getUserObject() instanceof WzFolder wzFolder) {
            wzFolder.remove(oldObject);
            wzFolder.add(newObject);
        }
        return newNode;
    }

    /**
     * 使用菜单栏的密钥重新载入文件节点
     */
    public void reloadFile() {
        TreePath[] treePaths = tree.getSelectionPaths();
        if (treePaths == null) return;

        WzKey key = (WzKey) MainFrame.getInstance().getKeyBox().getSelectedItem();
        if (key == null) {
            MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("warn.not_select_key"));
            return;
        }

        for (TreePath treePath : treePaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            reloadFilePreservingState(node, key);
        }

        clear();
    }

    // 卸载 -------------------------------------------------------------------------------------------------------------
    public void unload() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            DefaultMutableTreeNode pNode = (DefaultMutableTreeNode) node.getParent();
            if (pNode == null) continue;

            WzObject wzObject = (WzObject) node.getUserObject();
            if (pNode.getUserObject() instanceof WzFolder pFolder) {
                pFolder.remove(wzObject);
            }

            removeNodeFromTree(node);
        }

        clear();
    }

    /**
     * 移除 Root 下全部节点
     */
    public void unloadAll() {
        treeRoot.removeAllChildren();
        treeModel.reload(treeRoot);
        resetValueForm();
    }

    // 排序并改名：对子列表进行排序，并将数值类型的名称按从0开始的自然序数进行改名，使其连续 --------------------------------------------
    public void sortAndReindexChildren() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
        WzObject wzObject = (WzObject) node.getUserObject();

        boolean success;
        if (wzObject instanceof WzListProperty listProperty) {
            success = listProperty.sortAndReindexChildren();
        } else if (wzObject instanceof WzImage image) {
            success = image.sortAndReindexChildren();
        } else {
            MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("warn.only_image_or_list"));
            return;
        }

        if (!success) {
            MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("warn.sort.nothing"));
            return;
        }

        DefaultMutableTreeNode pNode = (DefaultMutableTreeNode) node.getParent();
        int index = pNode.getIndex(node);
        removeNodeFromTree(node);
        insertNodeToTree(pNode, wzObject, true, index);
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
    }

    // 保存 -------------------------------------------------------------------------------------------------------------
    public void save() {
        TreePath[] treePaths = tree.getSelectionPaths();
        if (treePaths == null) return;

        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.file_saving"));
        MainFrame.getInstance().updateProgress(0, 0);
        new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (TreePath treePath : treePaths) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    WzObject wzObject = (WzObject) node.getUserObject();
                    if (wzObject instanceof WzFolder) {
                        saveWzFolder(node);
                    } else if (wzObject instanceof WzSavableFile) {
                        saveFile(node);
                    } else {
                        DefaultMutableTreeNode fileNode = findFileNode(node);
                        saveFile(fileNode);
                    }
                }

                clear();
                return null;
            }

            @Override
            protected void done() {
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.file_saved"));
            }
        }.execute();
    }

    /**
     * 保存文件夹
     *
     * @param node WzFolder 对应的节点
     */
    private void saveWzFolder(DefaultMutableTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            WzObject wzObject = (WzObject) child.getUserObject();
            if (wzObject instanceof WzFolder) {
                saveWzFolder(child);
            } else {
                saveFile(child);
            }
        }
    }

    /**
     * 保存文件
     *
     * @param node WzFile / WzImageFile / WzXmlFile 对应的节点
     */
    private void saveFile(DefaultMutableTreeNode node) {
        WzObject wzObject = (WzObject) node.getUserObject();

        if (wzObject instanceof WzImageFile wzImageFile) {
            if (wzImageFile.isNewFile()) {
                JMessageUtil.error(MainFrame.i18n.get("error.try_save_new_file"));
                return;
            }
        }

        if (wzObject instanceof WzDirectory wzDir && wzDir.isWzFile()) {
            if (wzDir.getWzFile().isNewFile()) {
                JMessageUtil.error(MainFrame.i18n.get("error.try_save_new_file"));
                return;
            }
            wzObject = wzDir.getWzFile();
        }

        if (wzObject instanceof WzSavableFile wz) {
            String keyBoxName = wz.getKeyBoxName();
            byte[] iv = wz.getIv();
            byte[] key = wz.getKey();

            if (wz.getStatus() != WzFileStatus.PARSE_SUCCESS) {
                log.info(MainFrame.i18n.get("status.save_file_skip", wz.getName()));
                return;
            }

            Path oldPath = Path.of(wz.getFilePath());
            String fileName = oldPath.getFileName().toString();
            boolean renamed = !fileName.equals(wz.getName());

            if (renamed) {
                Path newPath = oldPath.resolveSibling(wz.getName());
                wz.setFilePath(newPath.toString());
            }

            if (wz.save()) {
                if (renamed) {
                    FileTool.deleteFile(oldPath);
                }
            } else {
                JMessageUtil.error(MainFrame.i18n.get("error.save_read_log"));
            }
            reloadFilePreservingState(node, new WzKey(-1, keyBoxName, iv, key));
        } else {
            JMessageUtil.error(MainFrame.i18n.get("error.try_save_error"));
        }
    }

    /**
     * 文件另存为
     *
     */
    public void saveAs() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        WzObject wzObject = (WzObject) node.getUserObject();
        if (wzObject instanceof WzDirectory wzDir && wzDir.isWzFile()) {
            wzObject = wzDir.getWzFile();
        }

        if (wzObject instanceof WzSavableFile wz) {
            String keyBoxName = wz.getKeyBoxName();
            byte[] iv = wz.getIv();
            byte[] key = wz.getKey();

            if (wz.getStatus() != WzFileStatus.PARSE_SUCCESS) {
                log.info(MainFrame.i18n.get("status.save_as_file_skip", wz.getName()));
                return;
            }

            File newFile = new File(wz.getFilePath());
            newFile = new File(newFile.getParent(), wz.getName());

            String[] filter = switch (wzObject) {
                case WzFile ignored -> new String[]{"wz"};
                case WzImageFile ignored -> new String[]{"img"};
                case WzXmlFile ignored -> new String[]{"xml"};
                default -> null;
            };

            File saveFile = FileDialog.chooseSaveFile(MainFrame.getInstance(), MainFrame.i18n.get("save") + " " + wz.getName(), newFile, filter);
            if (saveFile == null) {
                return;
            }
            wz.setFilePath(saveFile.getAbsolutePath());
            if (!wz.save()) {
                JMessageUtil.error(MainFrame.i18n.get("error.save_read_log"));
            }
            reloadFilePreservingState(node, new WzKey(-1, keyBoxName, iv, key));
        }
    }

    // 导出 -------------------------------------------------------------------------------------------------------------

    /**
     * 收集要导出的 Img节点
     *
     * @param node      要处理的节点
     * @param folder    用于存放导出文件的文件夹
     * @param collector 收集器
     */
    private void collectExportImg(DefaultMutableTreeNode node, Path folder, List<Pair<WzImage, Path>> collector) {
        WzObject wzObject = (WzObject) node.getUserObject();
        if (wzObject instanceof WzFolder) {
            expandTreeNode(node, false, false, false);
            String name = wzObject.getName().replaceAll("(?i)\\.wz$", "");
            folder = folder.resolve(name);
            int total = node.getChildCount();
            int current = 0;
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectExportImg(child, folder, collector);
                MainFrame.getInstance().updateProgress(++current, total);
            }
        } else if (wzObject instanceof WzDirectory wzDir && wzDir.isWzFile()) {
            WzFile wzFile = wzDir.getWzFile();

            if (!wzFile.parse()) {
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("error.parse", wzFile.getName(), wzFile.getStatus().getMessage()));
                throw new RuntimeException();
            }
            wzFile.exportFileToImg(folder, collector);
        } else if (wzObject instanceof WzImage wzImage) {
            Path p;
            if (wzImage instanceof WzXmlFile wzXmlFile) {
                p = folder.resolve(wzXmlFile.getImgName());
            } else {
                p = folder.resolve(wzImage.getName());
            }

            collector.add(new Pair<>(wzImage, p));
        }
    }

    /**
     * 导出 img
     */
    public void exportImg() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        File folder = FileDialog.chooseOpenFolder(MainFrame.i18n.get("test.temp0005"), FileDialog.KEY_EXPORT);
        if (folder == null) {
            log.info(MainFrame.i18n.get("test.temp0006"));
            return;
        }

        Instant now = Instant.now();
        new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0007"));
                int total = selectedPaths.length;
                int finish = 0;
                List<Pair<WzImage, Path>> collector = new ArrayList<>();
                for (TreePath treePath : selectedPaths) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    collectExportImg(node, folder.toPath(), collector);
                    MainFrame.getInstance().updateProgress(++finish, total);
                }

                total = collector.size();
                finish = 0;
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0008"));
                for (Pair<WzImage, Path> pair : collector) {
                    WzImage wzImage = pair.getLeft();
                    Path path = pair.getRight();
                    wzImage.save(path);
                    MainFrame.getInstance().updateProgress(++finish, total);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    Instant end = Instant.now();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0009", Duration.between(now, end).toSeconds()));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.execute();
    }

    /**
     * 收集要导出的 XML 节点
     *
     * @param node      要处理的节点
     * @param folder    用于存放导出文件的文件夹
     * @param collector 收集器
     */
    private void collectExportXml(DefaultMutableTreeNode node, Path folder, List<Pair<WzImage, Path>> collector) {
        WzObject wzObject = (WzObject) node.getUserObject();

        if (wzObject instanceof WzFolder) {
            expandTreeNode(node, false, false, false);
            folder = folder.resolve(wzObject.getName());

            int total = node.getChildCount();
            int current = 0;
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                collectExportXml(child, folder, collector);
                MainFrame.getInstance().updateProgress(++current, total);
            }
        } else if (wzObject instanceof WzDirectory wzDir && wzDir.isWzFile()) {
            WzFile wzFile = wzDir.getWzFile();

            if (!wzFile.parse()) {
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("error.parse", wzFile.getName(), wzFile.getStatus().getMessage()));
                throw new RuntimeException();
            }

            wzFile.exportFileToXml(folder, collector);
        } else if (wzObject instanceof WzImage wzImage) {
            String filename = wzImage.getName();
            if (!filename.endsWith(".xml")) {
                filename = filename + ".xml";
            }

            collector.add(new Pair<>(wzImage, folder.resolve(filename)));
        }
    }

    /**
     * 导出 XML
     */
    public void exportXml() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        ExportXmlDialog dialog = new ExportXmlDialog(this);
        ExportXmlData data = dialog.getData();
        if (data == null) return;

        Instant now = Instant.now();

        new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                int total = selectedPaths.length;
                int finish = 0;
                List<Pair<WzImage, Path>> collector = new ArrayList<>();
                for (TreePath treePath : selectedPaths) {
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0010"));
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    collectExportXml(node, Path.of(data.getExportPath()), collector);
                    MainFrame.getInstance().updateProgress(++finish, total);
                }

                total = collector.size();
                finish = 0;
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0008"));
                for (Pair<WzImage, Path> pair : collector) {
                    WzImage wzImage = pair.getLeft();
                    Path path = pair.getRight();
                    if (wzImage.exportToXml(path, data.getIndent(), data.getMeType(), data.isLinux())) {
                        MainFrame.getInstance().updateProgress(++finish, total);
                    } else {
                        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0012", wzImage.getName()));
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    Instant end = Instant.now();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0013", Duration.between(now, end).toSeconds()));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.execute();
    }

    // 修改密钥 ----------------------------------------------------------------------------------------------------------

    /**
     * 收集可以修改密钥的文件
     *
     * @param node      目标节点
     * @param collector 收集器
     * @return 是否存在 wz 文件
     */
    private boolean collectChangeKey(DefaultMutableTreeNode node, List<WzObject> collector) {
        WzObject wzObject = (WzObject) node.getUserObject();
        if (wzObject instanceof WzFolder) {
            expandTreeNode(node, false, false, false);
            boolean hasWzFiles = false;
            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                if (collectChangeKey(child, collector)) hasWzFiles = true;
            }
            return hasWzFiles;
        } else if (wzObject instanceof WzDirectory wzDir && wzDir.isWzFile()) {
            collector.add(wzDir.getWzFile());
            return true;
        } else if (wzObject instanceof WzImageFile wzImg) {
            collector.add(wzImg);
        }
        return false;
    }

    /**
     * 修改密钥
     */
    public void changeKey() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        List<WzObject> collector = new ArrayList<>();
        boolean hasWzFile = false;
        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            if (collectChangeKey(node, collector)) hasWzFile = true;
        }

        ChangeKeyDialog dialog = new ChangeKeyDialog(this, hasWzFile);
        KeyData keyData = dialog.getData();
        if (keyData == null) return;

        Instant now = Instant.now();
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0016"));
        int total = collector.size();
        new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                int finish = 0;
                for (WzObject wzObject : collector) {
                    if (wzObject instanceof WzFile wzFile) {
                        wzFile.changeKey(keyData.getVersion(), keyData.getName(), keyData.getIv(), keyData.getKey());
                        wzFile.getWzDirectory().setTempChanged(true);
                    } else if (wzObject instanceof WzImageFile wzImg) {
                        wzImg.changeKey(keyData.getName(), keyData.getIv(), keyData.getKey());
                        wzImg.setTempChanged(true);
                    }
                    MainFrame.getInstance().updateProgress(++finish, total);
                }
                tree.updateUI();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    Instant end = Instant.now();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0017", Duration.between(now, end).toSeconds()));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.execute();
    }

    // 导入--------------------------------------------------------------------------------------------------------------

    /**
     * 导入 Img
     */
    public void importImg() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
        List<File> imgFiles = FileDialog.chooseOpenFiles(new String[]{"img"}, FileDialog.KEY_IMPORT);
        attachImg(node, imgFiles);
    }

    private void attachImg(DefaultMutableTreeNode node, List<File> imgFiles) {
        final int[] count = {0};
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                WzDirectory targetDirectory = (WzDirectory) node.getUserObject();
                WzFile wzFile = targetDirectory.getWzFile();
                String keyBoxName = wzFile.getKeyBoxName();
                byte[] iv = wzFile.getIv();
                byte[] key = wzFile.getKey();
                int total = imgFiles.size();
                OverwriteChoice choice = null;
                int index = 0;
                for (File imgFile : imgFiles) {
                    String imgName = imgFile.getName();
                    if (targetDirectory.existImage(imgName)) {
                        if (choice == OverwriteChoice.SKIP_ALL) continue;
                        else if (choice == OverwriteChoice.OVERWRITE_ALL) {
                            targetDirectory.removeImageChild(imgName);
                            DefaultMutableTreeNode childNode = findTreeNodeByName(node, imgName);
                            index = node.getIndex(childNode);
                            removeNodeFromTree(childNode);
                        } else {
                            choice = OverwriteDialog.show(EditPane.this, imgName);
                            switch (choice) {
                                case OVERWRITE, OVERWRITE_ALL -> {
                                    targetDirectory.removeImageChild(imgName);
                                    DefaultMutableTreeNode childNode = findTreeNodeByName(node, imgName);
                                    index = node.getIndex(childNode);
                                    removeNodeFromTree(childNode);
                                }
                                case SKIP, SKIP_ALL, CANCEL -> {
                                    continue;
                                }
                            }
                        }
                    }
                    String filePathStr = imgFile.getAbsolutePath();
                    WzImageFile wzImageFile = new WzImageFile(imgName, filePathStr, keyBoxName, iv, key);
                    if (!wzImageFile.parse()) {
                        MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("test.temp0018", filePathStr));
                        return null;
                    }
                    WzImage wzImage = wzImageFile.deepClone(targetDirectory);
                    wzImage.setReader(new BinaryReader(wzFile.getWzMutableKey()));
                    wzImage.setChildrenWzImage();
                    wzImage.setTempChanged(true);
                    targetDirectory.addChild(wzImage);
                    insertNodeToTree(node, wzImage, true, index);
                    MainFrame.getInstance().updateProgress(++count[0], total);
                }

                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0019", count[0]));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.execute();
    }

    /**
     * 导入 XML
     */
    public void importXml() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
        List<File> xmlFiles = FileDialog.chooseOpenFiles(new String[]{"xml"}, FileDialog.KEY_IMPORT);
        attachXml(node, xmlFiles);
    }

    private void attachXml(DefaultMutableTreeNode node, List<File> xmlFiles) {
        final int[] count = {0};
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                WzDirectory targetDirectory = (WzDirectory) node.getUserObject();
                WzFile wzFile = targetDirectory.getWzFile();
                String keyBoxName = wzFile.getKeyBoxName();
                byte[] iv = wzFile.getIv();
                byte[] key = wzFile.getKey();
                int total = xmlFiles.size();
                OverwriteChoice choice = null;
                int index = 0;
                for (File xmlFile : xmlFiles) {
                    String imgName = xmlFile.getName();
                    imgName = imgName.endsWith(".xml")
                        ? imgName.substring(0, imgName.length() - 4)
                        : imgName;
                    if (targetDirectory.existImage(imgName)) {
                        if (choice == OverwriteChoice.SKIP_ALL) continue;
                        else if (choice == OverwriteChoice.OVERWRITE_ALL) {
                            targetDirectory.removeImageChild(imgName);
                            DefaultMutableTreeNode childNode = findTreeNodeByName(node, imgName);
                            index = node.getIndex(childNode);
                            removeNodeFromTree(childNode);
                        } else {
                            choice = OverwriteDialog.show(EditPane.this, imgName);
                            switch (choice) {
                                case OVERWRITE, OVERWRITE_ALL -> {
                                    targetDirectory.removeImageChild(imgName);
                                    DefaultMutableTreeNode childNode = findTreeNodeByName(node, imgName);
                                    index = node.getIndex(childNode);
                                    removeNodeFromTree(childNode);
                                }
                                case SKIP, SKIP_ALL, CANCEL -> {
                                    continue;
                                }
                            }
                        }
                    }
                    String filePathStr = xmlFile.getAbsolutePath();
                    WzXmlFile wzXmlFile = new WzXmlFile(imgName, filePathStr, keyBoxName, iv, key);
                    if (!wzXmlFile.parse()) {
                        MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("test.temp0020", filePathStr));
                        return null;
                    }
                    WzImage wzImage = wzXmlFile.deepClone(targetDirectory);
                    wzImage.setReader(new BinaryReader(wzFile.getWzMutableKey()));
                    wzImage.setChildrenWzImage();
                    wzImage.setTempChanged(true);
                    targetDirectory.addChild(wzImage);
                    insertNodeToTree(node, wzImage, true, index);
                    MainFrame.getInstance().updateProgress(++count[0], total);
                }

                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0019", count[0]));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.execute();
    }

    private void attachFolder(DefaultMutableTreeNode node, List<File> files) {
        final int[] count = {0};
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                WzFolder targetWzFolder = (WzFolder) node.getUserObject();
                targetWzFolder.getChildren(); // 只是调用一下确保加载了子项
                int total = files.size();
                TreePath treePath = new TreePath(node.getPath());
                boolean expanded = tree.isExpanded(treePath);
                for (File file : files) {
                    WzObject wzObject = targetWzFolder.addFile(file.toPath(), false);
                    if (expanded) {
                        insertNodeToTree(node, wzObject, true);
                    }
                    MainFrame.getInstance().updateProgress(++count[0], total);
                }

                if (!expanded) {
                    tree.expandPath(new TreePath(node.getPath()));
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0019", count[0]));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.execute();
    }

    private void attachWzDir(DefaultMutableTreeNode node, List<File> folders) {
        final int[] count = {0};
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                WzDirectory wzDir = (WzDirectory) node.getUserObject();
                WzFile wzFile = wzDir.getWzFile();
                String keyBoxName = wzFile.getKeyBoxName();
                byte[] iv = wzFile.getIv();
                byte[] key = wzFile.getKey();
                int total = folders.size();
                OverwriteChoice choice = null;
                MainFrame.getInstance().updateProgress(0, total);

                for (File folder : folders) {
                    for (Map.Entry<String, List<Path>> entry : FileTool.getAllFiles(folder.toPath()).entrySet()) {
                        String[] relativize = entry.getKey().split(Pattern.quote(File.separator));
                        WzDirectory curDir = wzDir;
                        for (String r : relativize) {
                            if (curDir.existDirectory(r)) {
                                curDir = curDir.getDirectory(r);
                            } else {
                                WzDirectory newDir = new WzDirectory(r, curDir, wzFile);
                                curDir.addChild(newDir);
                                curDir = newDir;
                            }
                        }

                        List<Path> files = entry.getValue();
                        total += files.size();
                        for (Path file : files) {
                            String filename = file.getFileName().toString();
                            boolean isXml = false;
                            if (filename.endsWith(".xml")) {
                                filename = filename.substring(0, filename.length() - 4);
                                isXml = true;
                            }
                            if (curDir.existImage(filename)) {
                                if (choice == null) choice = OverwriteDialog.show(EditPane.this, filename);

                                if (choice == OverwriteChoice.SKIP_ALL || choice == OverwriteChoice.SKIP) {
                                    if (choice == OverwriteChoice.SKIP) choice = null;

                                    MainFrame.getInstance().updateProgress(++count[0], total);
                                    continue;
                                } else if (choice == OverwriteChoice.OVERWRITE_ALL || choice == OverwriteChoice.OVERWRITE) {
                                    curDir.removeImageChild(filename);

                                    if (choice == OverwriteChoice.OVERWRITE) choice = null;
                                } else if (choice == OverwriteChoice.CANCEL) break;
                            }

                            String filePathStr = file.toString();
                            WzImage wzImage;
                            if (isXml) {
                                WzXmlFile wzXmlFile = new WzXmlFile(filename, filePathStr, keyBoxName, iv, key);
                                if (!wzXmlFile.parse()) {
                                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("test.temp0020", filePathStr));
                                    return null;
                                }
                                wzImage = wzXmlFile.deepClone(curDir);
                            } else {
                                WzImageFile wzImageFile = new WzImageFile(filename, filePathStr, keyBoxName, iv, key);
                                if (!wzImageFile.parse()) {
                                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("test.temp0018", filePathStr));
                                    return null;
                                }
                                wzImage = wzImageFile.deepClone(curDir);
                            }

                            wzImage.setReader(new BinaryReader(wzFile.getWzMutableKey()));
                            wzImage.setChildrenWzImage();
                            wzImage.setTempChanged(true);
                            curDir.addChild(wzImage);
                            MainFrame.getInstance().updateProgress(++count[0], total);
                        }
                        if (choice == OverwriteChoice.CANCEL) break;
                    }

                    MainFrame.getInstance().updateProgress(++count[0], total);
                    if (choice == OverwriteChoice.CANCEL) break;
                }

                node.removeAllChildren();
                treeModel.reload();
                handleTreeDoubleClick(node);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0019", count[0]));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.execute();
    }

    // 删除 -------------------------------------------------------------------------------------------------------------
    public void delete() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject wzObject = (WzObject) node.getUserObject();
            WzObject pWzObject = wzObject.getParent();

            switch (pWzObject) {
                case WzDirectory directory -> {
                    boolean success = false;
                    if (wzObject instanceof WzImage wzImg) {
                        success = directory.removeImageChild(wzImg.getName());
                    } else if (wzObject instanceof WzDirectory wzDir) {
                        success = directory.removeDirectoryChild(wzDir.getName());
                    }

                    if (!success) {
                        log.warn(MainFrame.i18n.get("test.temp0021", pWzObject.getName(), directory.getName()));
                        continue;
                    }
                    removeNodeFromTree((DefaultMutableTreeNode) treePath.getLastPathComponent());
                }
                case WzImage image when image.removeChild(wzObject.getName()) ->
                    removeNodeFromTree((DefaultMutableTreeNode) treePath.getLastPathComponent());
                case WzImageProperty property when property.removeChild(wzObject.getName()) ->
                    removeNodeFromTree((DefaultMutableTreeNode) treePath.getLastPathComponent());
                default -> log.error(MainFrame.i18n.get("test.temp0022", pWzObject.getClass().getSimpleName()));
            }
        }
        resetValueForm();
    }

    // 转移到右侧视图 -----------------------------------------------------------------------------------------------------
    public void move() {
        if (!MainFrame.getInstance().getCenterPane().isRightShowing()) {
            MainFrame.getInstance().getCenterPane().showRightEditPane(true);
        }

        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        EditPane targetPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject wzObject = (WzObject) node.getUserObject();
            targetPane.insertNodeToTree(targetPane.getTreeRoot(), wzObject, true);
            removeNodeFromTree((DefaultMutableTreeNode) treePath.getLastPathComponent());
        }
        resetValueForm();
    }

    // 剪贴板操作 --------------------------------------------------------------------------------------------------------
    public void doCopy() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        Clipboard clipboard = MainFrame.getInstance().getClipboard();
        clipboard.lock();
        clipboard.clear();
        List<String> names = new ArrayList<>();
        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject wzObject = (WzObject) node.getUserObject();
            clipboard.add(wzObject.deepClone(null));
            names.add(wzObject.getName());
        }
        clipboard.unlock();

        // 将复制的节点名称写入到系统剪贴板，以触发一些工具复制音效
        StringSelection selection = new StringSelection(String.join(",", names));
        java.awt.datatransfer.Clipboard systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        systemClipboard.setContents(selection, null);
    }

    private void setPasteWzFileAndReader(List<WzObject> items, WzFile wzFile) {
        for (WzObject item : items) {
            if (item instanceof WzDirectory dir) {
                dir.setWzFile(wzFile);
                setPasteWzFileAndReader(dir.getChildren(), wzFile);
            } else if (item instanceof WzImage img) {
                img.setReader(wzFile.getReader());
                setPasteWzImage(img.getChildren(), img);
            } else {
                MainFrame.getInstance().getClipboard().unlock();
                throw new RuntimeException(MainFrame.i18n.get("test.temp0023", item.getClass().getSimpleName()));
            }
        }
    }

    private void setPasteWzImage(List<? extends WzObject> items, WzImage image) {
        for (WzObject item : items) {
            if (item instanceof WzImageProperty prop) {
                prop.setWzImage(image);
                prop.setChildrenWzImage(image);
            } else {
                MainFrame.getInstance().getClipboard().unlock();
                throw new RuntimeException(MainFrame.i18n.get("test.temp0024", item.getClass().getSimpleName()));
            }
        }
    }

    private boolean isWzObjExistChild(WzObject parent, WzObject child) {
        switch (parent) {
            case WzDirectory pDir when child instanceof WzDirectory -> {
                return pDir.existDirectory(child.getName());
            }
            case WzDirectory pDir when child instanceof WzImage -> {
                return pDir.existImage(child.getName());
            }
            case WzImage image -> {
                return image.existChild(child.getName());
            }
            case WzImageProperty property when property.isListProperty() -> {
                return property.existChild(child.getName());
            }
            default -> {
                MainFrame.getInstance().getClipboard().unlock();
                throw new RuntimeException(MainFrame.i18n.get("test.temp0025", parent.getClass().getSimpleName()));
            }
        }
    }

    private void removeWzObjChild(WzObject parent, WzObject child) {
        switch (parent) {
            case WzDirectory pDir when child instanceof WzDirectory -> pDir.removeDirectoryChild(child.getName());
            case WzDirectory pDir when child instanceof WzImage -> pDir.removeImageChild(child.getName());
            case WzImage image -> image.removeChild(child.getName());
            case WzImageProperty property when property.isListProperty() -> property.removeChild(child.getName());
            default -> {
                MainFrame.getInstance().getClipboard().unlock();
                throw new RuntimeException(MainFrame.i18n.get("test.temp0026", parent.getClass().getSimpleName()));
            }
        }
    }

    private void addWzObjChild(WzObject parent, WzObject child) {
        switch (parent) {
            case WzDirectory pDir when child instanceof WzDirectory cDir -> pDir.addChild(cDir);
            case WzDirectory pDir when child instanceof WzImage cImg -> pDir.addChild(cImg);
            case WzImage image when child instanceof WzImageProperty property -> image.addChild(property);
            case WzImageProperty pProp when pProp.isListProperty() && child instanceof WzImageProperty cProp ->
                pProp.addChild(cProp);
            default -> {
                MainFrame.getInstance().getClipboard().unlock();
                throw new RuntimeException(MainFrame.i18n.get("test.temp0027", parent.getClass().getSimpleName(), child.getClass().getSimpleName()));
            }
        }
    }

    /**
     * 粘贴到全部选中的节点（多选 List 时会把剪贴板内容分别粘贴到每个 List 下，每个目标各拿一份独立的克隆）
     */
    public void doPaste() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        Clipboard clipboard = MainFrame.getInstance().getClipboard();
        clipboard.lock();

        if (clipboard.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0028"));
            clipboard.unlock();
            return;
        }

        OverwriteChoice choice = null;
        int pastedTargets = 0;
        int rejectedTargets = 0;
        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject to = (WzObject) node.getUserObject();

            if (to instanceof WzImage image) {
                image.parse();
            } else if (to instanceof WzDirectory wzDir && wzDir.isWzFile()) {
                wzDir.getWzFile().parse();
            }

            // 目标类型不匹配时跳过该目标，继续粘贴其余目标
            if (!clipboard.canPaste(to)) {
                rejectedTargets++;
                continue;
            }

            List<WzObject> cpItems = clipboard.getItems(); // 每个目标一份独立克隆
            cpItems.forEach(cpItem -> cpItem.setParent(to)); // 复制的时候顶级对象没有 parent

            // 这里的对象类型和canPaste保持一致
            if (to instanceof WzDirectory wzDirectory) {
                setPasteWzFileAndReader(cpItems, wzDirectory.getWzFile());
            } else if (to instanceof WzImage wzImg) {
                setPasteWzImage(cpItems, wzImg);
            } else if (to instanceof WzImageProperty wzProp && wzProp.isListProperty()) {
                setPasteWzImage(cpItems, wzProp.getWzImage());
            }

            for (WzObject item : cpItems) {
                item.setTempChanged(true);
                int index = -1; // -1 = 追加到末尾，和数据模型的顺序保持一致
                if (isWzObjExistChild(to, item)) { // 发现重名
                    if (choice == OverwriteChoice.SKIP_ALL) continue;
                    else if (choice == OverwriteChoice.OVERWRITE_ALL) {
                        index = removeExistChild(node, to, item);
                    } else {
                        choice = OverwriteDialog.show(this, item.getName());
                        switch (choice) {
                            case OVERWRITE, OVERWRITE_ALL -> index = removeExistChild(node, to, item);
                            case SKIP, SKIP_ALL, CANCEL -> {
                                continue;
                            }
                        }
                    }
                }
                addWzObjChild(to, item); // 已经设置 changed 了

                if (!node.isLeaf()) {
                    insertNodeToTree(node, item, false, index);
                }
            }
            pastedTargets++;
        }

        clipboard.unlock();
        resetValueForm();
        tree.repaint(); // 刷新节点上的子节点数量

        if (rejectedTargets > 0) {
            MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("paste.done_with_reject", pastedTargets, rejectedTargets));
        } else {
            MainFrame.getInstance().setStatusText(MainFrame.i18n.get("paste.done", pastedTargets));
        }
    }

    /**
     * 覆盖粘贴前移除同名的旧节点
     *
     * @return 旧节点在树里的位置（树上还没展开出该节点时返回 -1，表示追加）
     */
    private int removeExistChild(DefaultMutableTreeNode node, WzObject to, WzObject item) {
        removeWzObjChild(to, item);

        DefaultMutableTreeNode childNode = findTreeNodeByName(node, item.getName());
        if (childNode == null) return -1; // 目标节点在树上未展开，没有对应的树节点

        int index = node.getIndex(childNode);
        removeNodeFromTree(childNode);
        return index;
    }

    // 新建文件 ----------------------------------------------------------------------------------------------------------
    public void createWz() {
        CreateFileDialog dialog = new CreateFileDialog(this, true);
        CreateFileData data = dialog.getData();
        if (data == null) return;

        String name = data.getName();
        if (!name.endsWith(".wz")) name = name + ".wz";
        short fileVer = data.getVersion();
        WzKey wzKey = (WzKey) MainFrame.getInstance().getKeyBox().getSelectedItem();
        if (wzKey == null) return;

        WzFile wzFile = WzFile.createNewFile(name, fileVer, wzKey.getName(), wzKey.getIv(), wzKey.getUserKey());
        wzFile.setNewFile(true);
        wzFile.getWzDirectory().setTempChanged(true);
        insertNodeToTree(treeRoot, wzFile.getWzDirectory(), true);
    }

    public void createImg() {
        CreateFileDialog dialog = new CreateFileDialog(this, false);
        CreateFileData data = dialog.getData();
        if (data == null) return;

        String name = data.getName();
        if (!name.endsWith(".img")) name = name + ".img";
        WzKey wzKey = (WzKey) MainFrame.getInstance().getKeyBox().getSelectedItem();
        if (wzKey == null) return;

        WzImageFile wzImageFile = new WzImageFile(name, name, wzKey.getName(), wzKey.getIv(), wzKey.getUserKey());
        wzImageFile.setReader(new BinaryReader(wzImageFile.getIv(), wzImageFile.getKey()));
        wzImageFile.setNewFile(true);
        wzImageFile.setStatus(WzFileStatus.PARSE_SUCCESS);
        wzImageFile.setChanged(true);
        wzImageFile.setTempChanged(true);
        insertNodeToTree(treeRoot, wzImageFile, true);
    }

    // String汉化 -------------------------------------------------------------------------------------------------------
    public void localizeString() {
        Instant start = Instant.now();
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject to = getLocalizeTo(node);
            WzObject from = getLocalizeFrom(to);
            ChineseUtil.chinese(from, to);
        }

        Instant end = Instant.now();
        Duration duration = Duration.between(start, end);
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.localize_finish", duration.toMillis()));
    }

    private WzObject getLocalizeTo(DefaultMutableTreeNode node) {
        WzObject wzObject = (WzObject) node.getUserObject();
        switch (wzObject) {
            case WzDirectory wzDirectory when wzDirectory.isWzFile() -> {
                WzFile wzFile = wzDirectory.getWzFile();
                if (wzFile.getName().equalsIgnoreCase("List.wz")) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.list_file"));
                    throw new RuntimeException();
                }
                if (!wzFile.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzFile.getName(), wzFile.getStatus().getMessage()));
                    throw new RuntimeException();
                }
                return wzFile;
            }
            case WzImage wzImage -> {
                if (!wzImage.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                    throw new RuntimeException();
                }
                return wzImage;
            }
            case WzImageProperty prop -> {
                return prop;
            }
            default -> {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
                throw new RuntimeException();
            }
        }
    }

    private WzObject getLocalizeFrom(WzObject to) {
        switch (to) {
            case WzFile wzFile -> {
                DefaultMutableTreeNode rightTreeRoot = MainFrame.getInstance().getCenterPane().getAnotherPane(this).getTreeRoot();
                DefaultMutableTreeNode rightNode = MainFrame.getInstance().getCenterPane().getAnotherPane(this).findTreeNodeByName(rightTreeRoot, wzFile.getName());
                if (rightNode == null) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.localize_find", wzFile.getName()));
                    throw new RuntimeException();
                }
                WzFile from = ((WzDirectory) rightNode.getUserObject()).getWzFile();
                if (!from.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.localize_parse", from.getName(), from.getStatus().getMessage()));
                    throw new RuntimeException();
                }
                return from;
            }
            case WzImage wzImage -> {
                WzImage from = (WzImage) MainFrame.getInstance().getCenterPane().getAnotherPane(this).findWzObjectInTreeByPath(wzImage.getPath());
                if (from == null) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.localize_find", wzImage.getName()));
                    throw new RuntimeException();
                }
                if (!from.parse()) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.localize_parse", from.getName(), from.getStatus().getMessage()));
                    throw new RuntimeException();
                }
                return from;
            }
            case WzImageProperty prop -> {
                WzImageProperty from = (WzImageProperty) MainFrame.getInstance().getCenterPane().getAnotherPane(this).findWzObjectInTreeByPath(prop.getPath());
                if (from == null) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.localize_find", prop.getName()));
                    throw new RuntimeException();
                }
                return from;
            }
            default -> {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", to.getClass().getSimpleName()));
                throw new RuntimeException();
            }
        }
    }

    // 图片汉化 ----------------------------------------------------------------------------------------------------------
    public void compareImg() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    ChineseUtil.initChineseImg();
                    for (TreePath treePath : selectedPaths) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                        WzObject to = (WzObject) node.getUserObject();

                        WzObject from = MainFrame.getInstance().getCenterPane().getAnotherPane(EditPane.this).findWzObjectInTreeByPath(to.getPath());
                        if (from == null) {
                            log.error(MainFrame.i18n.get("test.temp0030", to.getName()));
                            continue;
                        }

                        ChineseUtil.chineseImg(from, to);
                    }

                    ChineseUtil.completeChineseImg();
                    return null;
                } catch (Exception e) {
                    log.error(e.getMessage());
                    return null;
                }
            }
        }.execute();
    }

    // 视图对比 ----------------------------------------------------------------------------------------------------------
    /** 自动展开差异路径的数量上限，超出部分通过结果对话框双击跳转 */
    private static final int MAX_DIFF_REVEAL = 500;
    /** 占位节点的数量上限（独立于展开上限，缺失提示不能因为值差异太多而被挤掉） */
    private static final int MAX_DIFF_GHOSTS = 2000;

    /**
     * 将选中节点与对侧视图的同路径节点递归对比，差异节点在两侧树里着色标注，并弹出结果列表
     */
    public void diffWithOtherPane() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        clearDiffMarks();
        otherPane.clearDiffMarks();

        new SwingWorker<WzDiffUtil.DiffContext, Void>() {
            @Override
            protected WzDiffUtil.DiffContext doInBackground() {
                WzDiffUtil.DiffContext ctx = new WzDiffUtil.DiffContext(diffMarks, otherPane.getDiffMarks());
                try {
                    for (TreePath treePath : selectedPaths) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                        WzObject thisObj = (WzObject) node.getUserObject();
                        String thisPath = anchoredNodePath(node);

                        Pair<WzObject, String> other = resolveOtherSide(otherPane, node);
                        if (other == null) {
                            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("diff.error.no_target", thisPath));
                            ctx.addFailure(MainFrame.i18n.get("diff.error.no_target", thisPath));
                            continue;
                        }

                        bindDiffRoots(otherPane, thisPath, other.getRight());
                        WzDiffUtil.diffRoot(thisObj, other.getLeft(), thisPath, other.getRight(), ctx);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage());
                    ctx.addFailure(e.getMessage());
                }
                return ctx;
            }

            @Override
            protected void done() {
                WzDiffUtil.DiffContext ctx;
                try {
                    ctx = get();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
                finishDiff(ctx, otherPane);
            }
        }.execute();
    }

    /**
     * 与对侧视图当前选中的节点对比：不要求两侧文件名/路径一致，对比时把这两个节点绑定为对应关系，
     * 后续的定位、表单视图值、替换都按此绑定映射
     */
    public void diffWithOtherPaneSelection() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);
        TreePath otherSelected = otherPane.getTree().getSelectionPath();
        if (otherSelected == null) {
            MainFrame.getInstance().setStatusTextWithWarnLog(MainFrame.i18n.get("diff.error.no_selection"));
            return;
        }

        DefaultMutableTreeNode thisNode = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
        DefaultMutableTreeNode otherNode = (DefaultMutableTreeNode) otherSelected.getLastPathComponent();
        WzObject thisObj = (WzObject) thisNode.getUserObject();
        WzObject otherObj = (WzObject) otherNode.getUserObject();
        String thisPath = anchoredNodePath(thisNode);
        String otherPath = otherPane.anchoredNodePath(otherNode);

        clearDiffMarks();
        otherPane.clearDiffMarks();
        bindDiffRoots(otherPane, thisPath, otherPath);

        new SwingWorker<WzDiffUtil.DiffContext, Void>() {
            @Override
            protected WzDiffUtil.DiffContext doInBackground() {
                WzDiffUtil.DiffContext ctx = new WzDiffUtil.DiffContext(diffMarks, otherPane.getDiffMarks());
                try {
                    WzDiffUtil.diffRoot(thisObj, otherObj, thisPath, otherPath, ctx);
                } catch (Exception e) {
                    log.error(e.getMessage());
                    ctx.addFailure(e.getMessage());
                }
                return ctx;
            }

            @Override
            protected void done() {
                WzDiffUtil.DiffContext ctx;
                try {
                    ctx = get();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
                finishDiff(ctx, otherPane);
            }
        }.execute();
    }

    /**
     * 把两侧的对比根路径绑定为对应关系（双向）
     */
    private void bindDiffRoots(EditPane otherPane, String thisPath, String otherPath) {
        diffBindings.put(thisPath, otherPath);
        otherPane.diffBindings.put(otherPath, thisPath);
    }

    /**
     * 对比完成后的公共收尾：展开差异路径、刷新着色、状态栏汇总、弹出结果列表
     */
    private void finishDiff(WzDiffUtil.DiffContext ctx, EditPane otherPane) {
        List<DiffResult> results = ctx.getResults();
        int revealed = 0;
        int ghosts = 0;
        for (DiffResult result : results) {
            boolean withinReveal = revealed < MAX_DIFF_REVEAL;
            if (withinReveal) {
                revealed++;
                if (result.thisPath() != null) revealDiffPath(result.thisPath());
                if (result.otherPath() != null) otherPane.revealDiffPath(result.otherPath());
            }
            // 缺失的一侧插入半透明占位节点：MISSING 缺在本侧，ADDED 缺在对侧。
            // 占位节点走独立上限，超出展开上限的只插入不强制展开
            if (result.ghostPath() != null && ghosts < MAX_DIFF_GHOSTS) {
                ghosts++;
                if (result.kind() == DiffKind.MISSING) {
                    insertGhostNode(result.ghostPath(), DiffKind.MISSING, withinReveal);
                } else if (result.kind() == DiffKind.ADDED) {
                    otherPane.insertGhostNode(result.ghostPath(), DiffKind.ADDED, withinReveal);
                }
            }
        }

        tree.repaint();
        otherPane.getTree().repaint();

        if (ctx.hasFailures()) {
            // 有任务失败时结果不完整，不能报告"对比完成"或"两侧一致"
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("diff.status.failed", ctx.getFailures().size(), results.size()));
            ctx.getFailures().forEach(failure -> log.error("对比失败: {}", failure));
        } else if (results.isEmpty()) {
            MainFrame.getInstance().setStatusText(MainFrame.i18n.get("diff.status.same"));
        } else {
            MainFrame.getInstance().setStatusText(MainFrame.i18n.get("diff.status.done", results.size()));
        }

        new CompareResultDialog(MainFrame.getInstance(), MainFrame.i18n.get("diff.dialog.title", results.size()),
                results, this, otherPane).setVisible(true);
    }

    /**
     * 清除视图对比的差异标记、节点绑定和占位节点
     */
    public void clearDiffMarks() {
        diffMarks.clear();
        diffBindings.clear();
        for (DefaultMutableTreeNode ghostNode : ghostNodes) {
            if (ghostNode.getParent() != null) {
                treeModel.removeNodeFromParent(ghostNode);
            }
        }
        ghostNodes.clear();
        tree.repaint();
    }

    /**
     * 在 path 位置插入一个半透明占位节点，提示"该节点对侧存在、本侧缺失"。不挂进数据模型，只影响树显示。
     *
     * @param show 是否把占位节点展开到可见（false 时只插入，等用户自己展开）
     */
    private void insertGhostNode(String path, DiffKind kind, boolean show) {
        int slash = path.lastIndexOf('/');
        if (slash <= 0) return; // 顶层节点缺失不做占位

        DefaultMutableTreeNode parentNode = materializeDiffPath(path.substring(0, slash));
        if (parentNode == null) return;

        String name = path.substring(slash + 1);
        if (findTreeNodeByName(parentNode, name) != null) return;

        DiffGhostObject ghost = new DiffGhostObject(name);
        diffMarks.put(ghost, kind);

        DefaultMutableTreeNode ghostNode = new DefaultMutableTreeNode(ghost, false);
        treeModel.insertNodeInto(ghostNode, parentNode, parentNode.getChildCount());
        ghostNodes.add(ghostNode);
        if (show) {
            tree.makeVisible(new TreePath(ghostNode.getPath()));
        }
    }

    /**
     * 在对侧视图里定位到与选中节点同路径的文件/节点
     */
    public void locateInOtherPane() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
        EditPane otherPane = MainFrame.getInstance().getCenterPane().getAnotherPane(this);

        new SwingWorker<Pair<WzObject, String>, Void>() {
            @Override
            protected Pair<WzObject, String> doInBackground() {
                return resolveOtherSide(otherPane, node);
            }

            @Override
            protected void done() {
                Pair<WzObject, String> other;
                try {
                    other = get();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }

                if (other == null) {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("diff.error.no_target", anchoredNodePath(node)));
                    return;
                }

                if (!MainFrame.getInstance().getCenterPane().isRightShowing()) {
                    MainFrame.getInstance().getCenterPane().showRightEditPane(true);
                }
                otherPane.focusNodeByPath(other.getRight());
            }
        }.execute();
    }

    // 全部展开 / 全部收起 -------------------------------------------------------------------------------------------------
    /**
     * 递归展开选中节点的全部子孙（未解析的先解析）
     */
    public void expandAllFromSelection() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject wzObject = (WzObject) node.getUserObject();
            MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.loading", wzObject.getName()));

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    expandTreeNode(node, true, true, false);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    expandAllNodes(node);
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.loaded", wzObject.getName()));
                }
            }.execute();
        }
    }

    private void expandAllNodes(DefaultMutableTreeNode node) {
        if (node.isLeaf()) {
            expandTreeNode(node, true, true, false); // 子孙里可能还有未补插到树上的层级
        }
        if (node.isLeaf()) return;

        tree.expandPath(new TreePath(node.getPath()));
        for (int i = 0; i < node.getChildCount(); i++) {
            expandAllNodes((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    /**
     * 递归收起选中节点的全部子孙
     */
    public void collapseAllFromSelection() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        for (TreePath treePath : selectedPaths) {
            collapseAll(treePath);
        }
    }

    /**
     * 只展开选中节点子树里带差异标记的分支（支持多选）。无标记的子树保持原样，数量可控不会卡
     */
    public void expandDiffFromSelection() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        for (TreePath treePath : selectedPaths) {
            expandDiffNodes((DefaultMutableTreeNode) treePath.getLastPathComponent());
        }
    }

    private void expandDiffNodes(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof WzObject obj)) return;
        if (!diffMarks.containsKey(obj)) return; // 子树无差异，不展开

        if (node.isLeaf()) {
            expandTreeNode(node, true, true, false); // 补插已解析的子节点
        }
        if (node.isLeaf()) return;

        tree.expandPath(new TreePath(node.getPath()));
        for (int i = 0; i < node.getChildCount(); i++) {
            expandDiffNodes((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    /**
     * 阶梯修改 int：输入节点名、起始值、结束值，按树顺序把等分后的值依次写入各选中节点子树里的同名 int 节点
     */
    public void stepChangeIntNodeValue() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        StepIntDialog dialog = new StepIntDialog(MainFrame.i18n.get("tree.menu.step_int"), this);
        StepIntFormData data = dialog.getData();
        if (data == null) return;

        // 按树里的显示顺序分配，而不是点选顺序
        TreePath[] ordered = selectedPaths.clone();
        java.util.Arrays.sort(ordered, java.util.Comparator.comparingInt(tree::getRowForPath));

        int count = ordered.length;
        for (int i = 0; i < count; i++) {
            long value = count == 1
                    ? data.getStart()
                    : data.getStart() + Math.round((data.getEnd() - (long) data.getStart()) * i / (double) (count - 1));
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) ordered[i].getLastPathComponent();
            WzObject root = (WzObject) node.getUserObject();
            WzNodeUtil.changeIntNodeValue(root, data.getName(), (int) value);
        }

        tree.repaint();
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
    }

    /**
     * 把 path 对应的节点在树里补齐并展开到可见（不选中、不滚动），用于对比后批量展示差异位置。
     * 节点对象已在对比时解析过，这里只做树节点的补插与展开。
     */
    private DefaultMutableTreeNode revealDiffPath(String path) {
        DefaultMutableTreeNode node = materializeDiffPath(path);
        if (node != null) {
            tree.makeVisible(new TreePath(node.getPath()));
        }
        return node;
    }

    /**
     * 只把 path 对应的树节点补插出来（不展开、不滚动）
     */
    private DefaultMutableTreeNode materializeDiffPath(String path) {
        DefaultMutableTreeNode node = treeRoot;
        for (String segment : path.split("/")) {
            DefaultMutableTreeNode next = findTreeNodeByName(node, segment);
            if (next == null && node.isLeaf() && node != treeRoot) {
                expandTreeNode(node, true, true, false);
                next = findTreeNodeByName(node, segment);
            }
            if (next == null) return null;
            node = next;
        }
        return node;
    }

    /**
     * 树锚定的完整路径：从树 Root 的直接子节点到 node 的名称链。
     * <p>
     * 与 {@code WzObject.getPath()} 不同：文件夹加载时每个 wz/img 的 getPath() 是独立路径空间（不含文件夹前缀），
     * 这里按树的实际层级拼出完整路径。
     */
    private String anchoredNodePath(DefaultMutableTreeNode node) {
        StringBuilder sb = new StringBuilder();
        for (Object item : node.getUserObjectPath()) {
            if (!(item instanceof WzObject obj)) continue; // 跳过 "root"
            if (!sb.isEmpty()) sb.append("/");
            sb.append(obj.getName());
        }
        return sb.toString();
    }

    /**
     * 在对侧面板解析与本侧选中节点对应的对象。
     * <p>
     * 两侧的文件夹层级可能不同（一侧直接加载 wz，另一侧从文件夹加载），因此：
     * 先按完整树路径找；找不到时逐层剥掉本侧的文件夹前缀再找；
     * 仍找不到则在对侧的文件夹树里搜出包含根段的文件夹，补上对侧前缀找。
     *
     * @return 对象与其在对侧的树锚定路径，找不到返回 null
     */
    private Pair<WzObject, String> resolveOtherSide(EditPane otherPane, DefaultMutableTreeNode node) {
        // 绑定优先：节点落在某个已绑定的对比根下时，按绑定映射出对侧路径（两侧根路径可以完全不同）
        String thisPath = anchoredNodePath(node);
        String bindKey = null;
        for (String key : diffBindings.keySet()) {
            if ((thisPath.equals(key) || thisPath.startsWith(key + "/"))
                    && (bindKey == null || key.length() > bindKey.length())) {
                bindKey = key;
            }
        }
        if (bindKey != null) {
            String otherPath = diffBindings.get(bindKey) + thisPath.substring(bindKey.length());
            WzObject obj = otherPane.findWzObjectByModelPath(otherPath);
            if (obj != null) {
                return new Pair<>(obj, otherPath);
            }
        }

        List<WzObject> chain = new ArrayList<>();
        for (Object item : node.getUserObjectPath()) {
            if (item instanceof WzObject obj) chain.add(obj); // 跳过 "root"
        }

        List<String> segments = new ArrayList<>();
        chain.forEach(obj -> segments.add(obj.getName()));

        // 可剥离的文件夹前缀层数：链上开头连续的文件夹，选中节点本身不算
        int folderPrefixCount = 0;
        while (folderPrefixCount < chain.size() - 1 && chain.get(folderPrefixCount) instanceof WzFolder) {
            folderPrefixCount++;
        }

        for (int strip = 0; strip <= folderPrefixCount; strip++) {
            String candidate = String.join("/", segments.subList(strip, segments.size()));
            WzObject obj = otherPane.findWzObjectByModelPath(candidate);
            if (obj != null) {
                return new Pair<>(obj, candidate);
            }
        }

        // 对侧比本侧多了文件夹层级：在对侧的文件夹里递归找根段所在的文件夹
        String candidate = String.join("/", segments.subList(folderPrefixCount, segments.size()));
        String prefix = otherPane.findFolderPrefixContaining(segments.get(folderPrefixCount));
        if (prefix != null) {
            String full = prefix + "/" + candidate;
            WzObject obj = otherPane.findWzObjectByModelPath(full);
            if (obj != null) {
                return new Pair<>(obj, full);
            }
        }
        return null;
    }

    /**
     * 在本面板已加载的文件夹树里递归查找直接包含 name 子项的文件夹，返回其树锚定路径
     */
    private String findFolderPrefixContaining(String name) {
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            if (((DefaultMutableTreeNode) treeRoot.getChildAt(i)).getUserObject() instanceof WzFolder folder) {
                String result = searchFolderPrefix(folder, folder.getName(), name);
                if (result != null) return result;
            }
        }
        return null;
    }

    private String searchFolderPrefix(WzFolder folder, String prefix, String name) {
        for (WzObject child : folder.getChildren()) {
            if (child.getName().equals(name)) return prefix;
        }
        for (WzObject child : folder.getChildren()) {
            if (child instanceof WzFolder sub) {
                String result = searchFolderPrefix(sub, prefix + "/" + sub.getName(), name);
                if (result != null) return result;
            }
        }
        return null;
    }

    // 批量修改图片格式 ---------------------------------------------------------------------------------------------------
    public void changeCavFmt() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        ChangeCavFmtDialog dialog = new ChangeCavFmtDialog(this);
        CanvasFormData data = dialog.getData();
        if (data == null) return;
        WzPngFormat format = data.getFormat();

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                List<WzImageProperty> properties = new ArrayList<>();
                for (TreePath treePath : selectedPaths) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    WzImageProperty prop = (WzImageProperty) node.getUserObject();
                    properties.add(prop);
                }
                CanvasUtil.changeFormat(properties, format);
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
                return null;
            }
        }.execute();
    }

    // 批量修改节点名称/值 -------------------------------------------------------------------------------------------------
    public void changeNodeName() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        ChangeNodeNameDialog dialog = new ChangeNodeNameDialog(this);
        ChangeNodeNameFormData data = dialog.getData();
        if (data == null) return;
        String oldName = data.getOldName();
        String newName = data.getNewName();
        int degree = data.getDegree();
        if (degree < 1) return;

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject root = (WzObject) node.getUserObject();
            WzNodeUtil.changeNodeName(root, oldName, newName, degree);
        }
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
    }

    public void changeIntNodeValue() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        IntDialog dialog = new IntDialog(MainFrame.i18n.get("test.temp0031"), this);
        IntFormData data = dialog.getData();
        if (data == null) return;
        String nodeName = data.getName();
        int value = data.getValue();

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject root = (WzObject) node.getUserObject();
            WzNodeUtil.changeIntNodeValue(root, nodeName, value);
        }
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
    }

    public void rawToIcon() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject root = (WzObject) node.getUserObject();
            WzNodeUtil.rawToIcon(root);
        }
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
    }

    public void changeOriginValue() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        VectorDialog dialog = new VectorDialog(MainFrame.i18n.get("test.temp0032"), this);
        VectorFormData data = dialog.getData();
        if (data == null) return;
        String nodeName = data.getName();
        int x = data.getX();
        int y = data.getY();

        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject root = (WzObject) node.getUserObject();
            WzNodeUtil.changeOriginValue(root, nodeName, x, y);
        }
        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
    }

    // 图片嗅探 ----------------------------------------------------------------------------------------------------------
    public void imageFinder() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();
        WzObject wzObject = (WzObject) node.getUserObject();
        List<? extends WzObject> children;
        List<CanvasUtilData> data = new ArrayList<>();

        switch (wzObject) {
            case WzDirectory wzDir -> {
                if (wzDir.getWzFile().parse()) {
                    children = wzDir.getChildren();
                } else {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzDir.getName(), wzDir.getWzFile().getStatus().getMessage()));
                    return;
                }
            }
            case WzImage wzImage -> {
                if (wzImage.parse()) {
                    children = wzImage.getChildren();
                } else {
                    MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                    return;
                }
            }
            case WzImageProperty prop when prop.isListProperty() -> children = prop.getChildren();
            default -> {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
                return;
            }
        }

        CanvasUtil.search(data, children);

        CanvasWall canvasWall = new CanvasWall(data, wzObject.getPath(), node, this);
        canvasWall.setVisible(true);
    }

    // 批量缩放图片 ------------------------------------------------------------------------------------------------------
    public void scaleImage() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        ScaleDialog dialog = new ScaleDialog(this);
        DoubleFormData data = dialog.getData();
        if (data == null) return;
        double scale = data.getValue();
        if (scale == 1.0) return;

        String name = data.getName();

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                List<WzImageProperty> properties = new ArrayList<>();
                for (TreePath treePath : selectedPaths) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    WzObject prop = (WzObject) node.getUserObject();
                    if (prop instanceof WzImageProperty listProp) {
                        properties.add(listProp);
                    } else if (prop instanceof WzImage img) {
                        img.parse();
                        properties.addAll(img.getChildren());
                    }
                }
                CanvasUtil.scaleImage(properties, name, scale);
                MainFrame.getInstance().setStatusText(MainFrame.i18n.get("status.done"));
                return null;
            }
        }.execute();
    }

    // 批量删除节点 ------------------------------------------------------------------------------------------------------
    public void removeAllWzChildWithName() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        NodeDialog dialog = new NodeDialog(MainFrame.i18n.get("test.temp0034"), MainFrame.i18n.get("test.temp0033"), this);
        NodeFormData data = dialog.getData();
        if (data == null) return;

        String name = data.getName();
        int count = 0;
        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            WzObject wzObj = (WzObject) node.getUserObject();
            int current = 0;
            if (wzObj instanceof WzImage image) {
                if (!image.parse()) {
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0035", image.getName(), count));
                    return;
                }
                current = image.removeAllChildWithName(name);
            } else if (wzObj instanceof WzImageProperty prop) {
                current = prop.removeAllChildWithName(name);
            }

            if (current > 0) {
                DefaultMutableTreeNode pNode = (DefaultMutableTreeNode) node.getParent();
                int index = pNode.getIndex(node);
                removeNodeFromTree(node);
                insertNodeToTree(pNode, wzObj, true, index);
                count += current;
            }
        }

        MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0036", count));
    }

    // 批量删除非现金装备 --------------------------------------------------------------------------------------------------
    public void removeNonCashEqp() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        int cashCount = 0;
        int delCount = 0;
        int notfoundCount = 0;
        int size = 0;

        for (TreePath path : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            WzDirectory wzDir = (WzDirectory) node.getUserObject();
            List<WzImage> images = wzDir.getImages();
            size += images.size();
            for (WzImage image : images) {
                image.parse();
                int cashValue = 0;
                WzImageProperty info = image.getChild("info");

                if (info != null) {
                    WzImageProperty cash = info.getChild("cash");

                    switch (cash) {
                        case WzIntProperty c -> cashValue = c.getValue();
                        case WzStringProperty c -> cashValue = Integer.parseInt(c.getValue());
                        case null -> log.warn(MainFrame.i18n.get("test.temp0037", info.getPath()));
                        default -> log.warn(MainFrame.i18n.get("test.temp0038", info.getPath()));
                    }
                } else {
                    log.warn(MainFrame.i18n.get("test.temp0039", image.getPath()));
                    notfoundCount++;
                }

                if (cashValue == 0) {
                    wzDir.removeImageChild(image.getName());
                    delCount++;
                    continue;
                }

                cashCount++;
            }

            node.removeAllChildren();
            tree.repaint();
            handleTreeDoubleClick(node);
        }

        log.info(MainFrame.i18n.get("test.temp0040", size, cashCount, delCount, notfoundCount));
    }

    // Outlink ---------------------------------------------------------------------------------------------------------
    public void outlink() {
        Instant now = Instant.now();
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (selectedPaths == null) return;

        List<WzObject> objects = new ArrayList<>();
        for (TreePath treePath : selectedPaths) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
            WzObject wzObject = (WzObject) node.getUserObject();
            objects.add(wzObject);
        }

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                Outlink.replace(objects);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    Instant end = Instant.now();
                    MainFrame.getInstance().setStatusText(MainFrame.i18n.get("test.temp0041", Duration.between(now, end).toSeconds()));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        worker.execute();
    }

    // 创建子节点 --------------------------------------------------------------------------------------------------------
    public void addWzDirectory() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        NodeDialog nodeDialog = new NodeDialog(MainFrame.i18n.get("test.temp0043"), this);
        NodeFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzDirectory wzDirectory = (WzDirectory) node.getUserObject();
        WzFile wzFile = wzDirectory.getWzFile();
        if (!wzFile.parse()) {
            MainFrame.getInstance().setStatusText(MainFrame.i18n.get("error.parse", wzFile.getName(), wzFile.getStatus().getMessage()));
            throw new RuntimeException();
        }

        WzDirectory newDir = new WzDirectory(name, wzDirectory, wzFile);
        if (!wzDirectory.addChild(newDir)) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
            return;
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        newDir.setTempChanged(true);
        insertNodeToTree(node, newDir, true, 0);
    }

    public void addWzImage() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        NodeDialog nodeDialog = new NodeDialog(MainFrame.i18n.get("test.temp0044"), this);
        NodeFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        if (!name.endsWith(".img")) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0042"));
            return;
        }

        WzDirectory wzDirectory = (WzDirectory) node.getUserObject();
        WzFile wzFile = wzDirectory.getWzFile();
        if (!wzFile.parse()) {
            MainFrame.getInstance().setStatusText(MainFrame.i18n.get("error.parse", wzFile.getName(), wzFile.getStatus().getMessage()));
            throw new RuntimeException();
        }

        WzImage newImg = new WzImage(name, wzDirectory, wzFile.getReader());
        if (!wzDirectory.addChild(newImg)) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
            return;
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        newImg.setTempChanged(true);
        insertNodeToTree(node, newImg, true, 0);
    }

    public void addCanvas() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        CanvasDialog nodeDialog = new CanvasDialog(MainFrame.i18n.get("test.temp0045"), this);
        CanvasFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzCanvasProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzCanvasProperty(name, wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzCanvasProperty(name, wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        subProp.initPngProperty(name, subProp, imageRoot);
        subProp.setPng(data.getValue(), data.getFormat(), data.getScale());

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addConvex() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        NodeDialog nodeDialog = new NodeDialog(MainFrame.i18n.get("test.temp0046"), this);
        NodeFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzConvexProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzConvexProperty(name, wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzConvexProperty(name, wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addDouble() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        DoubleDialog nodeDialog = new DoubleDialog(MainFrame.i18n.get("test.temp0047"), this);
        DoubleFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzDoubleProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzDoubleProperty(name, data.getValue(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzDoubleProperty(name, data.getValue(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addFloat() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        FloatDialog nodeDialog = new FloatDialog(MainFrame.i18n.get("test.temp0048"), this);
        FloatFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzFloatProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzFloatProperty(name, data.getValue(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzFloatProperty(name, data.getValue(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addInt() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        IntDialog nodeDialog = new IntDialog(MainFrame.i18n.get("test.temp0049"), this);
        IntFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzIntProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzIntProperty(name, data.getValue(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzIntProperty(name, data.getValue(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addList() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        NodeDialog nodeDialog = new NodeDialog(MainFrame.i18n.get("test.temp0050"), this);
        NodeFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzListProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzListProperty(name, wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzListProperty(name, wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addLong() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        LongDialog nodeDialog = new LongDialog(MainFrame.i18n.get("test.temp0051"), this);
        LongFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzLongProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzLongProperty(name, data.getValue(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzLongProperty(name, data.getValue(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addNull() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        NodeDialog nodeDialog = new NodeDialog(MainFrame.i18n.get("test.temp0052"), this);
        NodeFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzNullProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzNullProperty(name, wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzNullProperty(name, wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addShort() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        ShortDialog nodeDialog = new ShortDialog(MainFrame.i18n.get("test.temp0053"), this);
        ShortFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzShortProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzShortProperty(name, data.getValue(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzShortProperty(name, data.getValue(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addSound() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        SoundDialog nodeDialog = new SoundDialog(MainFrame.i18n.get("test.temp0054"), this);
        SoundFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzSoundProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzSoundProperty(name, wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzSoundProperty(name, wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        subProp.setSound(data.getSoundBytes());

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addString() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        StringDialog nodeDialog = new StringDialog(MainFrame.i18n.get("test.temp0055"), this);
        StringFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzStringProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzStringProperty(name, data.getValue(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzStringProperty(name, data.getValue(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addUOL() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        StringDialog nodeDialog = new StringDialog(MainFrame.i18n.get("test.temp0056"), this);
        StringFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzUOLProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzUOLProperty(name, data.getValue(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzUOLProperty(name, data.getValue(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }

    public void addVector() {
        TreePath[] selectedPaths = tree.getSelectionPaths();
        if (TreePathUtil.isNullOrMultiple(selectedPaths)) return;

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectedPaths[0].getLastPathComponent();

        VectorDialog nodeDialog = new VectorDialog(MainFrame.i18n.get("test.temp0057"), this);
        VectorFormData data = nodeDialog.getData();

        if (data == null) return;

        String name = data.getName();

        if (name.isEmpty()) {
            JMessageUtil.error(MainFrame.i18n.get("test.temp0014"));
            return;
        }

        WzObject wzObject = (WzObject) node.getUserObject();
        WzImage imageRoot;
        WzVectorProperty subProp;
        if (wzObject instanceof WzImage wzImage) {
            if (!wzImage.parse()) {
                MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.parse", wzImage.getName(), wzImage.getStatus().getMessage()));
                throw new RuntimeException();
            }
            imageRoot = wzImage;
            subProp = new WzVectorProperty(name, data.getX(), data.getY(), wzObject, imageRoot);
            if (!wzImage.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else if (wzObject instanceof WzImageProperty prop) {
            imageRoot = prop.getWzImage();
            subProp = new WzVectorProperty(name, data.getX(), data.getY(), wzObject, imageRoot);
            if (!prop.addChild(subProp)) {
                JMessageUtil.error(MainFrame.i18n.get("test.temp0015"));
                return;
            }
        } else {
            MainFrame.getInstance().setStatusTextWithErrLog(MainFrame.i18n.get("error.not_supported_type", wzObject.getClass().getSimpleName()));
            throw new RuntimeException();
        }

        if (node.isLeaf()) return; // isLeaf 说明未加载数据，就不要插入了
        subProp.setTempChanged(true);
        insertNodeToTree(node, subProp, true, 0);
    }
}
