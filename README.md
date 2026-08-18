# QQGate

Minecraft QQ 绑定门禁插件 —— 玩家进服前须完成 QQ 绑定，适配 **Paper / Folia / Canvas**（区域多线程核心）。

```
玩家进服（未绑定）→ 被踢出并显示「绑定 XXXX」验证码
        ↓
玩家在 QQ 群发送「绑定 XXXX」（带不带空格均可）
        ↓
机器人（OneBot 11）回复绑定结果
        ↓
玩家重新进服 → 放行 → AuthMe 注册/登录
```

## 特性

- **Folia 兼容**：`folia-supported: true`，WS 线程零 Bukkit 调用，游戏侧操作全部经区域调度器
- **OneBot 11 标准协议**：反向 WebSocket（推荐，NapCat/LLOneBot）+ 正向 WebSocket（Lagrange）双模式
- **验证码安全**：4~8 位、5 分钟时效、一码一用、重连刷新、码只在踢出屏出现
- **限额策略**：`max-per-qq` / `max-per-player` 双向限额，`reject`（拒绝）或 `replace`（自动挤掉最早的，换绑自助）
- **私聊绑定**：可选开启私聊机器人完成绑定
- **全群放行**：可选忽略群白名单
- **稳定踢出屏**：拦截点为 `PlayerLoginEvent`（客户端已就绪），断开包必然渲染，不出现"连接中断"
- **零外部依赖**：单 jar，Gson 用服务端自带的，仅 shade 了 Java-WebSocket（已 relocate）
- **数据可读**：`bindings.json` 原子写入（tmp + move），支持 pretty-print

## 环境要求

| 项 | 要求 |
|---|---|
| 服务端 | Paper 1.20+ / Folia / Canvas（或任何 folia-supported 兼容核心） |
| Java | 21+ |
| 协议端 | 任一 OneBot 11 实现（推荐 [NapCat](https://github.com/NapNeko/NapCatQQ)） |
| 可选 | AuthMe（绑定后密码验证，形成双保险） |

## 快速开始

### 1. 安装

把 [`QQGate-x.x.x.jar`](../../releases) 放进 `plugins/`，启动服务器生成 `plugins/QQGate/config.yml`。

### 2. 配置 OneBot 连接

编辑 `config.yml`（NapCat 与 MC 同机）：

```yaml
onebot:
  mode: reverse-ws
  listen-host: 127.0.0.1
  listen-port: 6700
  access-token: "一段强随机字符串"
groups:
  allowed: [你的群号]
```

### 3. 配置 NapCat

WebUI → 网络配置 → 新建 → **WebSocket 客户端**（反向 WS）：

| 字段 | 值 |
|---|---|
| URL | `ws://<MC服务器地址>:6700/` |
| Token | 与 `access-token` 完全一致 |

保存后 MC 控制台出现 `OneBot connected (self_id=机器人QQ号)` 即通。

### 4. 测试

未绑定账号进服 → 踢出屏显示验证码 → 群内发 `绑定 XXXX` → 机器人回复成功 → 重连进服。

详细部署（含 Docker 网络排障）见 [DEPLOY.md](DEPLOY.md)，全部配置项见 [CONFIGURATION.md](CONFIGURATION.md)。

## 命令

### 玩家 `/qqgate`（人人可用）

| 子命令 | 说明 |
|---|---|
| `bind` | 获取自己的验证码（游戏内聊天栏显示，5 秒防刷冷却；已达上限会拒绝） |
| `info` | 查看自己的绑定（QQ 打码显示） |
| `help` | 帮助 |

### 管理员 `/qqgateadmin`（权限 `qqgate.admin`，OP 默认）

| 子命令 | 说明 |
|---|---|
| `status` | OneBot 连接状态、绑定数、待验证码数 |
| `codes` | 当前所有待验证码及剩余秒数 |
| `lookup <玩家名\|QQ号>` | 双向查询绑定 |
| `unbind <玩家名\|QQ号>` | 解除绑定 |
| `bind <玩家名> <QQ号>` | 代绑（跳过验证码，仍走限额裁决） |
| `reload` | 热重载配置（连接段除外） |

### 权限

| 权限 | 说明 | 默认 |
|---|---|---|
| `qqgate.admin` | 管理命令 | OP |
| `qqgate.bypass` | 跳过绑定检查 | OP |

## 群内指令

| 指令 | 效果 |
|---|---|
| `绑定 4823`（空格可选，支持全角空格/冒号） | 完成绑定 |
| `解绑`（需 `bind.self-unbind: true`） | 解除自己最早的绑定 |

机器人的所有回复自动**引用触发消息**并 **@发送者**。

## 从源码构建

```bash
# 需要 JDK 21+ 与 Gradle 9.x（仓库无 wrapper 时自行安装）
gradle shadowJar
# 产物: build/libs/QQGate-<version>.jar
```

运行测试：

```bash
gradle test
```

## 项目结构

```
src/main/java/dev/qqgate/
├── QQGatePlugin.java        # 装配 + 生命周期
├── BotConfig.java           # 纯 Java 配置窄接口（可脱离服务器单测）
├── bind/                    # BindSettings · BindStore(持久化) · BindService(裁决核心)
├── listener/JoinListener    # PlayerLoginEvent 拦截
├── onebot/                  # OneBotEndpoint(WS双模式) · ChatMessageHandler(群/私聊)
├── command/                 # QQGateCommand(玩家) · QQGateAdminCommand(管理)
└── util/Schedulers          # Folia 调度纪律
```

核心逻辑（`bind/` 与 `onebot/`）**不依赖 Bukkit**，绑定裁决、验证码生命周期、限额策略全部可用纯 JUnit 测试覆盖（29 个用例，含真实 WebSocket 握手的端到端模拟）。

## 常见问题

<details>
<summary><b>踢出屏偶尔显示"连接中断"？</b></summary>

已在 1.0.0 修复：拦截点从 `AsyncPlayerPreLoginEvent` 改为 `PlayerLoginEvent`，客户端在收到断开包前已完成登录握手，渲染稳定。若极端网络下仍偶发，可在 `kick.delay-ms` 加 300~800ms 延迟。
</details>

<details>
<summary><b>机器人回复显示原始 CQ 码 <code>[CQ:at,qq=...]</code>？</b></summary>

已在 1.0.0 修复：移除了发送帧的 `auto_escape`，CQ 码（引用/@）正常渲染为气泡。
</details>

<details>
<summary><b>NapCat 报 getaddrinfo ENOTFOUND？</b></summary>

Docker 网络问题：NapCat 容器与 MC 容器不在同一网络，或容器名抄错。见 [DEPLOY.md](DEPLOY.md#docker-网络排障)。
</details>

<details>
<summary><b>踢出页时间不对？</b></summary>

`bind.time-zone` 默认跟随服务器系统时区。Docker 部署加 `-e TZ=Asia/Shanghai`，或配置里显式设置 IANA 时区名。
</details>

<details>
<summary><b>不装 AuthMe 行吗？</b></summary>

行，两者零依赖。但离线服建议保留 AuthMe：离线 UUID 由用户名派生，改名即可冒充，密码是最后一道防线。正版服可只装 QQGate。
</details>

## 开源协议

[GPL-3.0](LICENSE)

## 致谢

- [OneBot 11](https://github.com/botuniverse/onebot-11) 协议标准
- [NapCatQQ](https://github.com/NapNeko/NapCatQQ) 推荐的协议实现
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)（MIT，已 shade）
- [PaperMC](https://papermc.io/) / [Folia](https://papermc.io/software/folia)
