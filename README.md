# IPTV Edge Agent

运行在酒店 IPTV（SmartTV & STB）设备上的本地语音智能体系统。

## 核心功能

- **离线语音识别 (ASR)**：基于 Vosk-Android 实现，完全不依赖云端，保护用户隐私。
- **实时流式处理**：通过 FIFO 命名管道实时读取 `tinycap` 音频流，实现零延迟识别。
- **多通道音频优化**：支持 8 通道麦克风阵列，可灵活切换声道并调整增益（Gain）。
- **系统实时监控**：
  - CPU 使用率
  - 系统总内存占用 (MEM)
  - 磁盘空间监控 (/data 分区)
  - 网络实时流量 (wlan0/eth0)
- **TV 优化 UI**：专为电视大屏设计的双区域显示（实时 ASR 过程 + 最终识别结果）。

## 技术细节

- **语言**：Kotlin
- **音频采集**：通过 Root 权限调用 `tinycap` 绕过系统音频框架限制。
- **识别引擎**：Vosk (使用 `vosk-model-small-cn-0.22`)。
- **通信机制**：Linux FIFO (Named Pipe)。

## 快速开始

1. **环境要求**：
   - 已 Root 的 Android TV 或机顶盒。
   - 终端支持 `tinycap` 命令。

2. **模型准备**：
   - 下载 Vosk 中文模型并解压到设备 SD 卡：
     - `/sdcard/vosk-model-small-cn-0.22/`

3. **编译运行**：
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## 阶段性成果

目前已完成 ASR 链路的完全跑通，支持实时语音转文字并显示在 UI 上。
