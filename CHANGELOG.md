# Changelog

All notable changes to this project will be documented in this file.

## [1.5.0] - 2025-12-23

### Added
- TTS 防死循环机制：实现 ASR/TTS 半双工抑制，TTS 播放期间自动静默 ASR，防止自触发闭环
- 音频效果器集成：集成系统级 AEC (回声消除)、NS (噪声抑制) 和 AGC (自动增益控制)
- TTS 路由修复：强制 TTS 引擎使用 USAGE_MEDIA 音频流，解决 Android 14 平台 Strategy 9 路由缺失问题
- UI 微调：VIDEO 区域默认不自动播放，STATUS 区域显示 ASR 初始化状态和模型加载耗时，LLM LOG 区域集成 TTS 状态日志

### Changed
- 优化 ASR 状态机，引入 asrMuted 状态标志
- 增强 TTSManager，增加引擎初始化异常保护和延迟初始化逻辑
- 调整 MainActivity，集成 TTS 日志到 LLM LOG

### Fixed
- 修复 TTS 播放被 ASR 误识别导致的死循环问题
- 解决 Android 14 平台 TTS 无声问题

## [1.4.0] - 2025-12-23

### Added
- SYSTEM MONITOR 严格左对齐布局
- BROADCAST LOG 4 空格缩进标准 JSON 格式
- 视频播放路径锁定为 /sdcard/1.mp4
- 安全增强：将 config.properties 加入 .gitignore

### Changed
- 优化 UI 布局，取消分栏，实现信息严格对齐
- 改进 BROADCAST LOG 滚动逻辑，解决截断问题
- 调整 CPU 型号获取方式为 ro.board.platform

### Fixed
- 修复 BROADCAST LOG 顶部行丢失问题
- 修复视频播放权限和错误捕获逻辑

## [1.3.0] - 2025-12-21

### Added
- M1 语音体验工程化完成
- 完整的 ASR 状态机实现 (IDLE → LISTENING → FINAL → COOLDOWN)
- Partial 回调工程化 (节流≥200ms、非空且变化时才回调)
- Final 触发规则 (边沿触发、静音持续≥800ms)
- Finalize 行为规范 (调用 finalResult、清空 lastPartial、设置 COOLDOWN 状态)
- 详细的可验收日志格式

### Changed
- 重构 AsrController，实现完整的状态管理和回调控制
- 优化 VAD 算法，使用动态阈值计算
- 改进音频处理流程，增加预热机制

### Fixed
- 修复状态机逻辑错误，正确初始化 lastVoiceTime
- 解决 COOLDOWN 期间仍有回调的问题
- 修复 recognize.reset() 实现

## [1.2.0] - 2025-12-20

### Added
- ASR 稳定性大幅提升
- 录音预热机制，丢弃前 500ms 音频避免脏词
- 动态 VAD/静音阈值：自动采样环境噪声并计算最优阈值
- 改进的边沿触发 finalize 逻辑，防止重复触发
- 优化的回调机制，减少不必要的 UI 刷新

### Changed
- 预热时间：500ms
- 噪声采样时间：1000ms
- 静音检测阈值：max(noiseRms*3, 120)
- 静音超时：800ms

## [1.1.0] - 2025-12-19

### Added
- 完成 ASR 链路的完全跑通
- 支持实时语音转文字并显示在 UI 上
- 实现暂停/恢复录音功能

### Fixed
- 修复 acceptWaveForm 参数传递问题
- 修正静音检测逻辑
- 实现 recognizer.reset() 方法

## [1.0.0] - 2025-12-18

### Added
- 初始版本发布
- 离线语音识别 (ASR) 功能
- 实时流式处理
- 多通道音频优化
- 系统实时监控 (CPU/MEM/DISK/NET)
- TV 优化 UI