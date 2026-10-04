# Lessons — 换肤底座与 Shoal 皮肤

来源：`av:oaclpee9`（主题 `petrel:skins` 第 1 条）。规格见 `docs/spec/skins.md`。

## 冷启动不闪：Android 12+ 的系统启动页由主题决定，早于读偏好

- `MainActivity.onCreate` 在 `setContent` 之前按 `UiPrefs`（同步读）把 window background 与系统栏样式设成实际皮肤和明暗，Compose 首帧就是对的。
- 但 API 31+ 的系统启动页在 Activity 创建之前画，只看主题：默认是白底加启动器图标。所以 `values-v31` / `values-night-v31` 把 `windowSplashScreenBackground` 设成 Shoal 的 wall、图标用全透明的 `splash_blank`，启动页就是一整块底色。
- 局限：启动页只能跟**系统**明暗。「明暗」设置与系统相反时（系统浅、设置深），冷启动那一瞬仍是系统明暗的纯色，之后才变成设置的明暗。跟随系统（默认）时无缝。夜航、Tonal 加进来后，启动页底色仍固定用 Shoal 的 wall（主题里没法按用户选的皮肤换）。

## 系统栏图标色按解析后的明暗，在组合期读

`resolveDark(tone, isSystemDark())` 的结果必须在组合期读出来再交给 `SideEffect { enableEdgeToEdge(...) }`，明暗设置或系统明暗变了才会重跑。`SystemBarStyle.light` 是「浅色背景配深色图标」，别读反。细节见 Mu3ic `docs/lessons/android-system-bars.md`。

## 照画稿搬时碰到的

- **Kotlin 块注释会嵌套**：KDoc 里写 `src/*.dc.html` 会打开一层新注释，文件末尾报 `Unclosed comment`。路径里带通配符时别写进注释。
- **XML 注释里不能出现 `--`**：写 `--wall` 会让 `mergeDebugResources` 失败。
- **`radio_button_checked` 的 FILL 1 变体和画稿不一样**：画稿的 `ms f` 渲染成「环加点」，Material Symbols 仓里 `_wght500fill1` 那份是一个粗环（中间开孔）。选中项一律用 FILL 0 的 `Ms.radioButtonChecked`。底栏图标则相反，FILL 1 才是画稿的选中样式。
- **`.stat .v` 的行盒是约 25 而不是 24.2**：`line-height: 1.1` 的 Oswald 数字行里夹着一个 12px、行高 normal 的 `<small>`，它的下沿把行盒撑高（画稿实测 stat 高 56）。`Stat` 用 25 的行盒。
- **`.hero` 的 135° 渐变不是角到角**：CSS 的渐变线固定 45° 方向、长度 `(w+h)·√2/2`，Compose 的 `linearGradient` 起终点取角会随宽高比变形。`heroSurface` 按 CSS 规则算起终点。
- **连接页 tailnet 行的「待登录」**：spec §2.7 写「留意色」，画稿里是普通 `--tx2`。按画稿做了。
- 中文字体回落导致文字在行内的垂直位置与画稿差 1 到 2 dp（画稿是 Noto Sans SC，App 是系统字体，spec §5 已接受），浮岛、行的框体位置与画稿对得上。

## 夜航（`av:8ug2rclp`）

- **NavController 必须建在皮肤之上**：各皮肤的 `Theme` / `Shell` 是不同的 composable，换皮肤时它们下面的整棵子树被丢弃重建。`PetrelApp` 里自己 `rememberNavController()` 的话，在设置页点「皮肤」会跳回首页（spec §2.3 要求停在设置页）。现在 `MainActivity.setContent` 在 `skin.Theme` 外面建好传进来；NavHost 在新的 Shell 里重建时 NavController 带着返回栈。以后加皮肤、加页面别把导航状态挪回皮肤里。
- **Compose 的基线对齐会撑高行盒**：画稿里 `align-items: baseline` 的行（组标题、读数、tailnet 读数格）里常有行高 1 的大字（Barlow 30 / 36，字体自然高 1.2em）。`Modifier.alignByBaseline()` 按字体自然高度算上下伸出量，行盒比 CSS 高好几 dp。夜航里这几处改成固定行高的 Row，小字用 `padding(top = …)` 手算基线位置（CSS 里 `基线到行盒顶 = 行高/2 + (ascent − descent)/2·字号`）；`.hops li` 这种字号行高接近自然高度的行，`alignByBaseline` 没问题。
- **`font:` 简写会把行高重置成 normal**：`.prow small{font:400 11.5px var(--mono)}` 的行高是字体的 normal（Plex Mono 1.3em = 14.95），不是继承来的 1.4。逐条看清哪些 `small` / `em` 带了简写，否则 peer 行每行高 1.5 到 2 dp。
- **带 1px 描边的盒子 padding 要加 1**：CSS `box-sizing:border-box` 下内容离外沿 = 描边 + padding，Compose 的 `border()` 不占位。`Panel` 在内部再 `padding(1.dp)`，`OutlineChip` / `OutlineBtn` 的横向 padding 比 CSS 的大 1。
- **`box-shadow: 0 0 0 Npx` 的外圈只在盒子外面**：用 `drawCircle(style = Stroke(N))` 画圆环，别画实心大圆垫在底下——实心圆会让里面半透明的底色（`--okBg`）叠两层，比画稿深一档。电源键、状态灯、航线末站都是这样。
- **渲染图里节点行的 `ms` 排在数字下面**（`.msv` 在 CSS 里是行内 `small`，但渲染图换了行，行高 68.5 对得上）。按渲染图做：数字 24/1，`ms` 等宽 10 在下一行右对齐。「超时」是汉字，24 的 Barlow 在行里太抢眼，缩到 18（画稿没画）。
- **航线虚线用 `PathEffect.dashPathEffect` 的相位做流动**：背景位置向右移 = 相位往负方向走，`phase = period − offset % period`；动画值只在 `drawBehind` 里读。
- **字体来源**：Barlow Condensed 取 `google/fonts` 的 `ofl/barlowcondensed/`，IBM Plex Sans / Mono 600 取 `IBM/plex` 仓的 `packages/*/fonts/complete/ttf/`（静态）。现有的 Plex Mono 400 / 500 是 v2.3（google/fonts 来源），新加的 600 是 v2.005（IBM/plex），度量一致（asc 1025 / desc 275）。

## 多个会话共用 keystone 的 a35

keystone 上的 `a35` 可能正被别的会话占着（Mu3ic 的活）。改 `wm density`、`cmd uimode`、`force-stop` 之前先看一眼 `adb shell dumpsys window | grep mCurrentFocus`。本仓对照截图改用 `a35b`（`emulator -avd a35b -no-snapshot -no-window -no-audio -gpu swiftshader_indirect`，serial `emulator-5556`）；同宽对照把密度设成 443（1080 px / 390 dp），看完 `wm density reset`。

## Tonal（`av:ifewua51`）

- **wght 400 图标取默认文件**：`google/material-design-icons` 的 `symbols/android/<name>/materialsymbolsrounded/` 里，wght 400 就是无后缀的 `<name>_24px.xml`（FILL 0）与 `<name>_fill1_24px.xml`（FILL 1）；wght 500 才带 `_wght500` 前缀。去 `android:tint` 时别按行删：它是 `<vector>` 标签最后一个属性，那一行末尾带着标签的 `>`，整行删掉文件就不合法。用 perl 只删属性本身。
- **饼干形外形**：`r(θ) = R + a·cos(9θ)` 在 Canvas 里按 180 点画 Path（`cookiePath`），R、a 各一个 `Animatable`、同一条 `cubic-bezier(.3,.9,.3,1)` 曲线 500 ms，在 `drawBehind` 里读值；连接中的旋转是另一个 `Animatable` 在 `graphicsLayer` 里读，图标不在这一层所以不转。画稿的 COOKIE / CIRCLE 路径点可以拿来校公式（`CookieShapeTest`）。
- **画稿的小字不是模型的 `tailnetSub`**：Tonal 连接页 tailnet 卡的小字只有 IP（或「等待批准」「正在启动」「未启动」），模型的副文字带本机名前缀。`buildHome` 直接产出结构化的 `TailnetRow(sub, detail, end)` 与 `tailnetCard`，不再从格式化好的 `tailnetSub` 里 `removePrefix`。节点行的副文字也不带「 · 当前出站」（Shoal 画稿带、Tonal 画稿不带），所以 `NodeItem` 另有 `via`。
- **饼干形离开「连接中」不能把旋转角直接归零**：外形每 40°（360° / 9 瓣）重复一次，当前角度离最近的 40° 倍数最多差 20°，`snapTo(0)` 会突然跳一下。退出时用 `settledAngle` 转到最近的整倍数（同一条 `.5s cubic-bezier(.3,.9,.3,1)` 曲线）再停。
- **同样的「状态」在真机上一闪而过**：「连接中」从 `vpn == "starting"` 到 Running 不到一秒，要在设备端循环 `screencap`（一次 ssh 里连拍）才抓得到；「待登录」在人的真机上抓不到（要登出人的 tailnet 节点），改在模拟器上用只含 `ts` 链的测试配置起 VPN，全新的 tsnet 状态就是 NeedsLogin，没有登录就不会在 tailnet 里注册节点。
- **真机上的坐标**：别盲点。导航栏在 `wm density 443` 下 y≈2231；点空了会落到桌面上，误开了人手机的相机。每次点之前先看上一张截图里目标的位置。`wm density` 人的真机原来是 override 480，复原用 `wm density 480`，不是 `reset`。
