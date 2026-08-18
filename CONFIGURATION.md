# QQGate 配置手册

配置文件：`plugins/QQGate/config.yml`（首次启动生成）

改完后用 `/qqgateadmin reload` 热生效。**例外**：`onebot:` 整段（mode/host/port/token 等连接参数）在启动时建立连接，改动需重启服务器。

---

## onebot — OneBot 连接

```yaml
onebot:
  mode: reverse-ws
  listen-host: 0.0.0.0
  listen-port: 6700
  forward-url: "ws://127.0.0.1:3001"
  access-token: ""
  reconnect-seconds: 5
  heartbeat-timeout-seconds: 60
  allowed-self-id: 0
```

| 键 | 默认 | 说明 |
|---|---|---|
| `mode` | `reverse-ws` | `reverse-ws`：机器人主动连我方（NapCat/LLOneBot 推荐）；`forward-ws`：我方主动连机器人（Lagrange 常用） |
| `listen-host` | `0.0.0.0` | reverse-ws 监听地址。与机器人同机建议 `127.0.0.1`（不暴露公网） |
| `listen-port` | `6700` | reverse-ws 监听端口 |
| `forward-url` | `ws://127.0.0.1:3001` | forward-ws 模式的目标地址 |
| `access-token` | `""` | 鉴权令牌，必须与机器人端一致。留空 = 不鉴权（公网部署强烈不建议） |
| `reconnect-seconds` | `5` | forward-ws 断线重连间隔 |
| `heartbeat-timeout-seconds` | `60` | 超过该秒数未收到任何事件判定机器人离线（OneBot 默认心跳 15s，60s = 4 倍余量） |
| `allowed-self-id` | `0` | 只信任该机器人 QQ 号发来的事件，`0` = 不限制。防其他机器人误连 |

---

## groups — 群控制

```yaml
groups:
  allow-all: false
  allowed: [123456789]
  reply-in-source-group: true
```

| 键 | 默认 | 说明 |
|---|---|---|
| `allow-all` | `false` | `true` 时任何群都能执行绑定/解绑（忽略白名单）。默认关闭 |
| `reply-in-source-group` | `true` | 回复发回来源群 |

---
## admins — QQ 管理员

```yaml
admins:
  qq: [10001, 20002]
  respond:
    group: true
    private: true
```

| 键 | 默认 | 说明 |
|---|---|---|
| `qq` | `[]` | QQ 管理员白名单。这些 QQ 可在群/私聊执行管理员指令（`查`/`解绑`/`全解绑`/`绑定 <名> <QQ>`/`状态`/`帮助`）。空 = QQ 侧管理功能整体关闭 |
| `respond.group` | `true` | 群内是否响应管理员指令 |
| `respond.private` | `true` | 私聊是否响应管理员指令 |

管理员指令详解见 README 的指令表。非白名单 QQ 发送管理员语法按普通玩家消息处理（无权限提升）。


---

## private — 私聊

```yaml
private:
  allow-bind: false
```

| 键 | 默认 | 说明 |
|---|---|---|
| `allow-bind` | `false` | 私聊指令总开关。开启后私聊机器人发 `绑定 <码>` / `解绑` 均有效，回执走私聊（无 @、无引用） |

---

## bind — 绑定行为

```yaml
bind:
  code-length: 4
  expire-minutes: 5
  expire-display: both
  time-format: "HH:mm"
  time-zone: "default"
  refresh-on-rejoin: true
  max-per-qq: 1
  max-per-player: 1
  limit-policy: reject
  self-unbind: false
  cooldown-seconds: 10
  code-message: "..."
```

### 验证码

| 键 | 默认 | 说明 |
|---|---|---|
| `code-length` | `4` | 验证码位数，4~8（超范围自动收敛） |
| `expire-minutes` | `5` | 有效期（分钟），下限 1 |
| `refresh-on-rejoin` | `true` | 玩家再次进服时作废旧码生成新码（防码泄露堆积）。`false` 时未过期旧码沿用 |
| `code-message` | 见默认 | `/qqgate bind` 游戏内验证码回显模板（MiniMessage，占位符同 `kick.message`） |

### 有效期显示

| 键 | 默认 | 说明 |
|---|---|---|
| `expire-display` | `both` | `both` = 时长+时刻；`relative` = 仅时长；`absolute` = 仅时刻 |
| `time-format` | `HH:mm` | 绝对时刻格式（Java `DateTimeFormatter` 语法，如 `MM-dd HH:mm`） |
| `time-zone` | `default` | `default` 跟随服务器系统时区；或 IANA 名称如 `Asia/Shanghai`（不支持 `UTC+8` 写法） |

### 数量限制

| 键 | 默认 | 说明 |
|---|---|---|
| `max-per-qq` | `1` | 一个 QQ 最多绑几个游戏账号（一人多小号场景） |
| `max-per-player` | `1` | 一个游戏账号最多绑几个 QQ（一号多人/换号场景） |
| `limit-policy` | `reject` | 达上限时：`reject` = 拒绝并提示；`replace` = 挤掉最早的绑定（自动换绑） |

**推荐组合**：`max-per-qq: 2` + `max-per-player: 1` + `limit-policy: replace` —— 允许一个 QQ 管两个小号，玩家换 QQ 全自助。

### 其他

| 键 | 默认 | 说明 |
|---|---|---|
| `self-unbind` | `false` | 群内/私聊发 `解绑` 自助解绑（解最早绑定的一条，多发逐次解） |
| `cooldown-seconds` | `10` | 绑定指令冷却（按 QQ 计，错码也计入，防刷屏） |

---

## kick — 踢出页

```yaml
kick:
  delay-ms: 0
  message: "..."
```

| 键 | 默认 | 说明 |
|---|---|---|
| `delay-ms` | `0` | 断开前延迟（毫秒）。拦截点已为 `PlayerLoginEvent`，通常保持 0；极端网络偶发"连接中断"时再试 300~800 |
| `message` | 见默认 | 踢出页文案，MiniMessage 格式 |

**可用占位符**：

| 占位符 | 内容 |
|---|---|
| `{code}` | 验证码 |
| `{group}` | 群号（`groups.allowed` 第一项） |
| `{player}` | 玩家名 |
| `{expire_minutes}` | 有效期分钟数 |
| `{expire_time}` | 过期绝对时刻（按 `time-format`/`time-zone` 渲染） |
| `{expire_line}` | 组合好的有效期整行（按 `expire-display` 生成） |

---

## storage — 存储

```yaml
storage:
  file: bindings.json
  pretty-print: true
```

| 键 | 默认 | 说明 |
|---|---|---|
| `file` | `bindings.json` | 数据文件（相对 `plugins/QQGate/`）。原子写入：tmp + ATOMIC_MOVE |
| `pretty-print` | `true` | JSON 缩进，便于人工检查/备份 |

---

## messages — 群内回复文案

所有回复自动**引用触发消息**并 **@发送者**（私聊回执无引用/@）。`\n` 为换行。

| 键 | 触发场景 |
|---|---|
| `bound` | 绑定成功 |
| `replaced` | 绑定成功且挤掉旧绑定 |
| `already-bound` | 该 QQ 与该账号已是绑定关系（重复发码） |
| `wrong-code` | 验证码错误或已过期 |
| `code-used` | 验证码刚被使用 |
| `qq-full` | 该 QQ 绑定数达上限 |
| `player-full` | 该账号绑定数达上限 |
| `cooldown` | 指令冷却中 |
| `not-bound` | 解绑时无任何绑定 |
| `self-unbind-ok` | 自助解绑成功 |

**占位符**：`{at}` @发送者、`{player}` 游戏名、`{qq}` QQ 号、`{count}/{max}` 已绑/上限、`{remaining}` 剩余额度、`{old_player}` 被挤下的玩家、`{seconds}` 冷却秒数。

---

新增文案键：

| 键 | 触发场景 |
|---|---|
| `already-bound` | 重复绑定同一对（幂等） |
| `self-unbind-notfound` | 指定解绑的账号不在自己名下 |
| `self-unbind-list` | 无参解绑/查询的绑定列表 |
| `help` / `admin-help` | 玩家/管理员帮助 |
| `admin-lookup` / `admin-lookup-empty` | 管理员查询结果/未找到 |
| `admin-unbind-notfound` / `admin-unbind-exact-ok` / `admin-unbind-ambiguous` | 管理员解绑：未找到/精确成功/多条列表 |
| `admin-unbindall-ok` | 全解绑成功 |
| `admin-bind-ok` / `admin-bind-no-player` / `admin-bind-fail` | 管理员代绑：成功/玩家无记录/失败 |
| `admin-status` | 管理员状态查询 |

## debug

```yaml
debug: false
```

`true` 时向控制台输出收发的每条 OneBot 帧（排查协议问题用，平时关闭避免刷屏）。

---

## 数据文件 bindings.json

结构（数组，每条一个绑定）：

```json
[
  {
    "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "name": "Steve",
    "qq": 10001,
    "boundAt": 1700000000000
  }
]
```

- **uuid** 是绑定锚点（离线服改名 = 新 uuid = 需重新绑定）
- `name` 仅作展示，不参与裁决
- 手动编辑需停服（运行时会被内存态覆盖回写）
