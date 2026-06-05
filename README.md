# 安卓自动滑动器APP [AutoSlide]

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Version](https://img.shields.io/badge/Version-2.4-green)](https://github.com/tianxing-ovo/AutoSlide/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/tianxing-ovo/AutoSlide/total)](https://github.com/tianxing-ovo/AutoSlide/releases)
[![Latest Downloads](https://img.shields.io/github/downloads/tianxing-ovo/AutoSlide/latest/total)](https://github.com/tianxing-ovo/AutoSlide/releases/latest)

[简体中文](README.md) | [English](README.en.md)

一款简洁高效的安卓设备自动滑动解决方案，适用于自动化测试、内容浏览等场景

## 功能特性

- **定时滑动**：用户可自定义滑动的时间间隔
- **停顿模式**：支持"不停顿"、"固定时间"及"随机时间"三种停顿模式，并支持手动输入自定义停顿时间；随机模式可设置最小和最大停顿时间范围
- **滑动速度**：支持多种滑动速度设置，并基于更平滑的速度曲线调整手势时长
- **滑动方向**：支持上、下、左、右四个方向的滑动
- **悬浮窗控制**：便捷的悬浮球控制，滑动开始后自动收缩为小图标，点击小图标即可展开面板并自动停止滑动
- **权限管理**：新增悬浮窗权限控制开关，可快速跳转至系统权限设置页面
- **音量键强停**：滑动运行中按音量键可立即停止滑动并恢复悬浮窗面板
- **息屏自停**：屏幕关闭时（包括按电源键）自动停止滑动，防止无效滑动浪费资源
- **手势模拟**：精准模拟手指滑动手势，手势坐标会基于屏幕尺寸动态计算
- **连续滑动优化**：不停顿模式下内置极小间隔，兼顾连续性与稳定性
- **智能权限管理**：支持手动开启、Shizuku 授权及 ADB 授权等多种方式，自动开启无障碍服务更便捷
- **简单易用**：直观的用户界面，轻松设置滑动参数

## 最近更新

- `v2.4`
  - 优化悬浮窗交互体验，支持在展开状态下面板任意位置（包括按钮上）直接拖拽移动，完美解决触控拦截冲突
  - 修复自定义悬浮窗容器在 Android 无障碍环境下的 performClick 规范化警告，增强系统兼容性与合规性
  - 升级核心随机数生成器为 SecureRandom 安全随机算法，彻底解决 SonarQube 安全审计热点 (kotlin:S2245)
- `v2.3`
  - 重构更新检测与界面逻辑，全面迁移至 Kotlin 协程 (Coroutines) 异步处理
  - 优化权限监控与生命周期绑定，彻底消除潜在的页面内存泄漏隐患
  - 移除无障碍服务配置中冗余的窗口内容读取权限，提升隐私合规性
  - 清理项目中全部冗余无用资源，全面通过并修复 Android Lint 的所有审查缺陷
- `v2.2`
  - 开启 R8 代码混淆与资源缩减，大幅缩小 APK 体积
  - 延迟 Shizuku 权限监听注册，优化 APP 冷启动渲染速度
  - 集成 GitHub 代理下载加速（ghfast.top），解决国内更新包下载缓慢问题
## 截图展示

![](assets/screenshot.png)

## 快速开始

### 前提条件

- Android 12.0及以上版本

### 安装步骤

1. 下载最新的APK文件从[发布页面](https://github.com/tianxing-ovo/AutoSlide/releases/)
2. 打开下载的APK文件并按照屏幕上的指示安装应用程序
3. 启动应用，并授予必要的权限以确保其正常运行

## 贡献代码

我们欢迎任何形式的贡献，包括但不限于bug报告、功能请求、pull
requests等

## 许可证

本项目基于 Apache License 2.0 许可证，详见 [LICENSE](LICENSE) 文件
