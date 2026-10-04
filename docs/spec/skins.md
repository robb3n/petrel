# Spec — Petrel 三套皮肤（Shoal / 夜航 / Tonal）

> 定稿 2026-10-03。**视觉真相源是画稿**（§1）：字形、颜色与 token、图标、控件的位置与排列、间距、各状态的样子，一律以画稿为准。本文件只管行为、数据、交互逻辑，以及画稿没画到的部分。本文与画稿在视觉细节上冲突时以画稿为准，并在 check 复述里指出来。
>
> anvil 主题 `petrel:skins`，分 3 条任务按顺序做（§3）。本文取代 `docs/spec/v1.md` 里的「视觉真相源」「颜色 token」「字体」「图标」「导航结构」各节；v1.md 的产品决定、行为细节与「画稿没画到的部分」中的行为条目仍然有效，除非本文另有规定。

## 0 已定决策（2026-10-03，人在对话里定）

1. **三套皮肤并存**：A Shoal（与 Mu3ic 同一套设计语言，**默认**）、B 夜航（深色仪表盘）、D Tonal（Material 3 Expressive）。画布上的 C 账簿不做。
2. **设置页里切换皮肤**：立即生效、不重启、持久化。设置页是新增的二级页。
3. **明暗**：设置里「跟随系统 / 浅 / 深」三选一，默认跟随系统。三套皮肤都有浅、深两面，同一个设置管全部皮肤；想让夜航一直是深色就选「深」。
4. **Tonal 用画稿里固定的蓝色调色板**，不用 Material You 动态取色。
5. **设置入口**：连接页右上角的齿轮（画稿里这个位置画的是「配置」图标，换成齿轮）。配置页仍从连接页的「配置」行进，设置页里也放一行「配置」。
6. **画稿没画的页不补画**（设置、配置 / 导入失败、tailnet 待登录、tailnet「VPN 未连接」、首次使用、节点页未连接、对话框）：forge 用该皮肤画稿里的原语套出来，结构与文案沿用 v1（§2.8），check 时人肉眼验。
7. Tonal 用**系统默认字体**，不打包 Google Sans（画稿里的 Google Sans Flex / Outfit 只是浏览器里的替身）。（refine 时定）
8. **延迟分档按画稿**（取自 shoal 的 mihomo 弹层）：< 100 ms 正常、100–199 ms 留意、≥ 200 ms 偏高。取代 v1「< 500 ms 都算正常」。（§2.7）
9. **Shoal 里节点名、IP 用 Rubik**（画稿如此），不用等宽字；夜航、Tonal 仍用 IBM Plex Mono。对 Shoal 而言，这条取代 v1 产品决定第 10 条里「等宽数据」的部分。

## 1 画稿怎么看

- **在线画布**：https://claude.ai/artifact/KCwYokjE19qGB1hEbQcR9w （需要人的 claude.ai 登录）。四行依次是 A Shoal、B 夜航、C 账簿（不做）、D Tonal。每张画板的 Tweaks 可以切深色；连接页还能切「已连接 / 连接中 / 待登录 / 未连接」。
- **仓库副本**（与画布一致，取精确数值读这里）：`docs/spec/assets/ui-skins/src/<画板>.dc.html`。每个文件 `<helmet><style>` 里的 `.phone` 是浅色 token、`.phone[data-theme="dark"]`（夜航是 `[data-theme="light"]`，它的默认面是深色）是另一面；类名就是原语，数值都在 CSS 规则和少量 inline `style` 里。`<script>` 里的 `M = {…}` 表是各状态的文案与样式类，`renderVals()` 是数据怎么变成显示文字。
- **渲染图**：`docs/spec/assets/ui-skins/png/<名字>-<light|dark>.png`，390 dp 宽、2 倍密度（PNG 宽 780 px），用 Read 工具直接看。连接页另有 `-connecting-`、`-login-`、`-off-` 三个状态。

| 皮肤 | 屏 | 源文件 | 渲染图前缀 |
|---|---|---|---|
| Shoal | 连接 | `Main.dc.html` | `ShoalHome` |
| Shoal | 节点 | `ShoalNodes.dc.html` | `ShoalNodes` |
| Shoal | tailnet | `ShoalTailnet.dc.html` | `ShoalTailnet` |
| 夜航 | 连接 / 节点 / tailnet | `NightHome` / `NightNodes` / `NightTailnet.dc.html` | 同名 |
| Tonal | 连接 / 节点 / tailnet | `TonalHome` / `TonalNodes` / `TonalTailnet.dc.html` | 同名 |

- **单位**：CSS px = dp，字号 px = sp。画稿没画系统状态栏和手势条：顶部留白 = 状态栏 inset + 画稿 header；底部同理加手势条 inset（App 走 edge-to-edge）。
- **示意数据**：IP（`100.64.0.x`）、延迟、节点名、离线节点 `op12`、tailnet 设备图标都是示意，实际内容来自内核。
- **画稿里不是界面文案的东西**：三张节点页最后一行说明「配置里有多个 select 组时…」是写给人看的注释，**不实现**。Tweaks 的 `dark` / `state` 是画稿自己的切换机制，不对应设置项（明暗设置见 §2.1）。
- **Shoal 的上游**：它就是 Mu3ic 的 shoal。Mu3ic 的 `mockups/shoal-style-redesign.html`（mockup v2）与 `app/src/main/java/com/robb3n/mu3ic/ui/theme/Shoal.kt`、`ui/shoal/` 是同一套 token 与原语的现成实现；Petrel 画稿的 Shoal CSS 就是从那份 mockup 抄来的。两边数值不一致时以 Petrel 画稿为准。

## 2 设计与行为

### 2.1 偏好

- 新建 `UiPrefs`（object，同仓库现有 repository 的写法）：SharedPreferences 文件名 `ui`，两个键：
  - `skin`：`"shoal"` | `"night"` | `"tonal"`，默认 `"shoal"`
  - `tone`：`"auto"` | `"light"` | `"dark"`，默认 `"auto"`
  - 读到未知值一律当默认值；写入用 `apply()`。
  - 暴露 `StateFlow<Skin>`、`StateFlow<UiTone>` 与 setter；进程里第一次访问时同步读一次（SharedPreferences 很小），保证第一帧就是对的皮肤和明暗，不闪。
- `resolveDark(tone, systemDark)`：AUTO 跟系统，LIGHT 恒浅，DARK 恒深（照 Mu3ic `data/AppLook.kt` 的同名函数）。
- 设置页只列**已经实现**的皮肤：task 1 结束时只有 Shoal，「皮肤」整行不显示；task 2 起显示 [Shoal | 夜航]，task 3 起显示 [Shoal | 夜航 | Tonal]。界面上的皮肤名就写 `Shoal`、`夜航`、`Tonal`。

### 2.2 皮肤无关的界面模型

三套皮肤显示同一份数据。为了让加功能时只在模型里做一次、三套皮肤只管画，数据推导与渲染分开：

- 新包 `ui/model/`：纯函数，把 `CoreState`、`ConfigInfo`、`ImportUi`、`GeoIpStatus?`、`geoBusy`、`List<ProxyGroupUi>`、`testing`、`TailnetStatusUi?`、`UiPrefs` 推导成各屏的模型。v1 散在各屏里的推导（`statusView`、`proxySubtitle`、`baseNodes`、`delayLabel`、tailnet 路径文案、日期格式）搬到这里。建议的形状（名字与字段可调，语义不能少）：
  - `ConnStatus`：`NoConfig`、`Off`、`Connecting`、`NeedsLogin`、`Connected`、`TailnetOther(state)`（VPN 在跑、tailnet 既不是 Running 也不是 NeedsLogin，同 v1「已连接 / tailnet <state>」留意色）。
  - `HomeUi`：`status`、`error`（`CoreState.error`，空串 = 无）、`chain: List<Hop>`（空 = 不显示链路块）、`exitDelay: Delay`、`groupName`、`groupSize`、`tailnet: TailnetSummary?`（本机 IP、在线数、总数、直连数、中继数）、`config`（是否存在、文件名、时间、导入还是更新）、`importing`。
  - `Hop(name, role)`，`role` ∈ `Tailnet` / `Front` / `Exit`。
  - `Delay`：未测（-1）/ 超时（0）/ 毫秒数；派生 `band`（Ok / Warn / Bad / Idle）、显示文字、夜航信号格数（§2.7）。
  - `NodesUi`：`running`、`testing`、`groups`（组名、当前选中、成员：名字、副标题、`Delay`）、`baseNodes`（名字、是不是 `ts`、角色说明 `caption`、`ts` 的状态标签 `status`）。
  - `TailnetUi`：`NotActive` / `NeedsLogin(loginURL)` / `Running(selfName, selfIp, peers, 计数)`；peer：名字、IPv4（`firstIPv4()`）、`OsKind`、是否在线、路径文案与 band。
  - `ConfigUi`、`SettingsUi`（当前皮肤、可选皮肤列表、明暗、版本号、配置文件名）。
- **状态映射与文案只在模型里写一次**（回火 `av:3gg1fo1g` 收口）：皮肤只负责排版和选自己的样式，不得再做状态到文字的映射。已经在模型里的：
  - 延迟：`Delay.view(testing)` 给出显示文字、band、信号格数与大数字读数 `DelayReadout`（数字、单位、`timeout` 标志、着色档 Idle / Normal / Bad）；测速中的「…」也在这里。
  - 连接状态：`ConnStatus.label`、`sub()` 与着色档 `tone`（Idle / Busy / Warn / Live，三套皮肤各映射到自己的标签 / 状态灯 / 饼干形）。
  - 链路角色说明：`HopRole.caption(group, detailed)`（`detailed` 是 Shoal 的措辞）；底座节点的 `caption` 与 `ts` 状态 `NodeStatus`（见 §2.7）；链路卡说明 `chainSub`，与节点页组卡头共用 `groupSubtitle(size)`（「手动选择 · N 个节点」）。
  - tailnet：`TailnetEnd.view(spaced)`（`5 / 6 在线` 与 `5/6 在线` 两种写法）、`TailnetSummary` 的 `ratio` / `onlineLabel` / `pathLabel`；连接页的 `tailnetSub` / `tailnetCard` 由 `buildHome` 直接产出，不从格式化好的字符串里抠。
  - 配置：`ConfigSummary` 的 `stampShort` / `stampNight` / `stampFull`；`ConfigUi` 的 `timeLine`（含「仍在使用」）、`geoText` / `geoBad`、`rewrites`；首次使用三步 `FIRST_USE_STEPS`。
- **动作**：一个 `PetrelActions`（lambda 集合，名字可调）：`toggle`、`importConfig`、`replaceGeoIp`、`select(group, node)`、`testDelay`、`logout`、`openLogin`、`copyLogin`、`copyAddress(ip)`、`setSkin`、`setTone`，加导航 `openNodes` / `openTailnet` / `openConfig` / `openSettings` / `back`。
- **皮肤包里不得直接读 repository 或 `CoreBridge`**，只渲染模型、调用动作。只有这样，「新功能 = 模型一次 + 三套渲染」这条约定才守得住（写进 AGENTS.md，见 §3 task 1）。
- **JVM 单测**（新增 `testImplementation("junit:junit:4.13.2")`，放 `app/src/test/`）覆盖模型的纯函数：状态映射全分支、链路推导（chain 倒序、角色、只有一跳、没有组、成员找不到）、延迟分档与信号格、peer 路径文案与计数、`proxySubtitle`、`baseNodes`、`OsKind` 映射、日期格式。

### 2.3 壳与皮肤

- `PetrelApp` 继续持有 `NavController` 和路由：一级标签（连接 / 节点 / tailnet）是 `tabs` 路由里同一个 `HorizontalPager` 的三页（`ui/skin/TabPager.kt`，同 Mu3ic 主壳），根页上左右滑动跟手切换；`config`、`settings` 是推在 `tabs` 上的二级页（无底栏），在二级页上切标签先退回 `tabs`。pager 状态与 `NavController` 同在 `MainActivity` 根部，换皮肤不丢。返回键语义同 v1：二级页返回上一页；在节点或 tailnet 标签回连接页；连接页返回退出 Activity。v1 里「导入失败横幅一直显示到下一次导入或离开配置页」「从首页发起导入失败时自动进配置页」两条行为保留。
- 每套皮肤实现同一个接口（形状可调）：

  ```kotlin
  interface Skin {
      @Composable fun Theme(dark: Boolean, content: @Composable () -> Unit) // token CompositionLocal + MaterialTheme colorScheme 映射
      @Composable fun Shell(tabs: TabPager?, content: @Composable () -> Unit) // 底栏与 inset；tabs == null 是二级页
      @Composable fun Home(ui: HomeUi, a: PetrelActions)
      @Composable fun Nodes(ui: NodesUi, a: PetrelActions)
      @Composable fun Tailnet(ui: TailnetUi, a: PetrelActions)
      @Composable fun Config(ui: ConfigUi, a: PetrelActions)
      @Composable fun Settings(ui: SettingsUi, a: PetrelActions)
  }
  ```
- 包：`ui/skin/shoal/`、`ui/skin/night/`、`ui/skin/tonal/`，各自放 token（浅 / 深两组，逐一照抄画稿 CSS 变量）、字体族、原语、各屏。`MaterialTheme.colorScheme` 也按各皮肤映射一份（`background`、`surface`、`primary`、`error` 等），让 `AlertDialog` 这类没重画的 M3 组件取到对的颜色。
- **切换皮肤**：`NavController` 在皮肤之上，换皮肤只是重组，人停在设置页不动。不需要转场动画。`NavController` 要在 `MainActivity.setContent` 根部（`skin.Theme` 外面）建好再传进 `PetrelApp`：各皮肤的 `Theme` / `Shell` 是不同的 composable，建在里面会随换皮肤丢掉，人就回到首页。
- **v1 视觉退役**（task 1 做完 Shoal 后）：删掉 `ui/theme/Theme.kt` 的 `PetrelColors`、`ui/Components.kt` 和 v1 的各屏 composable；`ui/Icons.kt` 的 v1 线条图标保留（夜航要用），`PText` / Plex Mono 字体族按需留。

### 2.4 系统栏与窗口底色

- 先读 `~/Projects/Mu3ic/docs/lessons/android-system-bars.md`。状态栏 / 导航栏图标色按**应用实际明暗**（`resolveDark`）选 `SystemBarStyle.light` / `.dark`，不直接照抄系统深色开关；明暗设置或系统明暗变了要重新设置。
- 窗口底色：`setContent` 之前按当前皮肤与明暗把 window background 设成该皮肤的页面底色，避免启动时闪一下别的颜色。`res/values/themes.xml` 与 `values-night/themes.xml` 的 `windowBackground` 改成默认皮肤 Shoal 的 `--wall`（浅 `#E9E1D3`、深 `#14120E`）。
- **系统启动页（Android 12+）**：由主题决定、早于读偏好，所以 `values-v31` / `values-night-v31` 把 `windowSplashScreenBackground` 设成 Shoal 的 `--wall`、图标用全透明的 `splash_blank`，只能跟系统明暗。明暗设置与系统相反时，冷启动那一瞬仍是系统明暗的纯色（已接受，§5）。夜航、Tonal 加进来后启动页底色仍固定用 Shoal 的 `--wall`。
- `TileLaunchActivity`（透明）、常驻通知、快捷开关磁贴、启动器图标都**不随皮肤变**。

### 2.5 字体

| 皮肤 | 打包的字体 | 来源 |
|---|---|---|
| Shoal | Rubik 400 / 500 / 600 / 700，Oswald 400 / 500 | 直接复制 `~/Projects/Mu3ic/app/src/main/res/font/` 的 `rubik_*.ttf`、`oswald_*.ttf`（静态 TTF，OFL） |
| 夜航 | Barlow Condensed 500 / 600，IBM Plex Sans 400 / 500 / 600，IBM Plex Mono 400 / 500（已有）+ 600（新增） | `github.com/google/fonts` 的 `ofl/` 或 `github.com/IBM/plex` release，取**静态** TTF |
| Tonal | 不打包，用系统默认字体；数据用 IBM Plex Mono 400 / 500 | — |

- 中文三套都走系统字体（同 v1），画稿里的 Noto Sans SC 与系统字体的字形差异不算偏差。
- 画稿 Shoal 的 `--mono`（`.lbl em` 用的 JetBrains Mono）在 App 里换成已打包的 IBM Plex Mono，不另打包。
- `licenses/` 补齐许可文本：Rubik、Oswald、Barlow Condensed、IBM Plex Sans 的 OFL（Plex Mono 已有），Material Symbols 的 Apache-2.0。
- CSS 行高在 Compose 里怎么还原、单行字为什么要 `CssText` / `cssLineBox`：读 `~/Projects/Mu3ic/docs/lessons/shoal-mockup-porting.md`，原语可以直接从 Mu3ic `ui/shoal/ShoalUi.kt` 移植。

### 2.6 图标

- **Shoal**：Material Symbols Rounded，FILL 0 / 1、wght 500，画稿里 `class="ms f"` 用 FILL 1。照 Mu3ic 的做法：`res/drawable/ms_<name>.xml`（FILL 0）与 `ms_<name>_fill.xml`（FILL 1），去掉 `android:tint` 属性（只删属性，别按行删），由 `Icon(tint=…)` 着色，用一个 `Ms` object 暴露（Mu3ic `ui/shoal/Ms.kt`）。Mu3ic 已有的直接复制，缺的从 `github.com/google/material-design-icons` 的 `symbols/android/<name>/materialsymbolsrounded/` 取 wght500 变体（文件名以仓内实际为准）。名单 = Shoal 三张画板里所有 `.ms` 的文字，加上未画页需要的（如 `settings`、`arrow_back`、`download`、`check`、`error`、`open_in_new`）。
- **Tonal**：同一来源的 **wght 400** 变体（FILL 0 / 1），单独一组前缀（如 `ms400_<name>`），不与 Shoal 的 wght500 混用。
- **夜航**：v1 的线条图标（`ui/Icons.kt`，路径见 `v1.md`「图标」表），画稿里的 inline SVG 就是这套。设置齿轮 v1 没有：按同一规格（24 网格、stroke 1.8、圆头圆角、无填充）补一个。

### 2.7 状态、数据与文案（三套一致，差异处注明）

**连接状态 → 文案**（主文字 / 副文字）：

| 状态 | 主文字 | 副文字 |
|---|---|---|
| NoConfig | 未连接 | 还没有配置。导入一份 mihomo YAML 就能连接。 |
| Off | 未连接 | Shoal「点右边的开关，或用状态栏快捷开关」· 夜航「点电源键，或用状态栏快捷开关」· Tonal「点上面的按钮，或用状态栏快捷开关」 |
| Connecting | 连接中 | 正在启动 tailnet 与代理 |
| NeedsLogin | 待登录 | 登录 tailnet 之后代理才能用 |
| Connected | 已连接 | 代理与 tailnet 都已就绪 |
| TailnetOther | 已连接（留意色） | tailnet <state> |

- `CoreState.error` 非空时在副文字下面多一行错误文字，用该皮肤的失败色；Shoal 的状态卡在橄榄渐变上时，错误行用 `#FFF6E0` 加一个 `error` 图标，不用红字。
- 各状态的样式（Shoal 状态卡的 `hero` / `hero off`、开关 `sw on` / `sw on busy`、标题旁 tag；夜航 `status live|busy|warn|off`；Tonal 的饼干形 `ck` / `ck busy|warn|off`）照各连接页源文件的 `M` 表与 CSS，渲染图有四个状态可对。待登录状态在三套里都有「去登录 tailnet」按钮，进 tailnet 标签。

**当前链路**：取第一个 select 组当前选中成员的 `chain`，倒序显示（先 `ts` 再前置、最后出口）。角色：名字是 `ts` → tailnet 第一跳；最后一跳 → 出口；成员 `relays` 里的（配置里 `petrel-via` 标注的中转）→ 中转；其余 → 代理前置。只有一跳时只显示出口；没有组、或找不到选中成员时整块不显示。链路块只在 `config.present && running` 时显示（含待登录）。待登录时出口延迟显示「—」。夜航航线图的站点 = 本机 + 每一跳（标签：tailnet / 前置 / 中转 / 出口），站点等距排开。

**延迟**（节点行、出口、Shoal 状态卡）：

| 值 | 显示 | band | 夜航信号格（共 4 格） |
|---|---|---|---|
| -1（未测） | — | Idle | 0 |
| 0（超时） | 超时 | Bad | 0 |
| < 100 | `N ms` | Ok | 4 |
| 100–199 | `N ms` | Warn | 3 |
| 200–399 | `N ms` | Bad | 2 |
| ≥ 400 | `N ms` | Bad | 1 |

- 夜航补充（av:8ug2rclp 定）：
  - 连接页出口读数不按档位着色：已连接且延迟 > 0 时恒用 `--ok`（画稿 182 ms 也是绿的），未测、超时、待登录用 `--ink3`。节点行的信号格按档位着色；**数字只有 Bad 档（≥ 200 ms 与超时）用 `--err`，Ok / Warn 档沿用画稿的 `--ink`**，未测与测速中 `--ink3`（画稿没画 Bad 档，以此为准）。
  - 节点行没有单位（测速中、超时、未测）时也占住 `ms` 那一行（15），否则这些行比有数值的行矮、列表会跳。
  - 「超时」是汉字，24 的 Barlow 在节点行里太抢眼：节点行缩到 18，连接页读数缩到 24（画稿没画这个状态）。
- band 对应颜色：Shoal `.lat.ok/.warn/.bad/.idle`；夜航 `--ok/--warn/--err/--ink3`；Tonal 的 badge 用 `okC`、`warnC`，Bad 用 M3 error container（浅 `#FFDAD6` / `#410002`，深 `#93000A` / `#FFDAD6`），Idle 用 `sfHH`。测速中三套都显示「…」并用 Idle 样式。
- 账簿那套「延迟数字直接上色」不做。

**Shoal 状态卡三格**：出口延迟（出口的 `Delay`）、tailnet 在线（`在线数/总数`）、可选出口（第一个 select 组的成员数）。数据没到或未运行显示「—」。

**tailnet 摘要**（连接页的 tailnet 行 / 卡）：

| 状态 | 副文字 | 右侧值 |
|---|---|---|
| Connected | `op12-petrel · <本机 IPv4>` | Shoal `5 / 6 在线` · 夜航 `5/6 在线`（正常色）· Tonal 卡大字 `5/6 在线` |
| NeedsLogin | `op12-petrel · 等待批准` | 待登录（留意色） |
| Connecting | 正在启动 | 夜航、Tonal「—」，Shoal 空 |
| Off / NoConfig | 未启动 | 同上 |

- 节点数据来源：`TailnetRepository.refresh` 现在只在 tailnet 标签可见时每 5 秒调一次；连接页也要显示在线数，改成**连接页或 tailnet 标签可见时**都刷新。
- 本机名就是 `TAILNET_HOSTNAME`。

**配置摘要**：文件名（`ConfigInfo.name`）；时间 Shoal、Tonal 写 `M月d日导入|更新`，夜航写 `MM-dd 导入|更新`（等宽）。

**tailnet 节点路径**：Direct「直连」Ok；Relay「中继 · <DERP 区域>」Warn；PeerRelay「中继 · 节点」Warn；Idle「空闲」Idle；Offline「离线」Idle，整行按画稿 `.row.off` / `.prow.off` / `.item.off` 变淡。计数：直连 = Direct 数，中继 = Relay + PeerRelay 数，在线 = `onlineCount`。

**设备图标**（Shoal、Tonal 的 peer 行；夜航用状态点不用图标）：`os` 为 android / iOS → `smartphone`；macOS → `laptop_mac`；windows → `desktop_windows`；linux 及其它 → `dns`。画稿里 keystone 画成 `laptop` 只是示意。

**链路底座里 `ts` 的状态**（节点页「链路底座」的标签 / 状态点）：tailnet Running →「已连接」（Live 样式）；NeedsLogin →「待登录」（Warn 样式）；其余状态（Starting、NoState 等）显示状态名，用 Idle 样式，不冒充待登录。非 `ts` 的底座节点没有状态标签。中转（`petrel-via`）也是底座节点，说明为「中转 · 配置里用 petrel-via 标注」，图标同代理前置。

**当前出口的长节点名不截断**：名字里有很长的连字符串时（例如 `hk-hkt-premium-01-dialer`），**当前出口**所在的行 —— 链路的末跳、节点页的选中项（三套皮肤都是）—— 允许在连字符后折到两行：名字里每个 `-` 后插一个零宽空格（`String.breakAfterHyphens()`），文字 `maxLines = 2`，超出仍省略。其余节点名保持单行省略。

**其它固定文字**：夜航页头的「TUN · gvisor」是固定文字，任何状态都显示（tun stack 恒为 gvisor）。Shoal 链路里 `ts` 的副文字是「tailnet 第一跳 · Petrel 注入」，出口是「出口 · PROXY 当前选中」（PROXY = 组名）。

**交互**（三套一致）：

- 开关（Shoal 状态卡开关 / 夜航电源键 / Tonal 饼干形按钮）= v1 的 `toggle`；NoConfig 时不可用，改由「导入 YAML…」主按钮接手。
- 「切换」、出口行 → 节点标签；tailnet 行 / 卡 → tailnet 标签；配置行 / 卡 → 配置页；齿轮 → 设置页。切到标签都是 pager 滑过去，底栏指示器跟着滑。
- Shoal 状态卡的三个统计格可点（同 Mu3ic 首页统计格，按下叠高亮）：出口延迟 → 节点标签，tailnet 在线 → tailnet 标签，可选出口 → 节点标签。
- **刷新**：三套连接页右上角、齿轮左边一个刷新按钮（Shoal `refresh` wght 500、Tonal `refresh` wght 400、夜航补一个同规格的线条图标，路径见 `v1.md` 图标表）。点了 = 预热 tailnet 第一跳 + 测所有组的延迟 + 重拉 tailnet 节点列表；不重启 VPN、不重读配置。VPN 没在跑时置灰；进行中图标匀速旋转（.9s/圈）、不能再点，与节点页的「测速中」是同一个状态。
- 节点行点按 = `select(group, node)`，失败 Toast「切换失败：…」（同 v1）。「测延迟」只在运行中显示，测速中禁用、文字「测速中」。
- 本机地址旁的复制钮 = 复制本机 IP；长按 peer 行复制它的地址，Toast「已复制地址」（同 v1）。peer 行本身不可点：不带 click 语义（没有单击涟漪，读屏不报「双击激活」），只有一个名为「复制地址」的长按操作（`Modifier.longPressOnly`）；没有 IPv4 的 peer 没有这个长按动作。
- 登出 → 确认对话框（v1 文案：「登出 tailnet？」/「登出后要在电脑浏览器里重新批准登录，期间代理用不了。」/ 取消 / 登出）→ `logout`。
- 自绘控件补无障碍语义（Mu3ic AGENTS 的规则）：Seg 每项 `selected`，开关 `toggleableState`，节点行 `Role.RadioButton` + `selected`，图标按钮有 `contentDescription`。

### 2.8 画稿没画的页（决策 6）

每页用**该皮肤画稿里已有的原语**拼，结构与文案取 v1（`docs/spec/assets/ui-v1/src/` 对应画板 + 现有代码里的字符串），不发明新视觉元素。各皮肤可用的原语：

- Shoal：`.isle` + `.ph`（tile + 标题 + 副标题 + 尾部控件）、`.card`、`.row` / `.row.sel`、`.lbl`、`.foot`、`.mini`、`.obtn`、`.tag`、`.lat`、`.sw`、`.kv`、`.tile`，以及 Mu3ic 的 `.seg`（Petrel 画稿没用到，取 Mu3ic mockup v2 的 `.seg` 规则）。
- 夜航：`.panel`、`.cap`（中文 + 等宽英文小标）、`.ghead`、`.lrow` / `.nrow` / `.prow`、`.chip`、`.obtn`（描边按钮）、`.ghost`（整宽描边按钮）、`.wbtn`（留意色按钮）、`.reads` 读数格、`.foot`。实心主按钮 = `--ink` 底、`--bg` 字、8 dp 圆角、高 44。分段控件 = `.panel` 底、1 dp `--line` 描边、8 dp 圆角，选中段 `--ink` 底 `--bg` 字。
- Tonal：`.group` + `.item`（首 / 末行大圆角）、`.ghead`（主色小标题）、`.badge`、`.chipm`、`.tc` 色调卡、`.fbtn`（实心）、`.obtn`（描边）、`.efab`、`.selfc` 色调大卡；分段控件用 M3 Segmented Button 的形状，颜色取 `secC` / `onSecC`。

| 页 | 结构与文案来源 | 要点 |
|---|---|---|
| 首次使用（NoConfig 的连接页） | v1 `HomeEmpty` | 状态区显示 NoConfig 文案，开关不可用；主按钮「导入 YAML…」（校验中「校验中…」并禁用）；下面「首次使用」三步（导入配置 / 授权 VPN / 批准 tailnet 登录，文案同现有 `FirstUseCard`）；底部提示「之后从状态栏快捷开关一键开关，App 被强制停止也能拉起。」 |
| 节点页未运行 | 现有 `NodesScreen` | 居中一行「连接后才能查看和切换节点」，不显示「测延迟」 |
| tailnet「VPN 未连接」 | 现有 `TailnetScreen.NotConnected` | 「VPN 未连接」+「连接后显示 tailnet 状态」 |
| tailnet 待登录 | v1 `TailnetLogin` | 标题「需要登录 tailnet」（留意色）、说明、登录链接框（等宽；没拿到时「正在获取登录链接…」）、提示段、两个等宽按钮「复制链接」（主）/「浏览器打开」（次），链接没到时都禁用 |
| 配置 / 导入失败 | v1 `Config`、`ConfigError` | 左上返回；当前配置卡（文件名、`M月d日 HH:mm 导入|更新`、校验通过 / 仍在使用）；主按钮「导入 YAML…」与说明行；GeoIP 卡（`Country.mmdb · 已就绪|文件损坏|—`、「替换…」/「校验中…」）；「加载时 Petrel 会改写这些」卡（四行：`ts`、`tun`、`external-controller`、`interface-name · routing-mark`；`external-controller` 一行写明「固定 127.0.0.1:9090，没写 secret 时随机生成；其余 controller 入口一律删除」，文案在 `ui/model` 的 `PETREL_REWRITES`）；失败横幅在最上面 |
| 设置（新增） | 无 | 左上返回，标题「设置」。三组：**外观**（「皮肤」分段，只列已实现的皮肤，见 §2.1；「明暗」分段：跟随系统 / 浅 / 深）；**配置**（一行，副标题是当前文件名，进配置页）；**关于**（「版本」`v` + `BuildConfig.VERSION_NAME`；「开源许可」打开对话框，列出打包的字体、图标、mihomo、tailscale 及各自许可证）。Shoal 的组织方式参照 Mu3ic mockup v2 的 ⑤ 设置屏与 Mu3ic `SettingsScreen.kt` 的「外观」浮岛（浮岛 + `.lbl` + `.seg`） |
| 对话框 | v1 | 登出确认、开源许可：`AlertDialog`，颜色取该皮肤的 colorScheme 映射 |

夜航拼未画页的具体排法（av:8ug2rclp 定）：首次使用是 `.cap` 头加三个步骤行（序号用等宽 01 / 02 / 03）；配置页的失败横幅是 `--err` 描边的 panel，主按钮「导入 YAML…」用实心 ink 底整宽，GeoIP 行没有行首图标（v1 图标集里没有合适的）；设置页是「外观 / 配置 / 关于」三组（`.ghead` + `.panel`，分段控件内边距 4）；tailnet 待登录页是 `--warn` 色标题、`--p2` 底的链接框、两个按钮「复制链接」（实心）/「浏览器打开」（描边）。NoConfig 时电源键画成 off 样式、只是不可点。

Toast 文案全部沿用 v1。

### 2.9 动效

- **Shoal**：开关拇指 `.22s cubic-bezier(.3,.9,.3,1)`；连接中拇指透明度在 1 ↔ 0.35 之间往复（`.7s`，ease-in-out alternate）；测速中图标匀速旋转（`.9s`/圈）。
- **夜航**：已连接时航线是 8 dp 实、6 dp 空的虚线，以每 `.9s` 14 dp 的速度向出口方向流动；待登录时改成 4 / 4 的静止虚线（`--line` 色），tailnet 站点变成留意色；连接中状态灯透明度往复（`.7s`）。
- **Tonal**：大按钮的形状是 `r(θ) = R + a·cos(9θ)`（画稿 viewBox 200，按 176 dp 缩放；θ 从正上方顺时针）。已连接 / 待登录 / 连接中 R = 91、a = 9（饼干形），未连接 R = 94、a = 0（圆）；状态切换时 R 与 a 一起在 `.5s cubic-bezier(.3,.9,.3,1)` 内插值，填充色同步过渡。连接中整个形状匀速旋转（5 s/圈）。用 Canvas 按公式画 Path 即可，不必引入 graphics-shapes。
- 动画值必须以 `State` 在 draw / graphicsLayer lambda 里后读，见 `~/Projects/Mu3ic/docs/lessons/android-compose-animation.md`。

### 2.10 Inset 与底栏

- 先读 `~/Projects/Mu3ic/docs/lessons/android-window-insets.md`。
- **Shoal**：底栏浮在内容上（`.fnav`：左右 12、底 16 + 手势条 inset、高 56），内容可以滚到它后面；可滚屏底部留白 = 画稿 `.scroll` 的 `padding-bottom: 100px` + 手势条 inset。二级页没有底栏。
- **夜航**：底栏贴底（`.dock` 高 64 + 手势条 inset，顶部 1 dp `--line`），内容区止于底栏上沿。
- **Tonal**：M3 导航栏（`.nav` 高 80 + 手势条 inset；指示胶囊 56 × 32，照画稿，不用 M3 默认的 64 × 32）；节点页的扩展 FAB 在导航栏上方 16 dp、右 16 dp，内容底部留出 FAB 的高度。
- 顶部：状态栏 inset + 各皮肤 appbar（Shoal 56、夜航 60、Tonal 64）。
- **底栏动效**（同 Mu3ic 的 `FloatingNav`）：三套的选中指示（Shoal 的 `--ol` 滑动胶囊、夜航的 32×2 `--ok` 顶线、Tonal 的 56×32 `--secC` 指示胶囊）都只有一块，位置就是 pager 的实时位置（`TabPager.position`）：滑页、点某项、拖胶囊时跟着走，不是点完再弹过去。各项字色 / 图标按离指示器多近渐变，过半换实心图标（Tonal 标签同时换 700）。Shoal 另有：按住胶囊横拖，手指每横移一项的间距 pager 翻一页，松手按落点与甩动方向（> 1.2 项 / 秒）吸附；每项按下缩到 0.95 并带涟漪。

## 3 任务拆分

| # | 任务 | 内容 |
|---|---|---|
| 1 | 搭换肤底座并做 Shoal 皮肤 | §2.1–§2.4 全部；Shoal 的字体、图标、原语与全部页面（含 §2.8 的未画页和设置页）；系统栏与窗口底色；v1 视觉退役；`licenses/` 补 Rubik、Oswald、Material Symbols；更新 AGENTS.md（v1 功能范围后补「三套皮肤」一句、指向本文；代码约定加「新功能 = `ui/model` 一次 + 三套皮肤各画一次，皮肤包不读 repository」）；`v1.md` 开头加一句「视觉部分已由 `skins.md` 取代」 |
| 2 | 做夜航皮肤 | 夜航的字体、原语与全部页面；设置页出现 [Shoal \| 夜航]；`licenses/` 补 Barlow Condensed、IBM Plex Sans |
| 3 | 做 Tonal 皮肤 | Tonal 的 wght400 图标、原语与全部页面（含饼干形变形、扩展 FAB）；设置页出现 [Shoal \| 夜航 \| Tonal] |

后两条依赖 task 1 的底座；每条都要把自己那套皮肤的**所有**页面做齐，不留「暂用别的皮肤」的页。

## 4 验证（每条任务的完成判据）

- 构建：`unset http_proxy https_proxy all_proxy; ./gradlew :app:assembleDebug`
- 单测：`./gradlew :app:testDebugUnitTest`（task 1 新建，后两条不得打破；新增的模型函数要带测试）
- 视觉对照：在 a35 上截本任务那套皮肤的每一屏、浅深两面（切明暗用设置里的「浅 / 深」；「跟随系统」那一档用 `adb shell cmd uimode night yes|no` 验一次）。需要 VPN 运行的状态（已连接的链路、节点列表、tailnet 节点）在真机上截；真机测完把人的明暗与皮肤设置恢复原样。与 `docs/spec/assets/ui-skins/png/` 同明暗面的渲染图缩放到同宽并排对照，逐项核对：布局与排列、间距、字号层级、颜色 token、图标、各状态文案。偏差要么改到一致，要么列进 check 复述交给人定。未画的页没有渲染图，截图交人肉眼验。
- 切换：设置里来回切皮肤与明暗，界面立即变、停在设置页；杀进程后重开仍是上次的选择；冷启动没有别的颜色闪一下；状态栏图标色与内容明暗一致。
- 回归（真机）：快捷开关冷启动、最近任务卡片照旧（`docs/lessons/tile-and-device-testing.md`），因为 `MainActivity` 动了。

## 5 已接受的偏差

- 中文字形：画稿是 Noto Sans SC，App 是系统字体（同 v1）。Tonal 的拉丁字母：画稿是 Google Sans Flex，App 是系统字体。Shoal 的 `--mono` 换成 IBM Plex Mono。
- CSS 多层阴影（Shoal `--shIsle` / `--shPill`）用 elevation 阴影近似（同 Mu3ic）。
- 画稿没画状态栏，真机顶部会整体下移一个状态栏高度。
- 冷启动的系统启动页只能跟系统明暗（§2.4）：设置里的明暗与系统相反时，冷启动那一瞬是系统明暗的纯色。
- 连接页 tailnet 行右侧的「待登录」按画稿用普通 `--tx2`，不用留意色（§2.7 表里的「留意色」以画稿为准）。
- Shoal 里「导入 YAML…」按钮用画稿 `.obtn`（30 高）：首次使用页是内联小胶囊，配置页整宽；不另造 v1 的 52 高大按钮。
- 夜航字体来源：Barlow Condensed 取 `google/fonts` 的 `ofl/barlowcondensed/`，Plex Sans 与 Plex Mono 600 取 `IBM/plex` 仓的静态 TTF（v3.005 / v2.005）；现有 Plex Mono 400 / 500 是 v2.3，度量一致、字形同款。
- 夜航节点行的 `ms` 排在数字下面（渲染图如此；画稿 CSS 里它是行内 `small`）。
- Tonal 连接页 tailnet 卡的小字只写 IP（画稿如此），不写 §2.7 表里的「op12-petrel · IP」；节点行副文字不带「 · 当前出站」（画稿如此，Shoal 画稿带）。
- Shoal 的 tailnet 待登录页用 butter 系（butter tile + 「待登录」tag）表达留意色；首次使用三步用 `.row` 加编号 tile；配置页的配置键名用 Rubik；导入失败 tile 用 `--pink` / `--pinkInk`，「校验通过」用 `Tag.Live`。

## 6 out-of-scope

账簿皮肤；Material You 动态取色；按皮肤换启动器图标、磁贴图标或通知样式；新功能（流量、出站模式切换等）；除新增设置页以外的信息架构改动；各皮肤专属的设置项。
