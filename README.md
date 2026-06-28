# 安卓自动滑动器APP [AutoSlide]

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Version](https://img.shields.io/badge/Version-2.5.1-green)](https://github.com/tianxing-ovo/AutoSlide/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tianxing-ovo/AutoSlide/total?cacheSeconds=86400)](https://github.com/tianxing-ovo/AutoSlide/releases)
[![Latest Downloads](https://img.shields.io/github/downloads/tianxing-ovo/AutoSlide/latest/total?cacheSeconds=86400)](https://github.com/tianxing-ovo/AutoSlide/releases/latest)

[简体中文](README.md) | [English](README.en.md)

一款简洁高效的安卓设备自动滑动解决方案，适用于自动化测试、内容浏览等场景

## 功能特性

- **定时滑动**：用户可自定义滑动的时间间隔
- **停顿模式**：支持"不停顿"、"固定时间"及"随机时间"三种停顿模式，并支持手动输入自定义停顿时间；随机模式可设置最小和最大停顿时间范围
- **滑动速度**：支持多种滑动速度设置，并基于更平滑的速度曲线调整手势时长
- **滑动方向**：支持上、下、左、右四个方向的滑动
- **悬浮窗控制**：便捷的悬浮球控制，滑动开始后自动收缩为小图标，点击小图标即可展开面板并自动停止滑动
- **通知栏快捷磁贴**：新增系统下拉通知栏快捷磁贴（Quick Settings Tile），支持一键快捷开启或关闭悬浮窗服务，状态双向同步
- **权限管理**：新增悬浮窗权限控制开关，可快速跳转至系统权限设置页面
- **音量键强停**：滑动运行中按音量键可立即停止滑动并恢复悬浮窗面板
- **息屏自停**：屏幕关闭时（包括按电源键）自动停止滑动，防止无效滑动浪费资源
- **手势模拟**：精准模拟手指滑动手势，手势坐标会基于屏幕尺寸动态计算
- **连续滑动优化**：不停顿模式下内置极小间隔，兼顾连续性与稳定性
- **智能权限管理**：支持手动开启、Shizuku 授权及 ADB 授权等多种方式，自动开启无障碍服务更便捷
- **简单易用**：直观的用户界面，轻松设置滑动参数

## 最近更新

- `v2.5.1`
  - 优化系统向下兼容性，最低支持版本降至 Android 8.0 (API 26)
  - 修复低版本系统上由于获取版本号 (longVersionCode) 导致运行崩溃的问题
  - 优化深色模式主题在部分系统版本上的样式表现并解决 Lint 审查警告
- `v2.5`
  - 新增系统下拉通知栏快捷磁贴（Quick Settings Tile），支持从状态栏一键快捷启动/停止悬浮窗服务
  - 磁贴状态与悬浮窗服务生命周期（如悬浮球内关闭或服务异常退出）实时双向同步，确保 UI 状态一致
  - 完美适配 Android 14+ (API 34+) 关于从背景拉起 activity 必须使用 PendingIntent 的安全规范，解决高版本闪退问题，同时保留低版本兼容
  - 全面清理冗余资源和隐式广播设计，顺利通过 Android Lint 的全部安全和风格审查
- `v2.4`
  - 优化悬浮窗交互体验，支持在展开状态下面板任意位置（包括按钮上）直接拖拽移动，完美解决触控拦截冲突
  - 修复自定义悬浮窗容器在 Android 无障碍环境下的 performClick 规范化警告，增强系统兼容性与合规性
  - 升级核心随机数生成器为 SecureRandom 安全随机算法，彻底解决 SonarQube 安全审计热点 (kotlin:S2245)
## 截图展示

![](assets/screenshot.png)

## 快速开始

### 前提条件

- Android 8.0及以上版本

### 安装步骤

1. 下载最新的APK文件从[发布页面](https://github.com/tianxing-ovo/AutoSlide/releases/)
2. 打开下载的APK文件并按照屏幕上的指示安装应用程序
3. 启动应用，并授予必要的权限以确保其正常运行

## 贡献代码

我们欢迎任何形式的贡献，包括但不限于bug报告、功能请求、pull
requests等

## 许可证

本项目基于 Apache License 2.0 许可证，详见 [LICENSE](LICENSE) 文件
