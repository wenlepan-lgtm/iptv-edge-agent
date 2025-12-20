# 更新日志

## [v1.2.0] - 2025-12-20
### 改进
- 实现录音预热机制，丢弃前 500ms 音频避免脏词
- 实现动态 VAD/静音阈值计算，自动采样环境噪声并计算最优阈值
- 改进 finalize 边沿触发逻辑，防止重复触发
- 优化 UI/回调逻辑，减少不必要的 UI 刷新
- acceptWaveForm 参数修正为使用 byteArray.size

## [v1.1.0] - 2025-12-19
### 改进
- 修复 ASR 识别准确率
- 实现连续语音识别模式
- 添加暂停/恢复录音功能
- 改进音频源切换逻辑：MIC -> CAMCORDER -> VOICE_COMMUNICATION -> VOICE_RECOGNITION（fallback）

## [v1.0.0] - 2025-12-18
### 新增
- 初始版本发布
- 基础 ASR 功能
- 离线语音识别 (ASR) 基于 Vosk-Android 实现
- 实时流式处理通过 FIFO 命名管道读取 `tinycap` 音频流
- 多通道音频优化支持 8 通道麦克风阵列
- 系统实时监控 (CPU、内存、磁盘、网络)
- TV 优化 UI 专为电视大屏设计