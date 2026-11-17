# 磁力收藏夹（MagnetVault）

用于收藏和整理磁力、ED2K 文件链接的 Android 应用。只保存链接与文字元数据，不下载、缓存或解析媒体本体和种子文件。

当前版本：**2.3.1**（versionCode 9）。包名：`com.magnetvault.app`。支持 Android 8.0（API 26）及以上版本，targetSdk 34。

## 功能

- 收藏链接，编辑标题、来源和备注，使用多级分类、标签与星标整理。
- 搜索、批量移动、标签整理、回收站与恢复。
- 粘贴单条或多条磁力、ED2K 链接，支持带分类标签的文本与表格，预览后导入并跳过重复。
- 复制链接、系统分享与打开 MoePal。
- 导出和恢复客户端加密的 `.mjbf` 备份；支持坚果云 WebDAV 与每日自动备份。
- 浅色、深色和跟随系统主题；长标题自动换行。

## 项目结构

```text
AndroidApp/
├── AndroidManifest.xml
├── build.sh                 # 编译、对齐与签名
├── README.md                # 构建与模块说明
├── src/com/magnetvault/app/  # 正式 Java 源码
└── res/                     # 布局、主题、图标和其他资源
```

采用 Java 8、Android View 和 SQLite，运行时没有第三方库，不依赖 Gradle。仓库仅收录正式源码与说明；签名密钥和应用数据由使用者本地保管。

## 构建

需要 Bash、JDK 17、`zip`，以及 `aapt2`、`zipalign`、`apksigner`、Android API 30 的 `android.jar` 和包含 D8 的 `r8.jar`。工具目录约定及签名配置见 [工程说明](AndroidApp/README.md)。

```bash
export MV_ANDROID_SDK="/path/to/android-tools"
export MV_BUILD_TOOLS="/path/to/build-tools"
bash AndroidApp/build.sh
```

`MV_ANDROID_SDK` 目录需要包含 `android-30.jar` 与 `r8.jar`。构建时使用本地 `AndroidApp/release.keystore`，在交互终端输入口令，生成 `AndroidApp/MagnetVault.apk`。也可以通过环境变量指定密钥和口令，详见工程说明。

首次构建可用 `keytool` 创建自己的签名密钥；覆盖升级已有安装必须使用原签名密钥。

## 数据与备份

数据保存在应用本地 SQLite 数据库。备份使用 PBKDF2-HMAC-SHA256 与 AES-256-GCM 加密，坚果云只接收密文。备份密码可由本机 Android Keystore 加密保存，换设备时需要重新输入；忘记密码无法找回。
