# CS2 战绩（Cs2Stats）

安卓端 CS2 比赛数据助手：用手机把自己在 Steam 的**官匹战绩**抓下来，存进**你自己的网盘**，
在本地算出 **HLTV Rating 3.0 近似**与**总爆头率**。不依赖任何第三方统计平台，
数据不经过任何服务器，登录态只留在手机里。

| 总览 | 比赛列表 | 比赛详情 |
| :---: | :---: | :---: |
| <img src="docs/images/overview.jpg" width="240" alt="总览"> | <img src="docs/images/matches.jpg" width="240" alt="比赛列表"> | <img src="docs/images/detail.jpg" width="240" alt="比赛详情"> |

| 设置（外观） | 暗色总览（OLED 纯黑） |
| :---: | :---: |
| <img src="docs/images/settings.jpg" width="240" alt="设置 · 外观"> | <img src="docs/images/dark-overview.jpg" width="240" alt="暗色总览"> |

---

## 功能

- **采集官匹战绩**：登录 Steam 后一键抓取社区「我的游戏数据」页，逐页诊断、断点续抓，
  优先匹配 / 冲刺 / 街机等页签都支持；
- **每场评分**：比分、K/D/A、爆头率、★MVP、Score、Ping、存活率、排位/等待/时长，
  详情页还有 Rating 六子项拆解与双方 10 人战绩表；
- **总览聚合**：总场均 Rating、总爆头率（按总击杀加权）、K/D、KAST、ADR、胜率、近期走势；
- **Demo 解析**（可选，本机跑）：详情页按需下载本局回放，补齐页面拿不到的
  ADR / KAST / 多杀 / 开局杀 / 真实存活；比赛页还支持**批量队列**（限速、可取消）；
- **数据自己存**：网盘 WebDAV（坚果云 / Nextcloud / 群晖…）、自建 HTTP 后端、或纯本地示例数据，三种模式随时切；
- **外观**：主题跟随系统 / 浅色 / 深色三档，深色再分 **OLED 纯黑** 与 **LCD 深灰**两档底色；
- Material Design 3，Android 12+ 支持壁纸取色动态主题。

> **关于 Rating**：HLTV 没有公开 Rating 3.0 的精确系数，本项目按官方文章的**结构**
> （产出 60% / 代价 40%、六子项、以职业赛场基准归一）实现近似版，并用职业均值校准到 ≈1.00。
> 页面缺哪一项就自动剔除该项权重重归一化，详情页会标注「降级近似」——**拿不准的字段宁可留空，不编数**。

---

## 下载安装

1. 到 [Releases](../../releases) 页面下载最新 `app-release.apk`（约 14 MB）；
   源码打包（Source code zip / tar.gz）也在同一页；
2. 手机安装（Android **8.0 / API 26** 及以上）。这是用项目自有证书签名的 APK，
   系统可能提示「不允许安装未知应用」，允许本浏览器/文件管理器即可；
3. **原生解析库只编了 arm64-v8a**：绝大多数现代手机都是 arm64，可以正常解析 demo；
   其他架构（老设备 / 模拟器）应用本身能用，但「解析 demo」会因缺少 so 而不可用。

当前版本 **0.6.0**（versionCode 1）。校验值以 Release 说明里的 SHA-256 为准。

---

## 使用方法

### 1. 登录 Steam

设置页 →「**Steam 登录（应用内授权）**」：应用内 WebView 打开 Steam 授权页，
登录并确认后自动回到应用，回填你的 SteamID64（资料公开时还会带回昵称和头像）。
**登录态保存在手机本地**（WebView Cookie），随时可在设置页退出登录。

### 2. 抓取战绩

设置页 →「**从 Steam 抓取**」。抓的是你账号自己的社区数据页，逐页显示进度与诊断结果；
抓完自动写入本地缓存。可以随时重复抓取——**已解析过的 demo 成果不会被覆盖**。

### 3. 看数据

底部三个标签：

| 标签 | 内容 |
| --- | --- |
| **总览** | 总场均 Rating、总爆头率、K/D、KAST、ADR、胜率、近期 Rating 走势、最近比赛 |
| **比赛** | 全部 / 胜利 / 失败三个筛选，每场一行：地图、时间、比分、K/D/A、HS%、ADR、Rating |
| **设置** | Steam 登录、抓取、数据空间、外观、（预留的）FACEIT API Key |

点任意一场进**比赛详情**：比分与 Rating 六子项拆解、六个指标卡、双方战绩表、字段来源说明。

### 4. 解析 demo 补齐字段（可选）

官匹页面**没有** ADR / KAST / 多杀 / 开局杀，这些只在回放里有：

- **单场**：详情页 →「**下载并解析本局 Demo**」，前台服务下载 + 本机解析，进度在通知栏；
- **批量**：比赛页 →「**批量解析 N 场 demo**」，按开赛时间从早到晚排队（越早越接近过期），
  场间限速 3 秒、单场失败不中断、可随时取消；非 Wi-Fi 会先弹流量确认（每场约 70~150 MB）。

规则说明：

- 解析结果**只补空缺**，K / A / D / 爆头 / ★MVP / Score / Ping 一律以战绩页为准；
- 解析成功会自动归档到你的网盘；
- **回放链接约 30 天过期**，过期的场次就补不了了，建议抓到后尽快批量跑一遍；
- 解析完成后可删除手机上的临时回放文件（应用自己也会清理缓存）。

### 5. 选数据空间（设置页「数据空间」）

| 模式 | 说明 |
| --- | --- |
| **网盘同步**（推荐） | 用 WebDAV 网盘当数据库，见下一节 |
| **自建服务器** | 填自己的后端地址 + Token，接口见「自建服务器后端」 |
| **示例数据** | 什么都不连，看内置示例（固定种子）；一旦抓到真实数据，示例整体丢弃、绝不混算 |

网盘模式下：**上传**把 `matches.json` / `profile.json` 写进网盘，**下载同步**读回本地合并；
本地缓存兜底，断网、没配网盘也照样能看已抓到的比赛。

### 6. 外观（设置页顶部「外观」）

- 第一行：**跟随系统**（默认）/ 浅色 / 深色；
- 第二行（深色时生效）：**OLED 纯黑**（底色 `#000000`，省电、黑得彻底）/ **LCD 深灰**（层次更柔和）。

点完立刻生效，重启不丢；状态栏/导航栏图标明暗按应用设置走，白天强制深色也是白图标。

---

## 数据存哪：网盘 WebDAV（坚果云为例）

1. 坚果云**网页版** → 设置 → 安全选项 → 第三方应用管理 → 添加应用 → 得到「**应用密码**」；
2. 设置页「数据空间」选 **网盘同步**，填三项：

   | 字段 | 示例 |
   | --- | --- |
   | 网盘地址（WebDAV） | `https://dav.jianguoyun.com/dav/` |
   | 账号（登录邮箱） | 你的坚果云登录邮箱 |
   | 应用密码 | 上一步生成的（**不是**登录密码） |

3. 点「**测试连接**」：401 = 密码不对，成功即说明地址/账号没问题；
4. 之后每次解析完成会自动上传，也可以手动「上传 / 下载同步」。

支持任意标准 WebDAV（Nextcloud、群晖等同理）。**地址、账号、应用密码只保存在手机本地 DataStore，不上传、不打印、不进仓库。**

---

## 自建服务器后端（可选）

不想用网盘、或想多端共用一份数据时，把「数据空间」切到**自建服务器**即可。
后端只要实现下面 4 个接口，返回的 JSON 结构与网盘里的文件**完全一致**，随便用什么语言写：

```
GET    {base}/api/v1/profile              -> { steamId, personaName, avatarUrl, profileUrl }
GET    {base}/api/v1/matches?limit=100    -> { "matches": [ <match> ] }
GET    {base}/api/v1/matches/{id}         -> <match>
POST   {base}/api/v1/matches              -> 写入一场比赛（手机检测到新比赛后上传）
```

```jsonc
<match> = {
  "id": "...", "map": "mirage", "mode": "competitive",
  "startedAt": 1758000000, "durationSec": 2400,
  "myTeam": "TEAM_A", "myScore": 13, "enemyScore": 9,
  "hasRoundEconomy": false,
  "ranked": true, "waitTimeSec": 75,            // 可选：页面有 Ranked / Wait Time 行才给
  "replayUrl": "http://replay403.valve.net/730/<id>.dem.bz2",   // 可选：约 30 天过期
  "players": [ { "steamId": "...", "name": "...", "team": "TEAM_A",
                 "rounds": 22, "kills": 18, "deaths": 14, "assists": 4,
                 "hsKills": 9, "damage": 1720,
                 "kastRounds": 16, "survivedRounds": 7,
                 "multiKillRounds": 4, "openingKills": 3, "openingDeaths": 2,
                 "ping": 59, "mvps": 3, "score": 53 } ]   // 可选：记分板三列
}
```

约定：

- **认证**：请求头 `Authorization: Bearer <token>`，Token 在设置页填；
- **可选字段缺失是正常的**：评分侧会自动降级，不会崩溃；
- 后端地址留空 = 纯离线跑本地数据；
- 数据结构与 WebDAV 模式下的 `matches.json` 同构，两种模式可以互相迁移。

---

## 隐私

- Steam 登录态、网盘密码、后端 Token **只存在手机本地**（DataStore / Cookie），不经过第三方服务器；
- 上传到网盘/后端的内容只有比赛数据 JSON（`matches.json`、`profile.json`），不含任何账号凭据；
- 仓库源码与文档同样不含任何账号、密码、Cookie。

---

## 已知限制

- **官方没有 CS2 比赛历史 API**，数据来自你自己的社区数据页，页面结构变动时可能需要更新解析器；
- 官匹页面拿不到 ADR / KAST / 多杀 / 开局杀 → Rating 对应子项缺席并重新归一化，
  详情页会标「降级近似」；补齐只能靠 **demo 解析**（回放 30 天内有效）；
- 存活回合数在未解析 demo 前由「回合 − 阵亡」恒等反推（UI 标 `≈`），解析后改读真实值；
- Score / ★MVP / K / A / D 永远以战绩页为准，demo 不会覆盖；
- 后续可做：回合级经济落库（让 Round Switch 走真实口径）、采集自动化（定期抓 + 新比赛提醒）。

---

## 二次开发

```powershell
git clone https://github.com/atopos347/Cs2Stats.git
cd Cs2Stats
.\gradlew.bat testDebugUnitTest     # 43 个单测
.\gradlew.bat assembleDebug         # 调试包（applicationId 带 .debug 后缀）
.\gradlew.bat assembleRelease       # 发布包（需本机 keystore.properties，见下）
```

- **工具链**：JDK 17 · Gradle 8.9 · AGP 8.7.3 · Kotlin 2.1.0 · Compose BOM 2025.02.00 · minSdk 26 / targetSdk 35；
- **demo 解析器**是 Go 写的：`tools/demoparse/`（demoinfocs-golang 交叉编译成 `libcs2demo.so`），
  改动后用目录里的 `build-android.ps1` 重新编译；
- **发布签名**：把 `keystore.properties`（storeFile/storePassword/keyAlias/keyPassword）和 `keystore/*.jks`
  放到项目根目录（两者都已被 `.gitignore` 忽略，**不会进仓库**）；没有它们时 `assembleRelease` 出的是未签名包；
- 更细的实现记录、真机实测过程与踩坑，见 [`docs/开发笔记.md`](docs/开发笔记.md)。

---

## 许可证

本项目采用 **GNU General Public License v3.0**，全文见 [`LICENSE`](LICENSE)。

- Copyright (C) 2026 atopos347；
- 允许使用、修改、再分发（含商用），但**衍生作品必须以 GPL-3.0 同样条款开源**。

随二进制一并分发的第三方组件各有各的许可，不被本项目的 GPL 覆盖：

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| [demoinfocs-golang](https://github.com/markus-wa/demoinfocs-golang) v5.2.0 | demo 解析核心，编进 `libcs2demo.so` | MIT |
| AndroidX / Jetpack Compose / Material3 | 应用框架与 UI | Apache-2.0 |
| 其余 Go 依赖 | 解析辅助 | 逐个见 `go.mod` / `go.sum` |
