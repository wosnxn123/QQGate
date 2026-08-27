# QQGate 配置手册

配置文件：`plugins/QQGate/config.yml`（首次启动生成）

改完后用 `/qqgateadmin reload` 热生效。**例外**：`onebot:` 整段（mode/host/port/token 等连接参数）在启动时建立连接，改动需重启服务器。

---

## config-version — 配置版本与自动升级

`config-version` 是配置结构版本号，**勿手改**。启动时插件拿它与内置模板比对：

- 用户版本落后 → 自动补齐模板里新增的键（取默认值），你改过的值一律保留，版本号对齐到模板。
- 升级前自动备份原文件为 `config.yml.bak-<yyyyMMdd-HHmmss>`（同目录）。
- **升级会重写整个文件：你自己加的注释会丢失**（键值不受影响）。新增项的说明注释见仓库 `src/main/resources/config.yml`。
- 版本已是最新 → 完全不动文件。

---

## onebot — OneBot 连接

```yaml
onebot:
  mode: reverse-ws
  listen-host: 0.0.0.0
  listen-port: 6700
  forward-url: "ws://127.0.0.1:3001"
  access-token: ""
  allow-insecure-bind: false
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
| `allow-insecure-bind` | `false` | 安全阀：reverse-ws 模式下 `access-token` 为空、且 `listen-host` 不是回环地址（`127.0.0.1`/`::1`/`localhost`）时，插件**拒绝启动监听**并在控制台报错——不然任何人连上来都能伪造管理员指令。`true` = 明知风险强行启动（仅限可信内网） |
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
| `allow-all` | `false` | `true` 时任何群都能执行全部指令（功能上忽略白名单）。踢出页显示推荐群（若填）或「机器人所在任意群」 |
| `allowed` | `[123456789]` | 群白名单：只响应这些群的一切指令；**踢出页显示全部白名单群**。`allow-all: true` 时失效 |
| `recommended` | `""` | 推荐群：**仅 allow-all 开启时生效**——踢出页显示「群号 ★推荐（或任意群）」作官方入口；白名单模式下不参与显示与功能；留空 = allow-all 时显示通用提示 |
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
| `qq` | `[]` | QQ 管理员白名单。这些 QQ 可在群/私聊执行管理员指令（`查`/`解绑`/`全解绑`/`绑定 <名> <QQ>`/`拉黑`/`解拉黑`/`拉黑列表`/`状态`/`帮助`）。空 = QQ 侧管理功能整体关闭 |
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
| `refresh-on-rejoin` | `true` | 玩家再次进服（重连）时作废旧码、生成新码并刷新有效期（防码泄露堆积）。`false` = 未过期的旧码沿用 |
| `code-message` | 见默认 | `/qqgate bind` 游戏内验证码回显模板（MiniMessage，占位符同 `kick.message`） |

### 有效期显示

| 键 | 默认 | 说明 |
|---|---|---|
| `expire-display` | `both` | `both` = 时长+时刻；`relative` = 仅时长；`absolute` = 仅时刻 |
| `time-format` | `HH:mm` | 验证码过期时刻格式（Java `DateTimeFormatter` 语法，如 `MM-dd HH:mm`）。只管踢出页/验证码回显；列表时间戳归 `display.list-time-format` |
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

## display — 显示格式

```yaml
display:
  list-time-format: "yyyy-MM-dd HH:mm"
```

| 键 | 默认 | 说明 |
|---|---|---|
| `list-time-format` | `yyyy-MM-dd HH:mm` | 列表条目时间格式：绑定列表/黑名单/管理查询等所有列表里 `{time}` 的格式（Java `DateTimeFormatter` 语法）。只管列表；验证码过期时刻归 `bind.time-format`，时区共用 `bind.time-zone` |

---

## kick — 踢出页

```yaml
kick:
  delay-ms: 0
  op-skip-bind-check: true
  message: "..."
```

| 键 | 默认 | 说明 |
|---|---|---|
| `delay-ms` | `0` | 断开前延迟（毫秒）。拦截点已为 `PlayerLoginEvent`，通常保持 0；极端网络偶发"连接中断"时再试 300~800 |
| `op-skip-bind-check` | `true` | OP 无需绑定 QQ 即可进服（跳过绑定拦截）；`false` = OP 也要绑定。QQ 黑名单与名字封禁对 OP 同样生效，不受此开关豁免 |
| `banned-message` | 见默认 | 被拉黑 QQ 名下账号进服的踢出页（不显示验证码）；`{reason}` 为拉黑原因，按**纯文本**显示（内容中的 `<tag>` 原样出现，不影响模板样式） |
| `name-banned-message` | 见默认 | 同名封禁（该名字曾绑定被拉黑QQ）进服的踢出页；`{reason}` 同上 |
| `message` | 见默认 | 踢出页文案，MiniMessage 格式 |

**可用占位符**：

| 占位符 | 内容 |
|---|---|
| `{code}` | 验证码 |
| `{group_line}` | 群引导整行：白名单模式列出全部白名单群号（有推荐群则置顶标 ★）；`allow-all` 时为「机器人所在任意群」提示语；私聊绑定开启时追加提示行 |
| `{group}` | 单个群号（推荐群 > 白名单第一项；`allow-all` 时为提示文本）。旧模板兼容用，新模板建议用 `{group_line}` |
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
| `file` | `bindings.json` | 绑定数据文件名，相对 `plugins/QQGate/`；也接受绝对路径。非法路径或越出数据目录时回退默认名。原子写入：tmp + ATOMIC_MOVE |
| `pretty-print` | `true` | JSON 缩进，便于人工检查/备份 |

QQ 黑名单文件 `banned_qqs.json` 固定与它同目录。

**数据文件损坏/不可读时**：插件把原文件改名保留为 `<原名>.corrupt-<yyyyMMdd-HHmmss>`，以空数据启动并在控制台告警（不会静默清空后覆盖，绑定不会被永久丢弃）。人工修好后改回原名再 `/qqgateadmin reload`。

---

## messages — 群内回复文案

所有回复自动**引用触发消息**并 **@发送者**（私聊回执无引用/@）。`\n` 为换行。共 43 个键。

### 玩家文案

| 键 | 触发场景 |
|---|---|
| `bound` | 绑定成功 |
| `replaced` | 绑定成功且挤掉旧绑定（`limit-policy: replace`） |
| `already-bound` | 该 QQ 与该账号已是绑定关系（重复发码） |
| `wrong-code` | 验证码错误或已过期 |
| `code-used` | 验证码刚被使用 |
| `qq-full` | 该 QQ 绑定数达上限 |
| `player-full` | 该账号绑定数达上限 |
| `cooldown` | 指令冷却中 |
| `qq-banned` | 被拉黑 QQ 尝试绑定（`{reason}` 无原因时为空串） |
| `not-bound` | 查询/解绑时名下无绑定 |
| `self-unbind-ok` | 自助解绑成功 |
| `self-unbind-notfound` | 指定解绑的账号不在自己名下 |
| `self-unbind-list-header` / `-item` / `-hint` | 玩家绑定列表：头/每账号一条条目/尾提示（尾提示仅 `bind.self-unbind` 开启时拼接）。旧键 `self-unbind-list` 已删除 |
| `qqban-ok` | 拉黑成功主句（旧键 `qqban-ok` 的 `{result}` 拆解） |
| `qqban-ok-locked` | 拉黑成功的名下封锁段；仅名下有账号时拼接 |
| `qqunban-ok` | 解除拉黑成功（`{qq}` = 被解除的 QQ） |
| `qqunban-none` | 解拉黑时该 QQ 本就不在黑名单 |
| `admin-bind-banned` | 管理员代绑时目标 QQ 已被拉黑 |
| `qqbans-empty` | 黑名单为空 |
| `qqbans-list-header` / `-item` | 黑名单列表：头/每条黑名单一条条目。旧键 `qqbans-list` 已删除 |
| `help` | 玩家帮助（解绑行按 `bind.self-unbind` 开关由插件动态追加） |

### 管理员文案

| 键 | 触发场景 |
|---|---|
| `admin-help` | 管理员帮助 |
| `admin-lookup-banned-note` | 查黑名单 QQ 时拼在结果首行的警示（无 `{at}`） |
| `admin-lookup-qq-empty` / `-qq-header` / `-qq-item` | 按 QQ 查询：无绑定/头/条目（条目列玩家名） |
| `admin-lookup-player-header` / `-player-item` | 按玩家名查询：头/条目（条目列 QQ） |
| `admin-lookup-empty` | 按玩家名查询无绑定 |
| `admin-unbind-notfound` | 单参解绑/全解绑无结果（`{target}` = 调用方拼好的标签，如 "QQ 12345"） |
| `admin-unbind-notfound-exact` | 双参精确解绑未找到该组合 |
| `admin-unbind-exact-ok` | 解绑成功（单条直解/精确解绑共用；`{count}` 为剩余条数，可为 0） |
| `admin-unbind-ambiguous-header` / `-item` / `-hint` | 多条绑定选择列表：头/条目/尾提示。旧键 `admin-unbind-ambiguous` 已删除 |
| `admin-unbindall-ok` | 全解绑成功（`{detail}` = 被清清单，"、"连接） |
| `admin-bind-ok` | 代绑成功（挤下旧绑定时由代码追加"（挤下 X）"尾巴） |
| `admin-bind-no-player` | 代绑时目标玩家无既有绑定记录、无法定位 UUID |
| `admin-bind-fail` | 代绑兜底失败 |
| `admin-status` | 状态查询（`{mode}/{connected}/{self_id}/{binds}/{codes}` 五项独立字段） |

以上四组列表键取代了旧单键 `self-unbind-list`、`qqbans-list`、`admin-lookup`、`admin-unbind-ambiguous`（v8→v9 已删除，旧自定义文案需按新键重新拆分）。

**全局占位符**：`{at}`（群回复为 at 码+换行，私聊为空）与 `{sender}`（发送者 QQ）由插件自动注入，属于每个键，无需写进模板。

**业务占位符**：`{player}` 游戏名、`{qq}` 业务目标 QQ（**绝不表示发送者**——要显示发送者必须写 `{sender}`）、`{count}/{max}` 已绑/上限、`{remaining}` 剩余额度、`{old_player}` 被挤下的玩家、`{seconds}` 冷却秒数、`{target}` 查询/解绑目标（原样输入）、`{index}` 列表条目序号（从 1 开始）、`{time}` 预渲染时间字符串（格式由 `display.list-time-format` + `bind.time-zone` 控制）、`{detail}` 被清绑定清单（"、"连接）、`{reason}` 封禁原因片段（无条件时为空串，不留残括号；括号等装饰由插件按各键约定自带）、`{names}` 名下账号名清单（"、"连接）、`{mode}/{connected}/{self_id}/{binds}/{codes}` 状态五字段。

模板中出现未声明的占位符不会被替换，会原样显示给用户；插件启动时会校验并告警。

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
