# 卡面工坊 · 一加钱包适配版

基于 [MiamBao/cardface-miuix](https://github.com/MiamBao/cardface-miuix) 的 Fork，保留原项目的 Miuix 界面、图库、裁剪、Lanczos3 图像管线及小米智能卡支持。

## 下载

[下载当前适配版 APK](https://github.com/Cokelce/cardface-miuix/releases/download/v1.0.5-oneplus.7/CardFace-1.0.5-oneplus.7.apk) · [全部版本](https://github.com/Cokelce/cardface-miuix/releases)

### 本次更新

- 修复网络替换图片被重复采集后显示“未替换”的问题，统一首页和 Hook 的映射查询。
- 首页保持简约双列；编辑页增加“原图 → 替换后”预览。
- 采集一加钱包真实卡名；显示顺序为手动名称、钱包名称、图片文件名。
- 关于页清理指定署名，项目入口链接改为本仓库。
- 应用内更新检查现使用本仓库 Releases（含测试版），按完整 oneplus.N 版本号比较，并提供 APK 下载按钮。

## 一加钱包适配

- 目标钱包：`com.finshell.wallet`，基于 **5.52.0** 版本分析适配。
- 适配版包名：`io.github.miambao.cardface.oneplus`，与上游原版共存。
- 当前版本：`1.0.5-oneplus.7`（versionCode `22`）。
- 采集 `NfcCard`、`NfcCardDetail` 等卡片模型中的原始卡面 URL，接入原项目的编辑、图片映射与私有目录同步流程。
- 更新关于页和使用说明，保留上游署名及 GPL-3.0 许可证。

### 使用

1. 安装适配版，在 LSPosed 中启用“卡面工坊·一加适配”。
2. 一加设备选择作用域 **钱包（com.finshell.wallet）**；小米设备选择 **小米智能卡**。
3. 重启钱包并浏览卡面，再回到工坊选择图片、裁剪、保存并应用。

### 验证状态

已完成 APK 构建、安装版本核对及应用启动检查；79 项应用测试和 21 项图像管线测试通过。钱包内实际注入及换图效果仍待真机验证，其他钱包版本兼容性待验证。

### 构建适配版

Android SDK Platform 37.0、Build Tools 37.0.0，Gradle Wrapper 9.5.1，AGP 9.3.1，Kotlin 2.4.20。配置 `local.properties` 中的 `sdk.dir` 后运行：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :imaging:test
```

手机 ARM/chroot 环境如需自定义 AAPT2，可通过本机的 Gradle 属性设置 `android.aapt2FromMavenOverride`；仓库不硬编码本机工具路径。图像对比测试需要可用的 Java AWT 字体库。

原项目：MiamBao；原版 DIY NFC：zhizi42；原版重构署名按上游保留；一加钱包适配：Eta。

---

## 上游项目说明

<p align="center">
  <img src="docs/icon.png" width="112" alt="卡面工坊"/>
</p>

<h1 align="center">卡面工坊</h1>

<p align="center">
  把小米智能卡里千篇一律的卡面，换成你自己喜欢的图片<br/>
  <sub>LSPosed 模块 · Miuix（HyperOS）界面 · Lanczos3 图像管线</sub>
</p>

<p align="center">
  <a href="https://github.com/Xposed-Modules-Repo/io.github.miambao.cardface"><img src="https://img.shields.io/badge/LSPosed-Module%20Repo-blue.svg" alt="LSPosed Repo"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android"/>
  <img src="https://img.shields.io/badge/LSPosed-API%20101%2B-orange.svg" alt="LSPosed"/>
</p>

---

## 截图

| 首页 | 设置 |
|---|---|
| ![首页](docs/screens/01-home.jpg) | ![设置](docs/screens/02-settings.jpg) |

| 编辑卡面 | 图库 |
|---|---|
| ![编辑](docs/screens/03-editor.jpg) | ![图库](docs/screens/04-gallery.jpg) |

| 关于 |
|---|
| ![关于](docs/screens/05-about.jpg) |

### 原版界面

下面是本项目所基于的原版「DIY NFC卡面图片」v2.4（作者 zhizi42）的界面，仅作来源说明 / 对比用：

![原版界面（DIY NFC卡面图片 v2.4）](docs/screens/06-original-ui.jpg)

> 原版仓库：<https://github.com/Xposed-Modules-Repo/com.zhizi42.diymiuicard>

## 功能

- **替换卡面**：把图片合成为 960×606 PNG，复制到卡包私有目录后以 `file://` 交给卡包加载；
  兼容卡包 Glide 加载链路（反编译定位 `CustomGlideUrl` 工厂方法与 `RequestManager` 重载）。
- **高质量缩放**：缩放感知 Lanczos3 + sRGB 线性光重采样，细线 / 文字不再有锯齿（原版为最近邻缩放）。
- **图库 / 裁剪 / 卡面标志**：3 列图库、160:101 固定比例裁剪（双指缩放、拖动、缩放按钮）、
  京津冀 / 上海 / 泉城通 / 银川 标志、圆角裁切。
- **Miuix（HyperOS）界面**：大圆角状态卡、灰底白卡分组、澎湃蓝 / 动态取色 / 8 个预设色 /
  深色模式 / 过渡动画、底部悬浮导航。
- **其他**：卡面重命名、隐藏桌面图标（带通知入口防丢）、hook 缓存清理、一键重启卡包。

## 环境要求

- Android 8.0+（minSdk 26，targetSdk 36）
- 已 root，并安装 **LSPosed**（libxposed API 101+）
- 卡包：小米智能卡（`com.miui.tsmclient`）
- 参考验证环境：Xiaomi 15 Pro / HyperOS 4 / LSPosed 2.2.1 + 小米智能卡

## 安装与使用

1. 安装 Release 里的 `CardFace-v1.0.5.apk`。
2. 在 LSPosed 里启用「卡面工坊」，作用域勾选：小米智能卡。
3. 打开卡包，把卡面浏览一遍（让 hook 记录到卡面地址）。
4. 回到「卡面工坊」→ 点一张卡面 → 从相册选图 / 从图库选 → 裁剪 / 加标志 → 保存并应用。
5. 重启卡包（保存成功后可一键重启），卡面即生效。

> **隐藏 / 寄生模式的 LSPosed**：部分设备的管理器没有桌面图标，也无法被第三方 App 直接打开
> （LSPosed 用进程内随机 UUID 广播拉起管理器）。此时从通知栏的「Xposed 模块已更新 / LSPosed」通知进入管理器，
> 长按模块 →「模块设置」即可打开本应用。App 里点「打开 LSPosed」也会给出这个提示。

## 构建

需要：JDK 17、Android SDK Platform 37、Build Tools 37.0.0。

```bash
# 1. 准备签名（可选：不配也能 build，release 产物为未签名 APK）
cp keystore.properties.example keystore.properties
# 把自己的 keystore 放到 keystore/ 下，并改 keystore.properties

# 2. 构建
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

仓库不包含签名文件与 `keystore.properties`（已在 `.gitignore` 中）。

## 项目结构

```
app/       Android 应用（Compose + Miuix），含 Xposed 模块入口
imaging/   纯 JVM 图像管线（Lanczos3 降采样、缩放、合成），可单测
docs/      规格、进度记录、截图
```

## 免责声明与兼容性

- **小米智能卡**：在上述参考环境验证通过，其他机型 / 系统 / 卡包版本可能存在差异。
- 替换图片需要把图复制进卡包私有目录（`file://`），**复制步骤需要 root**；没有 root 时会退回 `content://`，
  但部分卡包版本不认。
- 本应用只修改卡包展示图，不涉及支付、余额与刷卡逻辑；因使用本应用产生的任何问题由使用者自行承担。
- 请勿用于伪造证件、支付欺诈等任何违法用途。

## 许可证

[GPL-3.0](LICENSE)。本项目包含 / 链接了以下开源项目：

- 原版项目：[DIY NFC卡面图片](https://github.com/Xposed-Modules-Repo/com.zhizi42.diymiuicard) v2.4（zhizi42，GPL-3.0）
- UI 组件：[Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)（Yukonga，Apache-2.0）
- 模块框架：[LSPosed](https://github.com/LSPosed/LSPosed)（GPL-3.0）、[libxposed API](https://github.com/LibXposed/libxposed-api)（Apache-2.0）
- Dex 定位：[DexKit](https://github.com/LuckyPray/DexKit)（LuckyPray，Apache-2.0）
- AndroidX / Jetpack Compose / Coil 3（Apache-2.0）

## 致谢

- **作者**：MiamBao
- **重构与实现**：DeepSeek（AI 编程助手）—— Miuix 界面重构、卡面 hook 链路、图像管线与性能优化
- 以及原版作者 zhizi42 与上述开源项目的作者们
