# 潮阅 (OceanReader)

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

### 架构
- MVVM + Repository 模式
- Room 持久化书架 / 标注 / 统计
- DataStore 存储阅读偏好
- 手写 PDF 解析器（避免重型第三方依赖）

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