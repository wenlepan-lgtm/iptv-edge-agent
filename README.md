# IPTV Edge Agent

运行在酒店 IPTV（SmartTV & STB）设备上的本地语音智能体系统。

## 核心功能

- **离线语音识别 (ASR)**：基于 Vosk-Android 实现，完全不依赖云端，保护用户隐私。
- **实时流式处理**：通过 FIFO 命名管道实时读取 `tinycap` 音频流，实现零延迟识别。
- **多通道音频优化**：支持 8 通道麦克风阵列，可灵活切换声道并调整增益（Gain）。
- **系统实时监控 (SYSTEM MONITOR)**：
  - **CPU**: 实时显示使用率、核心数及主频 (rk3576 8 Processor 2208MHz)。
  - **MEM**: 系统总内存占用。
  - **DISK**: 磁盘空间监控 (/data 分区)。
  - **NET**: 实时网卡流量，区分入流量 (IN) 和出流量 (OUT)，单位 KB/s，保留 2 位小数。
- **广播日志 (BROADCAST LOG)**：
  - 严格遵循标准 JSON 格式输出。
  - 支持 4 空格缩进，显示完整嵌套结构。
  - 自动触底滚动，确保最新日志实时可见。
- **TV 优化 UI**：
  - 专为电视大屏设计的布局，所有监控项严格左对齐。
  - 底部区域（视频、LLM 日志、广播日志）深度延伸，充分利用屏幕空间。

## 技术细节

- **语言**：Kotlin
- **音频采集**：通过 Root 权限调用 `tinycap` 绕过系统音频框架限制。
- **识别引擎**：Vosk (使用 `vosk-model-small-cn-0.22`)。
- **通信机制**：Linux FIFO (Named Pipe)。

## 快速开始

1. **环境要求**：
   - 已 Root 的 Android TV 或机顶盒。
   - 终端支持 `tinycap` 命令。
   - 视频文件路径：`/sdcard/1.mp4`。

2. **模型准备**：
   - 下载 Vosk 中文模型并解压到设备 SD 卡：
     - `/sdcard/vosk-model-small-cn-0.22/`

3. **配置说明**：
   - 复制 `app/src/main/assets/config.properties.template` 为 `config.properties`。
   - 填入您的 `web.api.key` (qwen-turbo)。

4. **编译运行**：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## 最新改进 (v1.4.0 - 2025-12-23)

### UI 与监控专业化提升
- **SYSTEM MONITOR 规范化**：
  - 实现了严格左对齐布局，取消分栏，所有信息在同一垂直基准线上。
  - NET 流量拆分为 IN/OUT，刷新率提升至 1 秒。
  - CPU 型号修正为通过 `ro.board.platform` 获取 (rk3576)。
- **BROADCAST LOG 格式化**：
  - 实现了 4 空格缩进的标准 JSON 结构。
  - 修复了顶部行丢失问题，确保从 `{` 开始完整显示。
  - 优化了滚动逻辑，解决了长 JSON 块的截断问题。
- **视频播放修复**：
  - 路径锁定为 `/sdcard/1.mp4`，完善了权限请求和错误捕获逻辑。
- **安全增强**：
  - 将 `config.properties` 加入 `.gitignore`，防止 API Key 泄露。

### 语音交互与防死循环优化 (v1.5.0 - 2025-12-23)
- **TTS 防死循环**：
  - 实现了 ASR/TTS 半双工抑制机制，TTS 播放期间自动静默 ASR，防止自触发闭环。
  - 引入 `asrMuted` 状态标志，确保 TTS 播放不会被误识别为用户语音。
- **音频效果器集成**：
  - 集成系统级 `AEC` (回声消除)、`NS` (噪声抑制) 和 `AGC` (自动增益控制)。
  - 优化了语音识别在复杂环境下的准确率和鲁棒性。
- **TTS 路由修复**：
  - 强制 TTS 引擎使用 `USAGE_MEDIA` 音频流，解决 Android 14 平台 `Strategy 9` 路由缺失问题。
  - 增加了引擎初始化异常保护和延迟初始化逻辑，提升稳定性。
- **UI 微调**：
  - VIDEO 区域默认不自动播放。
  - STATUS 区域显示 ASR 初始化状态和模型加载耗时。
  - LLM LOG 区域集成 TTS 状态日志，便于统一追踪。