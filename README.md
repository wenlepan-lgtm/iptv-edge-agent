# IPTV Edge Agent

运行在酒店 IPTV（SmartTV & STB）设备上的本地语音智能体系统。

## 核心功能

- **离线语音识别 (ASR)**：基于 Vosk-Android 实现，完全不依赖云端，保护用户隐私。
- **实时流式处理**：通过 FIFO 命名管道实时读取 `tinycap` 音频流，实现零延迟识别。
- **多通道音频优化**：支持 8 通道麦克风阵列，可灵活切换声道并调整增益（Gain）。
- **离线语音合成 (TTS)**：集成 sherpa-onnx-zh_CN-huayan 模型实现 TTS 功能。
- **2D 数字人交互 (DUIX)**：集成 DUIX SDK 实现 2D 数字人渲染与交互。
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
- **TTS 引擎**：sherpa-onnx (使用 `sherpa-onnx-zh_CN-huayan` 模型)。
- **数字人 SDK**：DUIX SDK。
- **通信机制**：Linux FIFO (Named Pipe)。

## 快速开始

1. **环境要求**：
   - 支持未Root 的 Android TV 设备。
   - 终端支持 `tinycap` 命令。
   - 视频文件路径：`/sdcard/1.mp4`。

2. **模型准备**：
   - 下载 Vosk 中文模型并解压到设备 SD 卡：
     - `/sdcard/vosk-model-small-cn-0.22/`

3. **配置说明**：
   - 复制 `app/src/main/assets/config.properties.template` 为 `config.properties`。
   - **重要**：请在 `config.properties` 文件中填入您的 `web.api.key` (用于 LLM 服务，如 qwen-turbo)。此文件不会被提交到 Git 仓库。

4. **编译运行**：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## 最新改进 (v2.0.0 - 2025-12-24)

### 技术栈更新
- **ASR 框架**：采用 Vosk 框架，使用 `vosk-model-small-cn-0.22` 模型。
- **TTS 引擎**：采用 sherpa-onnx 框架，使用 `zh_CN-huayan` 模型。
- **数字人 SDK**：集成 DUIX SDK，实现 2D 数字人功能。
- **文档更新**：同步更新 README.md 以反映当前技术架构。