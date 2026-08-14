# ShareScreenToTV 📺📱

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm_Noncommercial_1.0.0-blue.svg)](https://polyformproject.org/licenses/noncommercial/1.0.0/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Platform-Android_TV_%26_Mobile-green.svg)](https://developer.android.com/)

**ShareScreenToTV** 是一个专为 Android 手机与 Android TV（大屏电视/投影仪）打造的高性能、低延迟局域网无线投屏与文件投递系统。基于 Android 原生硬件编解码（`MediaCodec`）、`Jetpack Compose`、`CameraX` 扫码以及 UDP/TCP 自研传输协议构建。

---

## 🌟 核心特性 (Key Features)

### 1. 📱 高清低延迟屏幕与音频实时投屏
- **硬件编解码**：手机端通过 `MediaProjection` 捕获屏幕并利用 `MediaCodec` 进行 H.264 硬件加速编码；电视端利用 `SurfaceView` 实现毫秒级硬件解码渲染。
- **立体声音频同步**：内置 `AudioRecord` 与 AAC-LC 硬件编码器，精准注入 `csd-0` 音频元数据并在电视端实时立体声解码播放。
- **抗微抖动与防撕裂**：优化 UDP 2MB 环形接收缓冲区与发包微步节流，杜绝大屏撕裂与马赛克色块。

### 2. 🛡️ 智能绕过 VPN 局域网直连
- 支持在手机端或电视端开启 VPN（TUN 模式）的环境下，自动识别并绑定物理 Wi-Fi 网卡接口（`NetworkCapabilities.TRANSPORT_WIFI`），绕过 VPN 路由冲突，保障局域网无感直连。

### 3. 📦 任意文件与 APK 极速投递
- **通用文件传输**：支持从手机端将任意大文件（视频、音频、图片、文档、压缩包）以多线程块传输（TCP 20003）推送到电视。
- **APK 一键安装**：投递 APK 安装包后，电视端自动触发 PackageInstaller 快速安装。
- **电视端文件管理**：内置大屏专用缓存管理器（`CacheManagerActivity`），支持查看、播放媒体、安装应用和一键清空。


---

## 🏗️ 架构与端口说明 (Architecture)

```text
📱 手机端 (Sender)                                      📺 电视端 (Receiver)
┌──────────────────────────────────────────┐           ┌──────────────────────────────────────────┐
│  • Jetpack Compose UI                    │           │  • SurfaceView 硬件加速解码              │
│  • CameraX 扫码解析                      │           │  • ZXing 二维码生成展示                 │
│  • MediaProjection 屏幕捕获 (H.264)      │  UDP 20001│  • VideoDecoder (MediaCodec)             │
│    ─────────────────────────────────────┼──────────>│  • AudioDecoder (AAC-LC AudioTrack)      │
│  • AudioRecord 音频采集 (AAC-LC)         │  UDP 20002│  • TvDialogBuilder 遥控交互系统          │
│    ─────────────────────────────────────┼──────────>│  • CacheManager 缓存文件管理             │
│  • ControlClient (TCP 控制握手)          │  TCP 20000│  • ControlServer 控制中枢                │
│    ◄────────────────────────────────────┼──────────►│  • FileReceiver (大文件/APK 接收端)       │
│  • FileSender (通用文件分块传输)         │  TCP 20003│                                          │
│    ─────────────────────────────────────┼──────────>│                                          │
└──────────────────────────────────────────┘           └──────────────────────────────────────────┘
```

| 端口 (Port) | 协议 (Protocol) | 用途 (Description) |
| :--- | :--- | :--- |
| **20000** | TCP | 连接握手、分辨率协商、状态心跳与断连控制 |
| **20001** | UDP | H.264 视频流实时低延迟传输 |
| **20002** | UDP | AAC-LC 立体声音频流传输 |
| **20003** | TCP | 任意文件与 APK 大文件快速传输通道 |

---

## 🚀 快速开始 (Getting Started)

### 编译构建 (Build from Source)

环境要求：
- Android SDK 34+
- JDK 17+
- Gradle 8.11+

```bash
# 克隆仓库
git clone https://github.com/nameZhj/ShareScreenToTV.git
cd ShareScreenToTV

# 编译生成双端 APK
./gradlew :app-receiver:assembleDebug :app-sender:assembleDebug
```

编译产物位于：
- 电视端：`app-receiver/build/outputs/apk/debug/app-receiver-debug.apk`
- 手机端：`app-sender/build/outputs/apk/debug/app-sender-debug.apk`

---

## 📖 使用指南 (Usage)

1. **安装应用**：
   - 将 `app-receiver-debug.apk` 安装到 Android 电视机 / 电视盒子 / 投影仪。
   - 将 `app-sender-debug.apk` 安装到 Android 手机。
2. **连接电视**：
   - 确保手机与电视连接在同一个局域网（Wi-Fi 或路由器局域网）。
   - 打开电视端 App，屏幕上会显示连接二维码及本机 IP。
   - 打开手机端 App，点击 **“扫描电视二维码连接”**，对准电视屏幕扫码即可瞬间完成连接。
3. **投屏与功能操作**：
   - **屏幕共享**：在手机端点击“开始投屏屏幕”，授予录屏权限后电视即可实时显示手机画面与声音。
   - **投递文件/APK**：在手机端点击“上传文件到电视”，选取任意文件或 APK，电视端接收完成后即可直接打开或安装。
   - **遥控器菜单**：投屏中按下遥控器 **OK / 设置 / 菜单键**，可随时调整画面缩放比例或进入文件管理器。

---

## 📄 许可证 (License)

本项目采用 **[PolyForm Noncommercial License 1.0.0](LICENSE)** 许可证。

- ✅ **允许**：个人、学术研究及非商业性用途的自由使用、修改与分发。
- ❌ **禁止**：任何以营利、商业变现或商业组织生产经营为目的的使用。

---

## 🙏 致谢与开源组件 (Acknowledgments)

本项目基于以下优秀的开源项目构建，特此致谢：

- **[AndroidX & Jetpack Compose](https://developer.android.com/jetpack)** ([Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)) - 现代化 Android UI 与架构组件
- **[ZXing Core](https://github.com/zxing/zxing)** ([Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)) - 二维码生成与解析引擎
- **[ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)** ([Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)) - 快速条码/二维码扫描组件
- **[Glide](https://github.com/bumptech/glide)** ([BSD / MIT / Apache-2.0](https://github.com/bumptech/glide/blob/master/LICENSE)) - Android 媒体与图片加载引擎

---

## ⚠️ 注意事项 (Notes)

- **小米 / HyperOS 手机投屏相册**：若在共享相册时电视端画面呈现高斯模糊/毛玻璃效果，属于小米 HyperOS 系统级“屏幕共享防护 / 电诈防护”隐私安全策略，可在手机系统设置中关闭对应防护选项后正常投屏。
- **局域网防火墙**：请确保局域网路由器未开启 AP 隔离（Client Isolation），以便双端端口（TCP 20000, 20003 / UDP 20001, 20002）正常互通。
