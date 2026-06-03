# YukiHub

<p align="center">
  <img src="app/src/main/res/mipmap-hdpi/ic_launcher.png" alt="YukiHub" width="120" />
</p>

<p align="center">
  <a href="./README.md">简体中文</a> | <a href="./README_EN.md">English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="GPL-3.0" />
  <img src="https://img.shields.io/badge/Status-Open%20Source-7A5CFA" alt="Open Source" />
</p>

**YukiHub** is a Galgame / visual novel management and launcher tool for Android. It is suitable for managing local games, Android app-style games, emulator game entries, external program shortcuts, and play records.

Its goal is to integrate "game library management, quick launch, data synchronization, and usage statements" into a unified dark interface.

Developers are also welcome to actively build different branch versions. As the first mobile Gal frontend, there are still many shortcomings. Let's keep improving it together meow (⌯ᵔᗜᵔ⌯)/

- **Download Area**

[![GitHub release](https://img.shields.io/github/v/release/xm486/YukiHub?style=for-the-badge&logo=github)](https://github.com/xm486/YukiHub/releases/latest)

[![GitHub downloads](https://img.shields.io/github/downloads/xm486/YukiHub/total?style=for-the-badge&logo=github)](https://github.com/xm486/YukiHub/releases)

[![Download](https://img.shields.io/badge/Download-Mirror-blue?style=for-the-badge&logo=github)](https://yuki.rinne.cyou/)

> This project is open-sourced under the **GPL-3.0** license.

---

## Features

- Supports adding, editing, and deleting game entries
- Integrates game metadata sources from VNDB and Bangumi
- Supports game entries with empty directories
  - Suitable for Android app-style games, external programs, and custom launch entries
- Supports importing GameHub shortcuts
  - Shortcut list supports icon display
  - Supports search and filtering
- Supports multiple launch methods
- Supports local games, Android app-style entries, and external launch entries
- Supports game synchronization and import/export
  - Supports game entry synchronization
  - Supports play record synchronization
  - Supports matching and restoring empty-directory entries
- Supports viewing the complete disclaimer in Settings
- Requires agreeing to the disclaimer on first launch before entering
- Dark-style interface, suitable for landscape use

---

## Project Positioning

YukiHub is closer to a **local game management hub** rather than a simple launcher.

It is suitable for the following scenarios:

- Managing locally installed games
- Managing Android app-style game entries
- Managing external launch entries
- Organizing shortcuts in one place
- Recording and synchronizing play records
- Migrating game data between multiple devices

---

## Core Features

### 1. Game Management

Supports adding, editing, and deleting game entries, and provides unified management for different types of launch entries.

### 2. Empty-directory Entry Support

For games or app entries that do not require a local directory, the directory field is not mandatory.

This is especially useful for the following scenarios:

- Directly launching Android apps
- Entries launched by package name
- External program entries
- Custom quick-launch entries

### 3. GameHub Shortcut Import

Supports importing shortcuts from GameHub and provides:

- Icon display
- Search and filtering
- A clearer list selection experience

### 4. Synchronization

Supports synchronization import/export of game data and play records, suitable for local backup or multi-device migration. It also supports ☁️ WebDAV cloud synchronization.

During synchronization, matching will be attempted based on the following information:

- root path
- local ID
- game title

For empty-directory entries, title matching is prioritized.

### 5. Disclaimer System

- The full disclaimer can be viewed in Settings
- The disclaimer must be accepted on first launch before entering
- Helps with open-source release and clarifies usage boundaries

---

## Screenshots

### Main Screen

<img src="screenshots/main.jpg" width="720" />

### Sync Page

<img src="screenshots/tongbu.jpg" width="720" />

### Game Detail Page

<img src="screenshots/game.jpg" width="720" />

---

## Tutorial Area

### Import Winlator and G-station games and launch them directly

<p>
  <a href="https://b23.tv/Qixj22k">
    <img src="https://img.shields.io/badge/Bilibili-Watch%20Tutorial-00A1D6?logo=bilibili&logoColor=white" alt="Bilibili Tutorial" />
  </a>
  <a href="https://1822484704.share.123pan.cn/123pan/OsyJjv-wI1lh?pwd=8elD#">
    <img src="https://img.shields.io/badge/123pan-Modified%20Emulator%20Download-2B7FFF" alt="123pan" />
  </a>
  <a href="https://github.com/xm486/YukiHub/releases/tag/v0.1.0">
  <img src="https://img.shields.io/badge/Modified%20Emulator-Direct%20Download-181717?logo=github&logoColor=blue" alt="GitHub Download" />
</a>
</p>

- Notes:
  - The modified Winlator emulator package is based on the modified version by hostei2. `XServerDisplayActivity exported=false` was changed to `android:exported="true"` to expose the Activity for direct launch.
  - G-station games are based on the original version 5.3.5, with MT file extractor injection and Activity exposure. That is, add or change `android:exported="true"` for `android:name="com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"` in AndroidManifest.

Extraction code: `8elD`

### Tutorial for using WebDAV data cloud synchronization

<p>
  <a href="https://b23.tv/wuOvs5l">
    <img src="https://img.shields.io/badge/Bilibili-Watch%20Tutorial-00A1D6?logo=bilibili&logoColor=white" alt="WebDAV Tutorial" />
  </a>
</p>

---

### Currently Known Issues

- 1. Direct launching KRKR games with TF currently seems not to work ( )

- 2. Due to storage read/write restrictions on Huawei and some other phones, KRKR, Artemis and other engine games may not play normally. (An external private save option has been added. You can try enabling it; maybe it helps? A lightweight SAF option has also been added 🤔. Whether it works still depends on your testing.) (Feature entry is shown below ↓)

<img src="screenshots/save1.jpg" width="720" />

- That's all. PRs from capable people are welcome meow 😽😽😽

---

## Community Group

<p align="center">
  <a href="https://qun.qq.com/universal-share/share?ac=1&authKey=nZMa0s3mxxG1A0f%2BY0nAWmBYpul7FWTEDI6UWrzqb2IgKC4aDkUhvkV2AekAkW%2F1&busi_data=eyJncm91cENvZGUiOiIxNjM2MDM2MzUiLCJ0b2tlbiI6Im93eFRyY0tqNDdxK3FGQXlVZ0lhMEZGbWZWemphZnpYYW1kWWpPN1ViL3A0SkRUd1dEclMwZkM1bWI0UEYxME4iLCJ1aW4iOiIzMDg2Njc4NzU1In0%3D&data=bwoLG7XAPzqsvtfneNCQUUlu-HpX1yCn-6dkgd8ubDeBJKEPgd7wKYa6ym-EbW07Vapc3xm_o-iy0GbFHhZk5Q&svctype=4&tempid=h5_group_info">
    <img src="https://img.shields.io/badge/QQ-163603635-12B7F5?logo=tencentqq&logoColor=white" alt="QQ Group" />
  </a>
</p>

<p align="center">Welcome to join the QQ community group to report issues, make suggestions, or discuss features together.</p>

---

## Before Use

This project has a built-in disclaimer mechanism. On first launch, you need to check and agree to the disclaimer before continuing.

Please make sure you only use it to manage and launch games, apps, or resources that you have the right to use.

This project does not provide:

- Game files
- Cracked resources
- Ability to bypass authorization
- Support for any illegal use

---

## System Requirements

- Android 8.0 or above
- Landscape experience is better
- Requires partial file access permissions
- Some features may depend on system compatibility or third-party component support

---

## Permission Description

This app may request the following permissions:

- File read/write permission
- All files access permission
- Network permission

Purpose description:

- File permissions: used to read and manage game files, directories, and configurations
- Network permission: used for synchronization, online resources, or related features
- All files access: used for some directory-based game management scenarios

> Please grant permissions only when you clearly understand and accept their purposes.

---

## Installation

### Method 1: Install APK directly

Download the APK from the Releases page and install it.

### Method 2: Build it yourself

If you want to build the project yourself, please make sure you have installed:

- Android Studio
- Android SDK
- Gradle environment

Then open the project and run the build.

---

## Build Information

- Application ID: `com.yuki.yukihub`
- Min SDK: `26`
- Target SDK: `33`
- Compile SDK: `33`
- Current version: `0.1`

---

## Notes

- The project is currently in a continuous polishing stage before and after open-source release
- Some synchronization or cloud features depend on external service availability
- Some compatibility entries depend on the device environment and third-party app support

---

## Open Source License

This project is open-sourced under the **GNU General Public License v3.0 (GPL-3.0)**.

You may:

- Use it freely
- Modify it freely
- Distribute it freely
- Carry out secondary development under GPL-3.0 restrictions

Please use this project's source code under the terms of GPL-3.0.

---

## Disclaimer

This project is for legal use only.

The author is not responsible for the following situations:

- User operation mistakes
- Third-party resource issues
- System compatibility issues
- Third-party service unavailability
- Any illegal behavior caused by the user's use of this software

Please make sure you only use it to manage and launch software, games, or resources that you have the right to use.

---

## Acknowledgements

Thanks to the projects used as references and learning materials:

- krkr2
- Tyranor
- Beacon
- LunaBox
- Playnite

Thanks also to all users who participated in testing, feedback, and suggestions.

---

## Feedback and Contribution

If you encounter problems during use, feel free to submit an Issue or Pull Request.

You can also include the following when submitting feedback:

- Device model
- Android version
- Problem screenshots
- Reproduction steps
- Log information

This makes it easier to locate the issue.

---

## License

[GPL-3.0](./LICENSE)
