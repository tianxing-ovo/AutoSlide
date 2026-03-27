# Android Auto-Slide App [AutoSlide]

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Version](https://img.shields.io/badge/Version-2.0-green)]()

[简体中文](README.md) | [English](README.en.md)

A simple and efficient auto-slide solution for Android devices, ideal for automated testing, content browsing, and more.

## Features

- **Timed Sliding**: Customize the time interval between slides
- **Pause Modes**: Supports three pause modes - "No Pause", "Fixed Time", and "Random Time" - and also allows manually entering a custom pause duration; random mode supports setting minimum and maximum pause duration ranges
- **Slide Speed**: Multiple speed settings with a smoother speed curve to control gesture duration
- **Slide Direction**: Supports sliding in four directions - up, down, left, and right
- **Floating Window Control**: Convenient floating button control; automatically shrinks to a small icon when sliding starts, and expands the panel while stopping when tapped
- **Permission Management**: New floating window permission toggle for quick access to system permission settings
- **Volume Key Force Stop**: Press volume keys during sliding to immediately stop and restore the floating panel
- **Screen-Off Auto Stop**: Automatically stops sliding when the screen turns off (including power button press) to prevent unnecessary resource usage
- **Gesture Simulation**: Precisely simulates finger swipe gestures, with coordinates dynamically calculated from screen size
- **Continuous Slide Optimization**: Adds a tiny built-in gap in No Pause mode to balance continuity and stability
- **Smart Permission Management**: Supports manual enablement, Shizuku authorization, and ADB authorization, making accessibility service activation more convenient
- **Easy to Use**: Intuitive user interface for effortless parameter configuration

## Recent Updates

- `v2.0`
- Added Shizuku authorization for more convenient automatic accessibility setup
- Optimized permission switch handling to reduce duplicate triggers and improve interaction
- Added support for manually entering custom pause durations for more flexible configuration

## Screenshots

![](assets/screenshot.png)

## Quick Start

### Prerequisites

- Android 12.0 or higher

### Installation

1. Download the latest APK file from the [Releases Page](https://github.com/tianxing-ovo/AutoSlide/releases/)
2. Open the downloaded APK file and follow the on-screen instructions to install the application
3. Launch the app and grant the necessary permissions for proper functionality

## Contributing

We welcome all forms of contributions, including but not limited to bug reports, feature requests, pull requests, and more.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
