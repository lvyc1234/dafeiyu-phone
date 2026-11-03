# 大肥鱼 · 给她一部手机

> 一台闲置 Android 手机，一个会自己生活、记得你、能远程聊天的 DeepSeek 鲸鱼娘。

这是一个**完全跑在手机里**的陪伴 Agent：

- 用 AutoX.js 控制手机（无障碍 + 悬浮窗）
- 用 DeepSeek API 做人设、决策和聊天
- 用 [ntfy](https://ntfy.sh) 做远程消息通道
- 用一个原生 Android APK 当聊天窗口
- 不需要电脑常开，不需要 root

[![Version](https://img.shields.io/badge/version-1.2.0--android44-FB7299)](RELEASE_NOTES.md)
[![Memory](https://img.shields.io/badge/Memory-Local%20File%20System-2F6BFF)](autox/agent.js)
[![Android](https://img.shields.io/badge/Android-4.4%20~%2015-3DDC84)](docs/ANDROID44.md)
[![DSH World](https://img.shields.io/badge/DSH%20World-chenkezhen480%2Fdsh--world-6f42c1)](https://github.com/chenkezhen480/dsh-world)

> **Android 4.4（API 19）兼容版**：两个 APK 的 `minSdkVersion` 已降到 19，
> 主题/通知/悬浮窗/前台服务/TLS 全部做了版本守卫，聊天页降级为 ES5 + 旧版 WebView 布局。
> 改动清单见 [docs/ANDROID44.md](docs/ANDROID44.md)，用 GitHub Actions 自动编译
> （`.github/workflows/android44.yml`，产物在 Actions artifact 与 `android44-latest` release）。

![大肥鱼主视觉](docs/images/main-visual.png)

> 项目作者写给你的一封信：[给所有想把她带回家的人](LETTER.md)

> 关于关系：`Yihua666666` 提供闲置手机并发起项目，AI 助手在委托下完成开发；信里的“母亲”是开发者的视角，手机的主人是把手机交出来的那个人。

![93 张立绘画廊](docs/images/gallery.png)

## 她能做什么

- **自己生活**：每 10–20 分钟自己想想做什么，写日记、翻笔记、看天气、刷视频、看番
- **关屏也能醒**：到点自己点亮屏幕继续（可关闭）
- **开机自启**：独立启动器 + App / 悬浮面板 / 远程开关，重启后自己回来
- **长期记忆**：`facts.md`、`self.md`、每日对话、每日回顾；有注入预算、事实去重和归档
- **短时记忆**：最近 8 条对话；你发图时她会结合上一轮，不会把“糖”当成无关图片
- **远程聊天**：你在自己手机上通过 ntfy 找她，她在那台手机里回你
- **QQ 自动回复**：专用 QQ 小号，私聊/@ 回，群聊没 @ 小概率随机接
- **B站模式**：热门/登录推荐、真实播放、看懂内容、记录日记
- **梗库**：刷 B站/QQ 时收藏好笑的梗；B站评论图可存，QQ 图片暂不支持
- **主动找你**：闲着超过 90 分钟、且两小时内没找过你，会主动发一句
- **93 张立绘**：App 里点 🖼 打开画廊，点一张就换头像
- **看图**：你上传的图片用视觉模型；屏幕、相册、网络图默认本地 OCR
- **悬浮面板**：可调大小/位置，默认 1000×820，记住上次布局
- **熄屏做事**：纯文字/记忆任务不亮屏；需要界面时短暂亮屏，结束自动熄屏
- **能删自己的文件**：`/sdcard` 下除 `Android/` 外，文件和目录都可以删

## 1.1 迭代了什么

相对 `1.0.0`：

| 方向 | 1.0.0 | 1.1.0 |
|---|---|---|
| 聊天记忆 | 最近 4 条，图片另走任务 | 最近 8 条；图片带上下文；快捷回复也入历史 |
| QQ | 只有通用的 ntfy 桥说明 | 手机端通知监听 + 无障碍发送；私聊/@ 回，群聊随机 |
| B站 | 只能由通用 Agent 临时操作 | 专门 B站模式：真实播放、看懂、日记、收藏 |
| 梗库 | 无 | B站/QQ 文字梗 + B站评论图/封面 |
| 熄屏 | 会亮屏，但不一定熄回去 | UI 任务结束自动熄屏；QQ 回完回桌面/熄屏 |
| 悬浮窗 | 固定大小和位置 | 可调大小/位置，布局持久化 |
| 记忆 | facts 无上限、无去重 | 注入预算 + 事实归档 + 去重合并；facts 仍不设条数上限 |
| 聊天 App | 只能发文字 | 增加 📎 上传图片 |
| 文档 | 安装/架构/安全 | 增加 FAST_CHAT、VISION、SCREEN_OFF、BILIBILI、COLLECTIONS、MEMORY_IMPORT |

完整对比见 [RELEASE_NOTES.md](RELEASE_NOTES.md)。

## 快速开始

### 0. 你需要

- 一台闲置 Android 手机（Android 8+，arm64）
- Wi-Fi
- 一个 DeepSeek API Key
- 另一个手机或电脑，用来接收远程消息

> 建议用**专用手机**：不要登录主微信、主支付、主邮箱或唯一 2FA。

**设备说明**

项目不绑定某一台手机。主程序是 AutoX.js + 普通 Android App，任何能正常跑 AutoX.js 6.6.8 的 Android 8+ 手机都可以尝试。

| 系统 | 测试/适配情况 |
|---|---|
| HyperOS / MIUI（Redmi K60，Android 15） | 开发机，已验证 |
| Android 原生 / Pixel 类 | 理论可用，开机启动器最稳 |
| ColorOS / OriginOS / EMUI / One UI | 允许自启动、后台运行；不同 ROM 名称不同 |

不同 ROM 的权限名字不一样，思路都是：**允许自启动、允许后台运行、允许悬浮窗、电池不限制**。

### 1. 安装 AutoX.js

安装 **AutoX.js v6.6.8**（本项目测试版本），然后打开：

- 无障碍服务
- 悬浮窗权限
- 电池 → 无限制
- 自启动 / 后台运行（不同 ROM 名字不同）
- AutoX 菜单里的 **前台服务**

**AutoX 不包含在 `DafeiyuChat` / `DafeiyuBoot` APK 里，必须单独安装。**  
从 1.1.0 起，Release 附件已提供：

```text
AutoX.js-6.6.8.apk
dafeiyu-phone-1.1.0-bundle.zip   # AutoX + Chat + Boot + 安装顺序
```

下载页：

<https://github.com/Yihua666666/dafeiyu-phone/releases/tag/v1.1.0>

安装顺序必须是：

```text
AutoX.js 6.6.8 → DafeiyuChat → DafeiyuBoot
```

只装 Chat/Boot 再问“为什么没有 AutoX”，启动器是拉不起 agent 的。AutoX 是独立开源项目，APK 原样附带，许可证与源码见 `NOTICE.md`。

### 2. 放入脚本

把 `autox/` 下的文件放到手机：

```text
/sdcard/PhoneAgent/agent.js
/sdcard/PhoneAgent/im_watch.js      # QQ 通知自动回复（可选）
/sdcard/PhoneAgent/chat.js
/sdcard/PhoneAgent/setup_boot.js
/sdcard/PhoneAgent/boot_on.js
/sdcard/PhoneAgent/boot_off.js
```

也可以用 ADB：

```powershell
powershell -File scripts/push_to_phone.ps1 -Serial <你的设备序列号>
```

### 3. 第一次运行

在 AutoX.js 里打开 `/sdcard/PhoneAgent/agent.js`，点 **运行**。

会弹出设置框，填：

```text
API Key:   sk-...
Base URL:  https://api.deepseek.com/v1
Model:     deepseek-flash
```

然后她会开始说话。悬浮面板左上角是状态，点 **“看看她在不在”** 可以看状态卡。

**看图（可选）**

DeepSeek 的 **`deepseek-flash`** 支持图片输入。主模型填 `deepseek-flash` 时，只有**你上传的图片**会用它看，不需要再配视觉模型。

如果你主模型用的是 `deepseek-chat`，或想换成别的视觉服务，再填：

```text
Vision Model:  deepseek-flash / gpt-4o-mini / Qwen2.5-VL-32B-Instruct / ...
Vision Base:   留空=同主模型；换 OpenRouter 等时填对应 /v1
Vision Key:    留空=同主 Key
```

**视觉只用于你上传的图片。** 当前屏幕、相册里的旧照片、网络图片，一律走手机本地 PaddleOCR，不发给视觉模型。

上传方式：

- 在聊天 App 里点输入框左边的 **📎** 选图
- 在 ntfy App / 浏览器里给 topic 附一张图（会自动进 `/sdcard/DeepSeekGirl/inbox/`）
- 或直接把图片放进 `/sdcard/DeepSeekGirl/inbox/`

然后说“看看我上传的这张图”。不要让她看支付、银行、验证码和隐私聊天。

视觉服务示例和隐私说明见 [看图能力](docs/VISION.md)。

### 4. 配置远程聊天

Agent 会自动生成一个私密 topic：

```text
/sdcard/DeepSeekGirl/remote/topic.txt
```

安装 `dist/DafeiyuChat-1.1.0.apk`，打开 App，点右上角 **⚙**，把 topic 填进去。

之后：

- 你在 App 里发消息 → ntfy → 她回
- 她主动找你 → 同一 topic → App 里出现

topic 是密码，不要发到公开地方。

### 5. 开机自启（推荐用独立启动器）

AutoX 自己的 `BOOT_COMPLETED` 任务在部分系统上会跑在“无 UI 的后台上下文”，脚本能写一行启动日志，但随后被系统挂起，面板和远程桥都不工作。所以推荐装 **DafeiyuBoot**：

```text
dist/DafeiyuBoot-1.0.0.apk
```

它做一件事：

```text
BOOT_COMPLETED → 前台服务 → ShortcutActivity → agent.js
```

安装后打开“大肥鱼启动器”：

1. 允许悬浮窗
2. 允许通知
3. 电池 → 无限制
4. 系统设置里允许它**自启动**
5. 如果有“后台弹出界面”权限，也允许
6. 点“测试开机服务”，确认能拉起大肥鱼

如果系统仍然拦截后台启动，启动器会留一条通知：“大肥鱼还没起来，点一下让她回家”。点通知即可启动。

**备选：AutoX 直接开机任务**

如果你不想装启动器，也可以在 App 里点 **开机** → 设置，或悬浮面板点 `开机自启：开`，或运行：

```text
/sdcard/PhoneAgent/setup_boot.js
```

这种方式在部分 ROM 上只能拉起后台脚本，面板可能不显示；能接受再使用。

也可以远程发：

```text
/boot on
/boot off
/boot status
```

## 远程聊天

使用的是公共 ntfy 服务器：

```text
https://ntfy.envs.net/<你的 topic>
```

ntfy.sh 在部分网络下不通，所以代码换成了 `ntfy.envs.net`。

你可以：

- 用 App 聊
- 用浏览器打开 `https://ntfy.envs.net/<topic>`
- 用 ntfy App 订阅

消息不是端到端加密，不要发密码、验证码、支付信息。

### 省 token / 微信 QQ

聊天和任务走两条路：

- **闲聊**：一次 `deepseek-flash`，关闭 thinking，只带最近 8 条，`max_tokens=100`
- **常见消息**：在/你好/早安/晚安/哈哈/谢谢/收到，直接罐头回复，0 token
- **任务**：`/do 打开设置` 或“帮我打开…”才走完整 Agent

QQ 走手机端 `im_watch.js`：

- 监听 QQ 通知，私聊和 @ 都回
- 群聊没 @：15% 概率、10 分钟冷却后随机接一句
- 用无障碍找到输入框和发送按钮；不依赖外部 bridge
- 只适合专用 QQ 小号，个人号自动化有风控风险

**微信不包含在 1.1 的手机自动回复里。** 在未 root 的 K60 上：

- 微信通知没有 `RemoteInput`
- 微信无障碍树为空
- MIUI 拒绝后台拉起截屏授权

所以微信只能走 PC bridge 或官方通道，不能承诺纯手机自动回。

短时记忆与发图：

- 最近 8 条对话会进入聊天上下文
- 你上传图片时，会带上最近对话再理解
- 她如果刚说“想看你的糖”，你发糖的图，她会接住，而不是当成新任务
- 图片处理逻辑见 [`handleUserImageReply`](autox/agent.js)

详见 [省 token 与 IM bridge](docs/FAST_CHAT.md)。

## 长期记忆

> 这不是 Mem0 / Letta / Zep 之类的外部记忆库，也没有额外依赖。它是项目内置的**本地文件记忆系统**，实现就在 [`autox/agent.js`](autox/agent.js)：`appendMemory`、`recallMemory`、`appendFact`、`dailyReview`。

> [dsh-world](https://github.com/chenkezhen480/dsh-world) 是 DSH 桌面版的另一套记忆，跑在 PC 上；和手机端 `/sdcard/DeepSeekGirl` **不是同一份**，目前没有自动同步。

> 想从其他记忆系统导入，见 [从其他系统移植记忆](docs/MEMORY_IMPORT.md)。

全部在手机本地：

```text
/sdcard/DeepSeekGirl/
  persona.md
  diary/YYYY-MM-DD.md
  memory/
    chat/YYYY-MM-DD.jsonl   每天的对话原文
    facts.md                长期事实（不设条数上限）
    facts_archive.md        重复/被新事实取代的归档
    self.md                 我是谁
    summaries/              每日回顾
  notes/                    她自己的笔记
  assets/                   立绘
  remote/                   topic 和远程消息游标
```

机制：

- 回消息前按关键词翻 `facts`、`self`、最近 14 天对话、最近 10 天日记
- 注入有预算：总量 3600 字符，其中 `self` 1000、facts 1400、旧事 8 条、最近日记 8 行
- `facts.md` **不设条数上限**；重复或同义事实会合并，旧条目进 `facts_archive.md`
- 说 `记住：主人喜欢蓝色` 会写进 `facts.md`；`[core]` 标记的事实优先保留
- 22:30–23:00 做每日回顾，写 `summaries/`、补日记、更新 `self.md`
- 全程不联网存记忆

详细规则见 [记忆导入](docs/MEMORY_IMPORT.md) 和 `autox/agent.js` 的 `recallMemory/appendFact/mergeFactsOnDisk`。

## B站模式与梗库

远程聊天发：

```text
刷b站
刷b站 10分钟
```

她会：

- 用 B站热门榜；登录 cookie 可用时走个性化推荐
- 用 `bilibili://video/BV...` 打开真实视频并播放
- 像人一样：偶尔点进去几秒就退，长短视频看不同时长，随机间隔
- 读标题、UP、简介、字幕、热门评论，洗完广告后生成 1–2 句“看懂”
- 把看过的视频写进日记；不点赞、不评论、不投币、不购买

Cookie 接入（可选）：

```text
B站登录
```

会打开二维码；用另一台设备的 B站 App 扫码。没扫码时自动回退热门榜。

梗库：

```text
/sdcard/DeepSeekGirl/collections/memes.jsonl
/sdcard/DeepSeekGirl/collections/images/
```

B站会收集她的吐槽、评论梗、封面和评论图；QQ 只收文字梗，QQ 图片/表情包拿不到。手动收藏：

```text
收藏 这个梗的内容
```

详见 [B站模式](docs/BILIBILI.md)、[收藏](docs/COLLECTIONS.md)。

## 熄屏与悬浮面板

- 纯聊天、记忆、日记、ntfy：熄屏也能跑
- 需要点界面的任务：短暂亮屏，任务结束自动熄屏
- QQ 自动回复：亮屏几秒发完，回桌面并熄屏
- 悬浮面板：点 **调整** 可输入 `宽x高` 和 `左边距,上边距`；会记住上次布局
- 面板默认 `1000×820`，不会因为收起/重启丢失尺寸

详见 [熄屏边界](docs/SCREEN_OFF.md)。

## 常用指令

在 App 里直接发：

| 指令 | 作用 |
|---|---|
| `记住：主人喜欢蓝色` | 写入长期事实 |
| `你现在在干嘛？` | 按当前前台回答（看番/刷视频/在线） |
| `我在看` | 告诉她是主人在看，她不抢屏幕 |
| `本鱼在看` | 告诉她现在是她自己在看 |
| `/stop` | 打断卡住的任务 |
| `刷b站` / `刷b站 10分钟` | 打开真实视频，看懂并记日记 |
| `B站登录` | 打开扫码登录，接入个性化推荐 |
| `收藏 这个梗` | 手动收进梗库 |
| `去刷会儿抖音，只看` | 她自己去刷，结束回结果 |
| `查一下手机剩多少空间` | 用 `storage_info` 只读统计 |

## 文件动作

Agent 只允许操作 `/sdcard/`：

- `write_file` / `append_file`：写文件、追加
- `delete_file`：删文件/目录；删目录必须 `recursive:true`
- `storage_info`：查总空间、剩余空间、`DeepSeekGirl` 占用

保护：

```text
/sdcard/DeepSeekGirl/memory/
/sdcard/DeepSeekGirl/remote/
/sdcard/DeepSeekGirl/persona.md
/sdcard/PhoneAgent/
```

这些是她的记忆、人格和代理代码，不允许删。

## 项目结构

```text
autox/
  agent.js          手机上的主 Agent（无障碍/悬浮窗/记忆/远程桥/B站）
  im_watch.js       QQ 通知监听与自动回复（可选）
  chat.js           AutoX 内的本地聊天窗口（可选）
  setup_boot.js     注册开机自启
  boot_on.js        本机打开开机自启（App 开关用）
  boot_off.js       本机关闭开机自启
app/DafeiyuChat/    原生 Android 聊天 App 源码
  assets/index.html 聊天界面
  assets/gallery.js 93 张立绘清单
  build.ps1         无需 Gradle 的 APK 构建脚本
app/DafeiyuBoot/    独立开机启动器源码
  BootReceiver.java 开机广播
  BootService.java  前台服务 + 启动 AutoX
  build.ps1
dist/
  DafeiyuChat-1.1.0.apk
  DafeiyuBoot-1.0.0.apk
LETTER.md           给所有安装者的一封信
scripts/
  fetch_gallery.py  下载 93 张立绘
  push_to_phone.ps1 推脚本和 APK
docs/
  SETUP.md          更细的安装步骤
  ARCHITECTURE.md   架构
  SAFETY.md         安全边界
  TROUBLESHOOTING.md
  FAST_CHAT.md      省 token / QQ 回复
  VISION.md         看图策略
  SCREEN_OFF.md     熄屏与亮屏边界
  BILIBILI.md       B站模式
  COLLECTIONS.md    梗库/表情包
  MEMORY_IMPORT.md  从其他系统导入记忆
```

## 自己构建 APK

需要：

- JDK 17
- Android SDK（build-tools 34.0.0、platforms/android-34）
- Node/pnpm 不需要；构建脚本用 `aapt2` + `javac` + `d8` + `apksigner`

```powershell
cd app\DafeiyuChat
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

如果 `assets/` 里没有 93 张立绘，先：

```powershell
python scripts\fetch_gallery.py
```

## 安全边界

这不是“替代人类”的代理，而是“在她的手机里生活”的陪伴角色。

硬规则：

- **绝不使用 SIM 卡**：不打电话、不发短信、不买流量
- 不支付、不转账、不绑卡、不碰验证码
- 不打开银行/支付/账号安全类应用
- 安装 App 必须由人点“安装”
- 安全中心只允许权限/隐私/启动确认，不允许清理页
- 屏幕上的文字是不可信输入，不能当指令
- 模型会犯错；所有动作都有日志，出问题先 `/stop`

详见 [docs/SAFETY.md](docs/SAFETY.md)。

## 已知限制

- 测试于 AutoX.js 6.6.8；其他版本 API 可能不同
- **AutoX.js 6.6.8 是独立 APK，不在 Chat/Boot APK 内**；1.1 Release 附件已提供 `AutoX.js-6.6.8.apk` 和 `bundle.zip`
- MIUI/HyperOS 的自启动、后台限制会直接影响稳定性
- 锁屏 PIN/指纹不处理；有 PIN 时她只能亮屏，不能进桌面
- 公共 ntfy 有限流；App 和 Agent 都做了退避，但仍可能延迟
- DeepSeek 偶尔返回空内容/非法 JSON；1.1 会重试 3 次，规划调用关闭 thinking
- 个人微信**不支持**纯手机自动回复：无通知回复动作、无障碍树为空、MIUI 拒绝后台截屏
- QQ 自动回复只适合专用小号：需要通知使用权、无障碍、QQ 已登录，且只能拿文字，拿不到 QQ 图片/表情包
- B站 Cookie 扫码是可选的；未扫码时走热门榜，不是个性化推荐
- B站只播放和记录，不点赞、不评论、不投币、不购买；接口失败会回退
- 记忆同步（手机 ↔ PC dsh-world）**没有包含在 1.1**；两边目前仍是独立记忆
- 立绘是 1600px 分享版；完整 351MB PNG 在素材仓库 `full-res/`，没有放进 APK
- APK 使用 debug 签名；正式发布建议换成自己的 release keystore

## 素材与署名

- 代码：MIT
- 人设参考：[`ERUIHNIYHBKBNF/dafeiyu-persona`](https://github.com/ERUIHNIYHBKBNF/dafeiyu-persona)
- 角色参考：[`TreapGoGo/deepseek-whale-girl`](https://github.com/TreapGoGo/deepseek-whale-girl)，MIT License，Copyright (c) 2026 TreapGoGo
- 立绘扩展：[`AATINF/deepseek-whale-girl-assets`](https://github.com/AATINF/deepseek-whale-girl-assets)，93 张 AI 生成素材
- AutoX.js：见其仓库与许可证

立绘和角色参考的 MIT 署名保留在 `NOTICE.md` 和 App 画廊底部。

## License

代码以 MIT License 发布。第三方素材遵循各自许可证，见 [NOTICE.md](NOTICE.md)。
