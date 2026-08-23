# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

OrzRepacker：MapleStory WZ 资源的解包 / 打包 / 编辑工具。Spring Boot 4 + Java 21，界面是 Swing（FlatLaf 主题），Spring 只承担依赖注入、配置和 MCP 服务，**不是 Web 应用**（默认 `spring.main.web-application-type=none`）。

## 常用命令

```bash
mvn compile                      # 编译
mvn test                         # 跑测试（只有一个 contextLoads 冒烟测试）
mvn -Dtest=OrangeWzApplicationTests test   # 跑单个测试
```

完整 `mvn package` 会串起 classfinal 加密 → 复制整个本地 JRE 到 `target/jre` → assembly 打 zip，很慢，且最终 `OrzRepacker.jar` 会被 antrun 覆盖成加密版（启动必须带 `-javaagent`）。**只想要一个能直接跑的 Spring Boot fat jar 时：**

```bash
mvn -DskipTests -Dmaven.antrun.skip=true -Dassembly.skipAssembly=true package
```

这样 `target/OrzRepacker.jar` 保持未加密，`java -jar target/OrzRepacker.jar` 即可运行。

发行版的 `OrzRepacker.exe` 不在仓库里（在 Release 的 Environment.7z 中），它的行为是把加密后的 jar 解到 `%TEMP%\OrzRepacker\<version>\data.bin`，然后用捆绑的 `jre\bin\java -Xmx25g --enable-native-access=ALL-UNNAMED -javaagent:data.bin -jar data.bin` 启动。

### 自制 exe（jpackage app-image）

**产物只放 `target/dist/OrzRepacker/`，不要再另建 `target/app`、`target/image` 之类的输出目录。** 先按上面出未加密 fat jar，然后：

```bash
mkdir -p target/jpackage-input && cp target/OrzRepacker.jar libcrypto-3-x64.dll target/jpackage-input/
```

```bash
jpackage --type app-image --name OrzRepacker --app-version 1.162.50 --vendor OrzRepacker --input target/jpackage-input --main-jar OrzRepacker.jar --main-class org.springframework.boot.loader.JarLauncher --icon OrzRepacker.ico --java-options "-Xmx25g" --java-options "--enable-native-access=ALL-UNNAMED" --java-options "-Dfile.encoding=UTF-8" --dest target/dist
```

规则：

- `--app-version` 用 `application.properties` 里 `version` 去掉开头的 `v`（jpackage 只收数字版本号）。
- `--input` 是**暂存目录 `target/jpackage-input/`**，里面只放 `OrzRepacker.jar` + `libcrypto-3-x64.dll`（这个目录整个会被拷成 app-image 的 `app/`，也是 `java.library.path`，dll 要放这儿而不是 exe 同级）。**绝对不能把 `--input` 指向 `target/`**，否则 classes / lib / jre 全被打进去。
- java-options 三个都要带：`-Xmx25g`、`--enable-native-access=ALL-UNNAMED`、`-Dfile.encoding=UTF-8`。
- `OrzRepacker.ico`（仓库根目录，由 `src/main/resources/logo512.png` 生成的 16~256 多尺寸 ico）和 `libcrypto-3-x64.dll` 一样属于打包资源，放仓库根是为了 `mvn clean` 清不掉；logo 换了才需要重新生成。
- jpackage 之前先确认没有 OrzRepacker 进程在跑，**运行中的 app-image 目录删不掉也覆盖不了**（jar / runtime 被占用），删一半会把正在跑的实例弄坏。
- 这样出来的 exe 跑的是未加密 jar，不需要 `-javaagent`，代码也没混淆，和发行版不是一回事。
- 首次运行会在 exe 同级生成 `keys.dat` 和 `logs/`，交付前删掉再打包分发。

前端（仅 MCP/Web 模式需要）：

```bash
cd vue && yarn dev          # 开发，接 http://127.0.0.1:10002
cd vue && yarn build        # 产物直接输出到 src/main/resources/static（该目录被 gitignore）
```

## 启动链路

`OrangeWzApplication` 读 `--orange.gui.enabled` 决定 `headless()`，然后 `ServerManager`（`@ConditionalOnProperty(orange.gui.enabled=true)` 的 `ApplicationRunner`）在 EDT 上创建 `MainFrame.getInstance()`。

注意两个静态耦合：`ServerManager` 持有静态 `ApplicationContext`，而 `MainFrame.i18n` 是类加载时通过 `ServerManager.getBean()` 赋值的 public static 字段。**任何触碰 `orange.wz.gui` 包的代码，在 Spring 上下文起来之前都会 NPE**，写独立测试/工具类时要么先启动上下文，要么完全绕开 gui 包。

## 三种运行形态

| 形态 | 开关 | 说明 |
| --- | --- | --- |
| 桌面 | 默认 | Swing 界面，无端口 |
| MCP | `spring.main.web-application-type=servlet` | 10002 端口，`/mcp` 走 JSON-RPC |
| Web UI | 同上 + `vue` 已 build | `SpaController` 托管 `resources/static` |

## WZ 数据模型（`orange.wz.provider`）

节点树：`WzFolder`（磁盘目录）→ `WzFile`/`WzDirectory`（.wz）→ `WzImage`（.img）→ `WzImageProperty` 子树。`WzImageFile`（独立 .img 文件）和 `WzXmlFile` 都**继承自 `WzImage`**，所以对 `WzImage` 的 switch 分支会同时覆盖它们。

两个容易踩的点：

- **`getChildren()` 可能返回 null。** `WzImageProperty` 的构造函数里只有 `CANVAS_PROPERTY`、`CONVEX_PROPERTY`、`LIST_PROPERTY`、`RAW_DATA_PROPERTY` 四种 type 会 new 出 children 容器，值类型（int/string/vector/uol/null/...）的 `children` 是 null。判断用 `isListProperty()`。
- **懒解析。** `WzImage.parse()` 之前 children 是空的，`status`（`WzFileStatus`）标记解析状态。树是展开时才 `expandTreeNode()` → `parse()`。任何在树之外读子节点的代码都要自己先 `parse()` 并处理返回 false。

加解密：`WzKey`(iv + userKey) 由菜单栏的 `KeyBox` 选中，`EditPane.loadFiles()` 用当前选中的 key 解析文件；key 列表存在 CWD 的 `keys.dat`（`WzKeyStorage`，加密存储，首次运行生成三个默认 key）。

## GUI 结构（`orange.wz.gui`）

`MainFrame`（菜单栏 + 状态栏）→ `CenterPane`（左右两个 `EditPane`，右侧默认隐藏，可开同步）→ `EditPane` = 左边 `JTree` + 右边 `CardLayout` 表单区。

- 表单实例集中在 `EditPane.nodeForms`（不可变 Map，key 就是卡片名），全部继承 `AbstractValueForm`，基类负责"名称/类型"两行和保存按钮，子类用 `addRow()` / `addButton()` 往上加。
- `EditPane.handleTreeClick()` 里那个 `switch (wzObject)` 是**节点类型 → 表单的唯一入口**，新增节点类型或表单必须同时改 `nodeForms` 和这个 switch。
- 切卡片走 `switchForm(name)`，它会调用上一个表单的 `onHide()`，表单里缓存的状态应该在这里清掉。
- 界面文字一律 `MainFrame.i18n.get(key, args...)`，参数是 `MessageFormat` 语法。`messages_zh_CN.properties` 和 `messages_en_US.properties` **必须成对增删**，缺 key 会在运行时抛异常。语言由 CWD 的 `config.ini` 里 `language = zh_CN | en_US` 决定。

## MCP（`orange.wz.mcp`）

`McpServerBootstrap` 里手写 `new XxxTool(...)` + `toolRegistry.register(...)`，**不是 Spring 组件扫描**，加新 tool 必须在那里注册。会话状态在 `McpSessionManager`，节点路径解析在 `NodePathResolver`。

## 项目约定

- **commit message 前缀直接决定版本号。** `UpdateVersionNumber` 读 `git log <最新tag>..HEAD`：`Fix` 开头的 commit 让 patch +1，`Feat` / `Refactor` / `Chore` 开头的让 minor +1，然后改写 `application.properties` 里的 `version` 并自动 commit + 打 tag。历史里的风格是 `Feat: 中文描述` / `Fix: 中文描述`。
- 代码注释、日志、i18n 中文文案都用简体中文；`log` 由 Lombok `@Slf4j` 提供。
- 运行期产物（`keys.dat`、`config.ini`、`logs/`、`wz/`、`src/main/resources/static/`）都在 CWD 且已 gitignore。
