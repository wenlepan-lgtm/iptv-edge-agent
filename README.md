# IPTV Edge Agent

运行在酒店 IPTV（SmartTV & STB）设备上的本地语音智能体系统。

## 核心功能

- **离线语音识别 (ASR)**：基于 sherpa-onnx 框架实现，完全不依赖云端，保护用户隐私。
- **离线语音合成 (TTS)**：基于 Piper TTS 引擎实现，支持本地化语音播报。
- **3D 数字人交互 (DUIX)**：集成 DUIX SDK 实现 3D 数字人渲染与交互。
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
- **ASR 引擎**：sherpa-onnx (使用 `sherpa-onnx-whisper-...` 或其他具体模型)。
- **TTS 引擎**：Piper TTS (通过 `com.k2fsa.sherpa.onnx.tts.engine` 包)。
- **数字人 SDK**：DUIX SDK。

## 快速开始

1. **环境要求**：
   - 已 Root 的 Android TV 或机顶盒。
   - 视频文件路径：`/sdcard/1.mp4`。

2. **模型准备**：
   - 下载 sherpa-onnx ASR 模型。
   - 确保设备上已安装 Piper TTS 引擎。

3. **配置说明**：
   - 复制 `app/src/main/assets/config.properties.template` 为 `config.properties`。
   - **重要**：请在 `config.properties` 文件中填入您的 `web.api.key` (用于 LLM 服务，如 qwen-turbo)。此文件不会被提交到 Git 仓库。

4. **编译运行**：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## 最新改进 (v2.0.0 - 2025-01-01)

### 技术栈更新
- **ASR 框架**：从 Vosk 迁移至 sherpa-onnx，提升识别性能与稳定性。
- **TTS 引擎**：采用 Piper TTS 实现离线语音合成。
- **数字人 SDK**：集成 DUIX SDK，实现 3D 数字人功能。
- **文档更新**：同步更新 README.md 以反映当前技术架构。