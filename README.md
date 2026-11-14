# Q管理 (QFileManager)

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_34-brightgreen?logo=android" alt="Android Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin_1.9-blue?logo=kotlin" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-purple?logo=jetpackcompose" alt="Compose M3" />
  <img src="https://img.shields.io/badge/Protocol-LocalSend_v2-ff69b4?logo=airplayvideo" alt="LocalSend Protocol" />
  <img src="https://img.shields.io/badge/Media-FFmpeg--Kit-orange?logo=ffmpeg" alt="FFmpeg" />
  <img src="https://img.shields.io/badge/License-MIT-green" alt="MIT License" />
</p>

**Q管理** 是一款基于 **Kotlin + Jetpack Compose (Material 3)** 全新重构的现代 Android 智能文件管理器。集成了六大分类深度管理、离线 **M3U8 批量秒级无损转 MP4**、开源 **LocalSend 局域网高速互传**、**SMB1/2/3 网络共享存储**、**小米等品牌手机私密隐藏文件嗅探与恢复**、**微信存储精准深度扫描与清理**、**全盘重复文件秒级筛查**，以及内置**多帧轮播动态预览**与**沉浸式全屏音视频播放器**。

---

## 🌟 核心特性一览

### 1. 微信占用准确扫描与专清 (WeChat Storage Cleaner)
针对微信长期使用导致的存储空间严重膨胀（聊天记录附件、缓存、残留副本）提供精准的算法级分析与治理：
- 🔍 **根目录与弃用文件深度嗅探**：深度扫描 `/sdcard/Android/data/com.tencent.mm/` 与 `/sdcard/tencent/MicroMsg/`，自动发掘散落在深层目录中的 `.tmp`、崩溃转储日志、废弃中间件缓存及无用残留文件。
- 🎯 **文件名与文件大小精准校对比对**：智能分析同名且同大小的冗余副本，多维特征归组归类。
- 🕒 **默认保留最新（最晚）版本**：针对重复的文件副本，**算法默认保留修改时间最新的原件**，自动勾选历史旧副本待删，杜绝误删最新重要工作文档或生活图片。
- ⚡ **一键安全批量清理**：实时统计可释放体积，支持一键清空选中项并同步刷新系统存储，轻松释放几十 GB 宝贵空间。

---

### 2. 小米等手机隐藏私密文件深度嗅探、分类筛选与恢复/删除 (Hidden File Recovery & Management)
专为 MIUI / HyperOS 及常见 Android 设备设计，解决加密相册文件丢失、系统隐藏或误加点前缀无法查阅的问题：
- 🏷️ **全分类横向滑动筛选与实时计数**：支持按【照片、视频、音乐、文档、压缩包、其他】全维度分类筛选，每个标签实时显示隐藏文件数量，一键快速定位目标。
- 🕵️ **深度穿透私密目录**：全盘递归扫描小米私密相册（`/MIUI/.secretAlbum/`、`MIUI/Gallery/cloud/secretAlbum/` 等）以及全盘 `.nomedia`、`.` 开头的隐藏目录与文件。
- 🧬 **魔数 (Magic Number) 真实格式识别**：即使隐藏文件被去除后缀名或混淆，直接读取文件头十六进制魔数指纹，精准还原识别为 JPEG, PNG, GIF, WEBP, MP4, MKV, MP3 等真实格式，并自动补全正确扩展名。
- 🔄 **一键安全恢复为常规文件**：支持批量或单项去除隐藏标记，安全迁移至公开媒体目录（如 `Pictures/Restored/`、`Movies/Restored/`），并自动联动 `MediaScannerConnection` 刷新系统媒体库，让丢失的照片/视频在系统相册中即刻正常呈现。
- 🗑️ **隐藏文件批量与单项安全删除**：针对废弃或隐私无用的隐藏缓存文件，支持多选批量删除及单项彻底删除（带防误触二次确认），原地移除并释放存储空间。

---

### 3. 全局多媒体画面预览与视频多帧轮播 (Universal Preview & Video Carousel)
告别传统文件管理器枯燥的死板图标，让所有媒体文件一目了然：
- 🎞️ **独家视频多关键帧轮播预览**：文件列表中遇到视频时，后台采用 `MediaMetadataRetriever` 智能抽取视频 **12%、38%、65%、88%** 四个代表性时间节点关键帧，在缩略图区域以 950ms 为周期淡入淡出循环播放，**无需点开视频即可直观感知整部影片的精彩画面**。
- 🖼️ **全场景真实缩略图集成**：分类列表、重复文件排查、隐藏文件恢复全量接入通用视觉缩略图，支持高清图片缩略图、真实提取未安装 APK 的应用原版高清图标、音频专辑封面等。
- 🛡️ **内存位图池与协程限流防卡死**：内置 `LruCache` 内存复用池，配合 `Semaphore(2)` 严格限制后台解码并发数，飞速滑动列表依然保持 60/120Hz 丝滑流畅，彻底杜绝主线程冻结与 OOM 异常。

---

### 4. M3U8 批量离线缓存嗅探与秒级无损转 MP4 (M3U8 Batch Converter)
针对手机各类浏览器（夸克、UC、QQ浏览器、Kiwi、Xunlei等）和离线视频 App 碎片化缓存切片，提供全自动无损合并恢复：
- 🔍 **全盘深度嗅探**：自动递归排查各大离线缓存目录，支持识别标准 `.m3u8` 清单。
- 🧩 **无清单自然排序算法**：即使没有 m3u8 文件，仅有一堆散乱的 `0.ts`, `1.ts`, `2.ts` ... 切片，也能通过自然数字排序算法（Natural Order Numeric Sorting）动态重组为标准清单。
- ⚡ **FFmpeg Direct Stream Copy 极速直出**：采用 `-c copy -bsf:a aac_adtstoasc -movflags +faststart` 参数，不进行二次重编码耗损，纯解封装合并，数秒即可完成大文件直出，画质音质 100% 原始保留。
- 🧹 **一键【修复并删切片】（零二次确认）**：提供专门的一键转换并自动清理按钮，转换成功后**直接静默删除原始 TS 切片与清单文件**，无需繁琐二次确认，转换完即刻释放空间。
- 📲 **系统相册自动同步**：合成的 MP4 文件自动归入 `Movies/M3U8Converted/`，自动通知系统媒体库，相册即刻可见。

---

### 5. 极速流畅的文件列表体验 (Keep State & In-Place Operations)
- 📌 **文件浏览操作彻底保持滚动状态（绝不跳到顶部）**：彻底重构列表状态驱动模型与 Compose 组合生命周期，在本地与 SMB 共享目录中执行**文件重命名、删除、新建文件夹、剪切粘贴**等任意文件操作时，均通过 StateFlow 原地响应式增删改，绑定持久 `LazyListState`。即使在后台静默刷新时也保持列表挂载（仅在顶部展示轻量微进度条），**无论滚动到列表第几千项，操作后严格保持原滚动位置，彻底告别回到第 0 项的烦恼**。
- ✏️ **预览沉浸式直接重命名 (In-Preview Rename)**：在**图片全屏画廊预览**、**视频/音频普通窗口播放**及**全屏沉浸式播放**控制栏右上角均内置一键【重命名】按钮，无需退出播放或预览界面即可直接给媒体文件改名，底层物理文件或远程 SMB 文件秒级同步更名，父级列表实时原地联动。
- 🚀 **即时秒开与平滑增量加载**：打开分类目录时优先秒显内存缓存数据（告别空白等待），后台通过 Kotlin Flow `scanCategoryProgressive` 分批增量注入新数据，加载完成后平滑更新。

---

### 6. SMB 局域网网络共享 (SMB1 / SMB2 / SMB3 极速稳固版)
内置 **CodeLibs JCIFS** 协议引擎，支持直连 Windows、macOS、Linux Samba、OpenWrt 及各类 NAS（群晖/威联通/极空间/飞牛等）：
- ⚡ **纯 IP 直连协议调优与 NetBIOS 广播消除**：识别局域网 IP 直连时自动剔除耗时的 NetBIOS 广播解析（`BCAST`），握手延迟降低 70% 以上；放大并发请求槽位 (`maxMpxCount = 128`, `listSize = 65535`)，提升大目录吞吐速度。
- 🧠 **60 级目录内存 LRU 高速缓存**：内置目录结构与文件属性内存缓存，用户点击进入已访问文件夹或返回上级目录时 **0 毫秒瞬间秒显**，后台静默校准，浏览体验与本机无异。
- 🛡️ **长连接失效与瞬断自动重连机制**：针对移动端 WiFi 切换、熄屏省电导致的 Socket 断开或传输超时，内部自动拦截并销毁失效上下文发起无感自愈重连，将连接稳定性提升至 99.9%。
- 🎬 **平滑暗黑沉浸式预览（防跳窗与零跳动）**：彻底优化 SMB 预览流程，移除了多余的过渡弹窗，点击媒体直接进入暗黑播放/查看器；播放顶栏网速布局优化为独立固定高度槽位，彻底消除网络加载与速率频繁变动引发的窗口拉伸抖动；图片画廊与媒体播放均支持直接远程重命名。
- ⚡ **纯非阻塞协程与消除主线程卡死**：重构 SMB 底层下载与上传流水线，彻底清除内部 `runBlocking` 调用，全面采用非阻塞 `withContext(Dispatchers.IO)`，超时与长连接中断安全自愈，彻底根治文件传输或预览时的偶发假死问题。
- 🛡️ **安全上传防误触**：选择本地文件上传到远程共享目录时，增加显式【确定上传】操作，避免误选直接触发大文件网络传输。
- 🌐 **本地 HTTP Range 代理在线点播**：通过 NanoHTTPD Range 流媒体代理，无需下载几十 GB 大文件，即点即播局域网 4K/HDR 影视，支持拖拽进度与全屏播放。
- 📂 **全功能远程文件管理**：支持面包屑路径跳转、多选批量操作、新建文件夹、原地重命名、复制剪切、递归删除与后台保活断点续传。

---

### 7. 开源 LocalSend 协议局域网跨端无损互传
原生兼容开源 [LocalSend 协议 (Protocol v2)](https://github.com/localsend/localsend)，可直接与局域网内的 Windows、macOS、Linux、iOS 以及 Android 官方 LocalSend 客户端无缝互传：
- 📡 **UDP 组播自动发现 (Multicast Discovery)**：监听与广播组播地址 `224.0.0.167:53317`，秒级雷达探测周围设备。
- 🚀 **NanoHTTPD 内置高速传输服务**：接收文件自动流式写入 `Download/LocalSend/`，防重名自增处理。
- 📤 **流式多文件并发推送客户端**：采用 OkHttp 流式推送，实时计算传输百分比与即时传输速率（MB/s），前台常驻服务保证息屏不中断。

---

### 8. 重复文件深度筛查与智能瘦身 (Duplicate Cleaner)
- 🚀 **三阶段极速精准比对算法**：
  - **Step 1 (大小粗筛)**：毫秒级分组过滤单文件，排除 95% 以上独立文件；
  - **Step 2 (头部指纹校验)**：读取前 8KB 快速校验快照指纹，高效排除偶然同大小文件；
  - **Step 3 (全量与采样哈希确认)**：针对小于 30MB 文件流式读入计算完整 MD5；针对 **大于 30MB 的超大文件采用头中尾 64KB 采样比对**，**彻底消灭扫描大文件时的系统卡死与 I/O 阻塞**，100% 兼顾准确率与极速体验。
- 🏷️ **智能区分原件与副本**：按真实修改时间自动将最早产生的文件标记为 `[原文件]`，其余标记为 `[副本]`，支持一键【保留最早】或【保留最新】。

---

### 9. 本机文件与软件深度清理 (Storage & App Cleaner)
- 🧹 **五大核心垃圾维度深度扫描**：应用系统临时缓存 (`.tmp`/`.bak`)、相册缩略图索引 (`.thumbnails`)、日志与崩溃转储 (`.log`/`.dmp`)、废弃 APK 安装包残留、无用空文件夹树递归清理。
- 📱 **已安装软件空间深度剖析**：全面统计已安装应用及其外部数据目录（`Android/data`、`Android/obb`、`Android/media`）占用情况，支持一键清空外部数据残留或唤起系统详情页清除缓存。

---

### 10. 高级音视频播放器 (内置自由拖拽与沉浸全屏)
- 🎚️ **进度条自由拖拽 (Seekbar)**：窗口与全屏模式下均支持手动拖拽进度跳转，精准定位目标帧。
- 🖥️ **彻底沉浸全屏与无黑边铺满**：穿透系统 Dialog 窗口边距，消除黑边，自适应各类挖孔/水滴全面屏，横竖屏旋转平滑流畅。

---

## 🛠️ 项目工程技术栈

| 模块 | 技术选型 | 说明 |
|---|---|---|
| 核心架构 | MVVM + StateFlow + Kotlin Coroutines | 异步非阻塞响应式架构 |
| UI 框架 | Jetpack Compose + Material 3 | 现代化声明式设计、动态主题 |
| 缩略图引擎 | Coil + Custom VideoFrameDecoder + LruCache | 多关键帧抽取轮播、并发限流防卡死 |
| 视频处理 | FFmpeg-Kit (Mobile-FFmpeg) | 本地多线程解封装与秒级无损合并 |
| 局域网传输 | LocalSend Protocol v2 (OkHttp + NanoHTTPD) | 开源跨平台无损局域网快传 |
| 网络共享 | CodeLibs JCIFS (SMB1/2/3) + Range Proxy | 局域网 SMB 文件管理与流式秒开 |
| 专清/去重引擎 | WeChatStorageCleaner + Multi-stage MD5 | 微信根目录弃用文件扫描、采样哈希去重 |
| 隐私恢复 | HiddenFileManager (Magic Number Detection) | 小米私密相册穿透、二进制魔数纠正与恢复 |
| 压缩解压 | Zip4j | 跨平台 ZIP/RAR 目录树与解压 |

---

## 📱 编译与运行环境

- **操作系统**: Android 8.0 (API Level 26) 及以上
- **推荐目标版本**: Android 14 (API Level 34)
- **编译工具**: Android Studio Hedgehog / Iguana / Ladybug+
- **Gradle 版本**: 8.9
- **Java / JDK**: OpenJDK 17

### 快速本地编译

```bash
# 克隆仓库
git clone https://github.com/qiwenkai209-dot/QFileManager.git
cd QFileManager

# 编译 Debug APK
./gradlew assembleDebug

# 生成的 APK 位于
# app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 开源许可证

本项目遵循 [MIT License](LICENSE) 许可协议开放源代码。
