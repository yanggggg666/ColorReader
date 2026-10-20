# 潮阅 (OceanReader)

> 当前版本 **v1.2.0**（versionCode 9）· Debug 签名，同签名可直接覆盖安装

### v1.2.0
- **上下滚动阅读模式**（默认开启，微信读书式）：整章连续上下滑，滑动手感跟手；章末显示「本章完」并可一键「下一章」；朗读跟读自动把当前句滚到视口中间；设置/排版面板可随时切回传统翻页
- **排版面板实时预览**：调字号/行距/边距/字体时面板内直接显示效果样张，不再需要关闭面板看图
- **简洁主题**（默认）：书架/统计/设置顶栏改为纯色无渐变；喜欢极光风格可在设置里关掉「简洁主题」
- 移除了会累积行高的自定义段距 span（上一版根因修复），段落分隔回归源文本空行

### 签名与覆盖安装
每次 CI 运行都是全新虚拟机，AGP 默认会现场生成**随机** debug 密钥——每版签名不同就没法覆盖安装。现在用仓库内固定密钥 `keystore/debug.keystore`（CI 缺失时自动生成并提交回仓库，本地Clone后由 `build.gradle.kts` 兜底生成），所有构建同一签名，可直接覆盖升级、保留数据。debug 密钥密码是公开的 `android`，仅用于测试分发，可随时弃用重置。

### 抓日志排查排版问题
阅读页在排版/分页后会把关键数据打到 logcat（tag `OceanReader`）：解析出的样式（字号/行距/段距em/边距/字体）、章篇统计（字符数/行数/各类块数量）、分页结果（页数/内容高度/布局高度/每页字符跨度/首行行高）。复现问题后执行 `logcat -d | grep OceanReader` 即可导出诊断数据。

一款参考 ColorOS 17 设计语言的 Android 阅读应用，支持多格式文档导入、系统 TTS 朗读跟读、笔记高亮等完整阅读功能。

## 功能

### 文档格式
| 格式 | 支持情况 |
|------|----------|
| TXT / Markdown / RTF / HTML | ✅ 完整支持，自动检测编码（UTF-8 / GB18030 / Big5 / UTF-16） |
| EPUB | ✅ 解析 OPF/NCX/nav，提取目录和封面 |
| MOBI / AZW / AZW3 | ✅ 支持 PalmDOC 压缩，提取元数据 |
| PDF | ✅ PdfRenderer 渲染 + 文本层提取（支持 FlateDecode / LZW / ASCII85 等） |
| DOCX | ✅ 解析 OOXML，识别标题样式生成目录 |
| DOC (Word 97-2003) | ✅ 解析 OLE2 复合文档 + 段表提取文本 |
| FB2 / FBZ | ✅ 解析 FictionBook XML |

### 朗读（TTS）
- 系统 TTS 引擎，支持中文/英文
- **跟读高亮**：句子级高亮 + 单词级高亮（依赖引擎的 `onRangeStart` 回调）
- 语速 / 音调 / 发音人调节
- 定时停止（15/30 分钟）
- 前台服务 + 媒体通知控制
- 自动下一章

### 阅读体验
- 翻页动画（滑动 / 覆盖 / 无动画）
- 页面底色：纸白 / 羊皮 / 护眼绿 / 纯黑 / 墨蓝等
- 字体 / 字号 / 行距 / 段距 / 页边距 / 首行缩进可调
- 亮度调节（含夜间模式）
- 阅读进度自动保存

### 书架
- 网格 / 列表切换
- 搜索（书名 / 作者）
- 多选操作（删除 / 置顶）
- 继续阅读卡片

### 笔记与标注
- 高亮（多色）
- 下划线
- 笔记（想法记录）
- 书签
- 全文搜索

### 统计
- 每日阅读时长统计
- 总阅读天数 / 总字数

## 构建

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android SDK 34+
- Gradle 8.9+

### 构建步骤
1. 用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成
3. 连接设备或启动模拟器（API 24+）
4. 点击 Run ▶

### 依赖
- Jetpack Compose (BOM 2024.12.01)
- Material 3
- Room 2.6.1
- DataStore Preferences
- Coil 2.7.0
- AndroidX Media (MediaSession)

## 设计说明

### ColorOS 17 设计语言
- **超椭圆形状**：通过 `SuperShape` 自定义 Shape 实现（指数 n=4.2~4.6）
- **毛玻璃效果**：`glassPanel` 修饰符实现半透明 + 颗粒噪点 + 边缘高光
- **极光渐变**：强调色使用蓝紫/青蓝/橙粉等渐变
- **圆角系统**：统一使用大圆角（16dp~28dp）

### UI 架构规范（`ui/components/SharedComponents.kt`）
所有页面共用同一套布局令牌与控件，按钮的位置、大小、调用方式完全一致：

| 令牌 | 值 | 用途 |
|------|----|----|
| `AppSpacing.xs/sm/md/lg/xl/xxl` | 4/8/12/16/20/24dp | 页面间距（4dp 网格） |
| `AppSpacing.screen` | 20dp | 屏幕左右安全边距 |
| `AppSize.iconButton` | 40dp | 图标按钮视觉尺寸（触摸区 48dp） |
| `AppSize.touchTarget` | 48dp | 最小触摸目标 |
| `AppSize.fab` | 56dp | 悬浮操作按钮 |

共享组件：`AuroraTopBar`（毛玻璃顶栏，支持副标题）、`ListRow` / `SettingRow`（图标+标题+副标题+尾部控件的标准行）、`SectionHeader`（分区标题）、`SheetHeader`（底部面板标题+拖拽条）、`AppFilterChip`、`StatTile`、`EmptyState`、`AuroraIconButton`、`GlassBottomBar`。

布局约定：
- **书架**：大标题区（极光渐变底 + 书名计数 + 统计/设置入口）→ 工具栏（搜索 / 排序下拉 / 网格列表切换）→ 继续阅读卡片 → 书架内容；右下角 FAB 唤出导入选择面板（文件 / 文件夹）
- **阅读器**：顶栏只放「返回 / 目录 / 更多」（其余功能在更多菜单里）；底栏放进度条 + 上一条 + 朗读 + 下一条 + 目录；点击屏幕左/右 1/4 区域翻页，点击中部唤出/隐藏菜单；长按选中句子后可高亮 / 下划线 / 复制 / 写笔记
- **设置 / 统计**：`SectionHeader` 分区 + `ListRow` 行 + `SegmentedButton` / `Switch` / `Slider` 尾部控件

### 架构
- MVVM + Repository 模式
- Room 持久化书架 / 标注 / 统计
- DataStore 存储阅读偏好
- 手写 PDF 解析器（避免重型第三方依赖）

### 阅读页排版架构（参考微信读书排版引擎）
微信读书的引擎是「预处理 DOM/CSS → 文本流 + 样式区间（RangeTree）→ 分页工厂」，本项目用更轻的方式落地：

```
解析器(body 纯文本) ──► ReaderTypesetter(块流+样式区间) ──► TextPaginator(分页) ──► TextPageView(绘制)
```

- `ReaderTypesetter` 按行把章节识别为四种块：章节标题（1.5x 加粗）、文内小标题（1.28x 加粗）、正文段落（首行缩进）、代码块（等宽 + 淡底色，不缩进）
- 段间距/标题间距通过自定义 `BlockSpacingSpan`（`LineHeightSpan`）加在块的首尾字符上——只影响行高、**不改变文本长度**，因此 TTS 偏移、高亮、书签、搜索偏移全部继续有效
- 所有格式通用（TXT/EPUB/MOBI/PDF 的「第 3 章」标题行同样会被放大）；Markdown 的 ATX 标题与代码围栏由启发式识别
- 与 Readori 排版规范对齐：中文行高下限 1.35、首行缩进按字号换算、分页与渲染使用同一内容宽度（不会出现“分页减一次边距、显示再减一次”的不齐）
- **段距按 em 相对字号计算**（默认 0.4em）：固定 dp 段距叠在行距上会让行 pitch 达到 2.6em，这是“行间距太大”的根因；分级间距：标题前 0.3em/后 1.2em，小标题前 0.3em/后 0.6em，正文段后 0.4em，代码前 0.5em/后 0.7em
- **拖拽偏移安全复位**：手势被取消（来电、抢焦、快速划动被拦截）时偏移量曾被残留并累加，导致越往后翻每页越少行；现在取消即复位、翻页提交后也强制复位
- **段落 = 空行分隔的连续行**（不是每行一段）：这是「一页只有几行、越翻越稀」的正解——每行若都按段落处理，软换行/ EPUB `<br>` 的每一行都会吃到一份首行缩进和段距。缩进 span 只贴首行，段距只贴段尾
- 可调：字号 / 行距 / 段间距 / 页边距 / 首行缩进 / 加粗 / 两端对齐 / 字体 / 页面底色，全部即时生效

### PDF 显示（裁白边 + 包含式铺满）
- 按页面自身宽高比渲染（不变形），**自动检测并裁掉页面白边**（裁白边，~240 点采样，几毫秒），再把裁后的页面以“包含式”缩放居中——**永不裁内容、永不左右突出**
- 深色底面对 PDF 反色；左右不再“突出”是因为放弃了 cover 裁切，改用裁白边把屏幕面积挣回来
- **PDF 章/页导航**：目录按 4 页一组，点击目录跳到该组起始页（原来一律跳第 0 页）；「上一章」落在上一章末页（原来跳到全书最后一页）；章长度按下一章起始页推导，不再硬编码 8

### 导入流程
- 单文件 / 多选（`OpenMultipleDocuments`）/ 整个文件夹（`OpenDocumentTree` 递归扫描，上限 500 个）
- 也支持从其它应用「分享到潮阅」或「打开方式」（SEND / SEND_MULTIPLE / VIEW）
- 批量导入为顺序执行（单个文档解析需常驻内存，并发有 OOM 风险），实时显示进度（第 n/N 个 + 文件名），失败不影响其余文件，结束后 Snackbar 汇报成功/失败数量
- 文件复制进应用私有目录（`files/books`），不依赖原文件持续存在

### PDF 与短章节处理（参考微信读书 / 本机阅读 App 的逆向验证）
- **PDF 全屏**：按页面宽高比渲染位图（填满屏幕的宽或高，另一边跟随页面比例，不变形），再在画布上居中并把溢出部分裁掉——平台 `PdfRenderer` 只保证“铺满整张位图”，且 `destinationClip` 不允许越界（否则抛 `IllegalArgumentException` 白屏）
- **深色底色**：对 PDF 位图反色，夜间看扫描版不刺眼
- **短章节合并**（`ChapterSplitter.mergeUp`）：EPUB 每个 spine 文件、DOCX 每个小标题段都可能只有几行，一章占一页即“一页几行”。合并阈值 1200 字，首章标题保留，字符偏移不受影响（进度/高亮/TTS 全部继续有效）。微信读书的做法是全书连续排版（章节只是流里的标题），合并是同一效果在“按章分页”架构下的等价实现
- 微信读书 dex 实证：`TypesetSetting` + `TypesetCharSize`（字号）+ `TypesetGlue`（间距胶水）+ `textIndent` + `PageFactory`，与上面的块流架构一致

## 已知限制
- PDF 扫描版无文本层时无法朗读（需 OCR）
- MOBI HUFF/CDIC 压缩（类型 17480）暂不支持，建议用 Calibre 转换
- 暂不支持 EPUB 内嵌字体的完全还原

## 项目结构
```
app/src/main/java/com/oceanreader/
├── MainActivity.kt          # 入口 + 导航
├── ReaderApp.kt             # Application + AppContainer (DI)
├── data/                    # 数据层
│   ├── db/                  # Room 数据库
│   ├── model/               # 数据模型
│   ├── BookRepository.kt    # 书籍仓库
│   ├── BookImporter.kt      # 导入器
│   └── SettingsStore.kt     # DataStore 偏好
├── parse/                   # 文档解析
│   ├── EpubParser.kt
│   ├── MobiParser.kt
│   ├── DocParser.kt
│   ├── OoxmlParser.kt
│   ├── Fb2Parser.kt
│   ├── TextParsers.kt
│   ├── pdf/                 # PDF 引擎
│   └── DocumentFactory.kt   # 统一入口
├── reader/                  # 阅读器内核
│   ├── TextPaginator.kt     # 分页器
│   ├── ReaderTextView.kt    # 文本渲染 View
│   └── ReaderViewModel.kt   # 阅读状态管理
├── tts/                     # 语音朗读
│   ├── SpeechController.kt  # TTS 控制器
│   ├── SpeechService.kt     # 前台服务
│   └── SentenceSegmenter.kt # 句子分割
└── ui/                      # 界面层
    ├── theme/               # 设计系统
    ├── components/          # 共享组件
    ├── shelf/               # 书架页
    ├── reader/              # 阅读页
    ├── settings/            # 设置页
    └── stats/               # 统计页
```