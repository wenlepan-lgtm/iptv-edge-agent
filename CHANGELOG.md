# Changelog

All notable changes to this project will be documented in this file.

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