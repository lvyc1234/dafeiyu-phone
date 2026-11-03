# Android 4.4（API 19）兼容说明

目标：把「大肥鱼」的两个原生 APK 降到 **minSdkVersion 19**，能在 KitKat 老机器上安装并正常跑；
脚本层在 4.4 上按能力降级，缺什么关什么，而不是崩。

构建：GitHub Actions `.github/workflows/android44.yml`，push 到 `main` 或手动 dispatch 即编译，
产物进 Actions artifact，同时覆盖发布到 release `android44-latest`。

- `DafeiyuBoot.apk` —— 开机启动器
- `DafeiyuChat.apk` —— 聊天界面（WebView）

---

## 1. 构建层

| 项目 | 改前 | 改后 |
| --- | --- | --- |
| `minSdkVersion` | 23 | **19** |
| `targetSdkVersion` | 34 | 34（不变） |
| aapt2 link | `--min-sdk-version 23` | `--min-sdk-version 19` |
| d8 | `--min-api 23` | `--min-api 19`（lambda / try-with-resources 降级到 API 19） |
| 签名 | 仅 v2（默认按 minSdk 推导） | **强制 v1 JAR 签名**，`--min-sdk-version 18` 让 v1 摘要用 SHA-1，4.4 的校验器最稳 |
| 构建脚本 | 只有 `build.ps1`（Windows） | 新增 `scripts/build_app.sh`（Linux / CI，与 ps1 等价） |

Android 4.4 不认 APK Signature Scheme v2/v3，**只认 v1（JAR signing）**，
所以 `apksigner` 必须显式 `--v1-signing-enabled true`，否则装不上（`INSTALL_PARSE_FAILED_NO_CERTIFICATES`）。

## 2. DafeiyuBoot（启动器）

| 位置 | 问题 | 处理 |
| --- | --- | --- |
| `AndroidManifest.xml` | `@android:style/Theme.Material.NoActionBar` 是 API 21 才有 | 主题改 `@style/AppTheme`：`res/values/` 用 `Theme.Holo.NoActionBar`，`res/values-v21/` 用 Material |
| `MainActivity` | `setStatusBarColor` / `setNavigationBarColor` API 21 | `SDK_INT >= 21` 才调用 |
| `MainActivity` | `Settings.canDrawOverlays` API 23 | 4.4 返回 `true`（manifest 声明 `SYSTEM_ALERT_WINDOW` 后安装即授权） |
| `MainActivity` | `ACTION_MANAGE_OVERLAY_PERMISSION` API 23 | 4.4 退回 `ACTION_APPLICATION_DETAILS_SETTINGS` |
| `MainActivity` | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` API 23 | 4.4 直接提示「没有这个开关」 |
| `MainActivity` | `checkSelfPermission` API 23 | 只在 `>= 33` 请求通知权限时调用 |
| `MainActivity` | `startForegroundService` API 26 | 已有分支，保留 |
| `BootService` | `NotificationChannel` API 26、`FLAG_IMMUTABLE` API 23、`setColor` API 21 | 全部已判版本；4.4 走 `new Notification.Builder(this)` |
| `BootService` | 硬编码 `org.autojs.autoxjs.v6` | 新增 `AutoJs.java` 按 `autoxjs.v6 → autoxjs → autojs → stardust` 顺序探测 |
| `BootReceiver` | 只认 `BOOT_COMPLETED` | 追加 `QUICKBOOT_POWERON`（部分 4.4 ROM 只发这个） |
| 资源 | 无 `res/values` | 新增 `values/styles.xml` 与 `values-v21/styles.xml` |

`<queries>`、`<property>`、`foregroundServiceType` 这些新 manifest 元素 4.4 会直接忽略，不需要删。

## 3. DafeiyuChat（聊天界面）

| 位置 | 问题 | 处理 |
| --- | --- | --- |
| `MainActivity` | `setStatusBarColor` API 21 | 判版本 |
| `MainActivity` | `onShowFileChooser` + `FileChooserParams` 是 API 21 | 拆成两个 `WebChromeClient`：`Api21ChromeClient` 只在 21+ 实例化；4.4 用 `LegacyChromeClient` 的 `openFileChooser(uploadMsg, acceptType, capture)` |
| `WebSettings` | 4.4 的 `file://` 页面要读本地资源 | 打开 `setAllowFileAccessFromFileURLs` / `setDomStorageEnabled` / `setDatabaseEnabled` |
| `NtfyBridge` | **TLS1.0 默认协议**：4.4 与只支持 TLS1.2 的服务器握手直接失败 | 新增 `TlsCompat`：API ≤ 20 强制 `TLSv1.2`，并用反射补 SNI |
| `NtfyBridge` | **系统 CA 里没有 ISRG Root X1**（2015 年才签发），Let's Encrypt 站点全部校验失败 | `TlsCompat` 带 `assets/tls/isrgrootx1.pem`、`isrg-root-x2.pem`，系统校验失败后用 PKIX 以「系统根 + 内置根」重建信任链再验一次（仍然校验完整链路和有效期，不是 trust-all） |
| `NtfyBridge` | `runScript` 硬编码 AutoX v6 包名 | 改用 `AutoJs.java` 多包名探测 |
| `index.html` | ES6：`async/await`、`Promise`、`padStart` —— Chromium 30~33 直接 SyntaxError，整页 JS 不执行 | 全部改写为 ES5 回调式，`padStart` 换 `pad2()` |
| `index.html` | CSS：`var()`、`grid`、`gap`、`inset`、`aspect-ratio`、`min()`、`env(safe-area-inset-*)` 全不支持 | 换成写死色值 + `position:absolute` / `float` / `inline-block` 布局 |
| `index.html` | `classList.toggle(cls, force)` 老实现忽略第二参数 | 改成手写 `setAdvanced()` |

## 4. AutoX 脚本（`autox/*.js`）

脚本本身早就是 ES5（Rhino 友好，没有箭头函数/模板字符串），补的是运行时能力探测：

- 新增 `SDK_INT` / `IS_KITKAT` / `AUTOX_PKG_CANDIDATES`；
- `ensureScreenCapture()`：4.4 没有 MediaProjection，先 `shell("id")` 查 root，无 root 直接关闭
  截屏/OCR 兜底并写日志（其它功能不受影响）；
- `startKeepAliveService()`：按 `autoxjs.v6 → autoxjs → autojs` 顺序试，Auto.js 4.x 没有该服务时静默跳过；
- `paddle` OCR 模块在 4.x 不存在，原有 `typeof paddle === "undefined"` 判断已经兜住。

## 5. 4.4 上仍有的限制（客观存在，不是 bug）

1. **AutoX.js 6 装不上**：它的 minSdk 是 24。4.4 请装 **Auto.js 4.1.1（`org.autojs.autojs`）**，
   两个 APK 和脚本都会自动识别这个包名。
2. **没有 MediaProjection**：4.4 截屏只能 root；无 root 时「看屏幕/OCR 兜底」关闭，纯无障碍操作仍可用。
3. **没有 `paddle` OCR**：视觉理解走视觉模型 API，或干脆关掉。
4. **WebView 是 Chromium 30~33**：页面已降级，但透明渐变、圆角阴影等观感会比新机朴素。
5. **没有通知渠道/电池优化页**：启动器里按钮 2、3 在 4.4 上只会提示「不需要」，不影响功能。
6. **TLS**：只支持 TLS1.2 + 内置的 ISRG 根；如果 ntfy 换到别的 CA 且该 CA 不在 4.4 系统库里，
   需要把对应根证书（PEM）丢进 `app/DafeiyuChat/assets/tls/` 并在 `TlsCompat.EXTRA_ROOTS` 里登记。

## 6. 安装

```bash
# 从 release 或 Actions artifact 拿到两个 APK
adb install -r DafeiyuBoot.apk
adb install -r DafeiyuChat.apk
```

手动在手机上装也可以（4.4 需要允许「未知来源」）。装完：

1. 装 Auto.js 4.1.1，开无障碍 + 悬浮窗；
2. 把 `autox/agent.js` 放到 `/sdcard/PhoneAgent/agent.js`，在 Auto.js 里跑一次；
3. 打开「大肥鱼启动器」，点「测试启动大肥鱼」验证；
4. 打开「大肥鱼」聊天 App，设置里填 ntfy topic。
