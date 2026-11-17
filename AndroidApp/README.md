# MagnetVault Android 工程

Java 8 + Android View，SQLiteOpenHelper 管理本地数据。包名 `com.magnetvault.app`，minSdk 26、targetSdk 34。源码版本与本地 versionCode 由 `version.properties` 统一维护。

## 自动发布

`main` 分支的正式代码或构建配置更新会触发 `.github/workflows/android-release.yml`，可在 Actions 页面手动运行。流程只在本仓库的 `main` 执行，使用 Java 17 与标准 Android build-tools 35.0.0，校验原签名后发布到 Releases。

首次配置仓库的 Actions Secrets：

| 名称 | 值 |
| --- | --- |
| `MV_KEYSTORE_BASE64` | 原签名密钥的 Base64 内容 |
| `MV_STORE_PASSWORD` | 原密钥库口令 |
| `MV_KEY_ALIAS` | 密钥别名，默认 `magnetvault` |
| `MV_KEY_PASSWORD` | 密钥口令，未设置时使用密钥库口令 |

在 Actions Variables 中设置 `MV_SIGNING_CERT_SHA256` 为原签名证书 SHA256，用于阻止签错密钥的 APK 发布。私钥内容与口令仅通过 Secrets 传入签名步骤，APK 上传前删除临时密钥。

云端 `versionName` 为 `version.properties` 的版本加上运行序号，例如 `2.3.1.1`；`versionCode` 为 `max(源码 versionCode, 1000000) + 运行序号`。保留该 workflow 文件名，以维持运行序号连续。重新运行同一次构建沿用版本，已发布附件不会被覆盖。

每次发布提供 `MagnetVault.apk`、带版本号的同一 APK 和 `SHA256SUMS.txt`。用户可始终使用 [最新版 APK 链接](https://github.com/yimisunan/cilishouc/releases/latest/download/MagnetVault.apk)。

## 构建环境

构建脚本直接调用 Android 工具，不需要 Gradle：

- Bash、JDK 17（`javac --release 8`）、`zip`。
- `aapt2`、`zipalign`、`apksigner`，以及用于显示 APK 信息的 `aapt`。
- Android API 30 的平台 JAR，命名为 `android-30.jar`；包含 D8 的 `r8.jar`。

工具目录通过环境变量配置：

| 变量 | 用途 | 默认值 |
| --- | --- | --- |
| `MV_ANDROID_SDK` | 旧版工具目录，含 `android-30.jar` 与可选的 `r8.jar` | `/opt/androidsdk` |
| `MV_ANDROID_JAR` | 标准 SDK 的 `android.jar` 路径，可替代旧版目录约定 | `$MV_ANDROID_SDK/android-30.jar` |
| `MV_R8_JAR` | 没有 `d8` 可执行文件时使用的 JAR | `$MV_ANDROID_SDK/r8.jar` |
| `MV_BUILD_TOOLS` | Android 构建工具目录，优先使用其中的 `d8` | `/usr/lib/android-sdk/build-tools/29.0.3` |
| `MV_KEYSTORE` | 本地签名密钥路径 | 工程内 `release.keystore` |
| `MV_KEY_ALIAS` | 签名密钥别名 | `magnetvault` |
| `MV_STORE_PASSWORD` | 密钥库口令 | 交互输入 |
| `MV_KEY_PASSWORD` | 密钥口令 | 使用密钥库口令 |
| `MV_APK` | APK 输出路径 | 工程内 `MagnetVault.apk` |

签名密钥和口令不随源码分发。首次构建可以创建自己的密钥，口令由 `keytool` 交互询问：

```bash
keytool -genkeypair -keystore AndroidApp/release.keystore \
  -alias magnetvault -keyalg RSA -keysize 2048 -validity 10000
```

已有安装的覆盖升级需要使用原签名密钥，并保持应用包名一致。

从仓库根目录运行：

```bash
MV_ANDROID_SDK="/path/to/android-tools" \
MV_BUILD_TOOLS="/path/to/build-tools" \
bash AndroidApp/build.sh
```

脚本依次编译资源与 Java、生成 DEX、打包、对齐、签名并校验，输出 `AndroidApp/MagnetVault.apk`。非交互构建通过受保护的环境变量提供签名口令。修改输出位置时，使用绝对路径设置 `MV_APK`。

## 代码模块

| 模块 | 主要文件 |
| --- | --- |
| 本地数据与分类、标签 | `Db`、`Repo`、`Entry`、`Category` |
| 链接识别与导入预览 | `MagnetParser`、`ImportTextParser`、`ImportDraft`、`ClipImporter`、`ImportActivity` |
| 收藏、搜索与整理页面 | `MainActivity`、`SearchActivity`、`DetailActivity`、`EditActivity`、`CategoryActivity`、`CategoryPicker`、`TagPicker` |
| 加密备份与恢复 | `BackupCrypto`、`BackupManager`、`RestoreFlow`、`SecretStore` |
| 坚果云与自动备份 | `WebDavClient`、`SimpleHttpClient`、`CloudBackup`、`BackupJob`、`DavActivity`、`CloudActivity` |
| 设置与界面组件 | `SettingsActivity`、`ThemePrefs`、`Design`、`Ui`、`AppActivity` |

数据库版本为 3。加密备份扩展名为 `.mjbf`，使用 PBKDF2-HMAC-SHA256（600000 次）与 AES-256-GCM；恢复前保留加密快照，可撤销最近一次恢复。

Android 10 及以上版本的批量导入以用户主动粘贴为准。云端连接设置中的检测用于确认 WebDAV 读写权限，使用用户配置的账户。