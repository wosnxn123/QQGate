# QQGate 部署指南 — NapCat 对接与常见排障

本文覆盖 QQGate 与 OneBot 11 协议端（推荐 NapCat）的完整对接，以及 Docker 部署的网络排障。

---

## 一、拓扑选择

| 拓扑 | QQGate 配置 | NapCat URL | 说明 |
|---|---|---|---|
| 同机（最常见） | `listen-host: 127.0.0.1` | `ws://127.0.0.1:6700/` | 端口不暴露，最安全 |
| 分离部署 | `listen-host: 0.0.0.0` + 强 token | `ws://<MC公网IP>:6700/` | 需放行防火墙/安全组 |
| MC 在 NAT 内 | `mode: forward-ws` + `forward-url` | （NapCat 建 WS 服务器） | 我方出方向连接 |
| Docker 同宿主机 | 默认 | `ws://<MC容器IP或容器名>:6700/` | 见下文网络排障 |

---

## 二、NapCat 配置（反向 WS，推荐）

### 1. 进入 WebUI

启动 NapCat 后日志会输出：

```
WebUi User Panel Url: http://127.0.0.1:6099/webui?token=xxxxx
```

浏览器打开（token 在 URL 里；忘记可查 `config/webui.json` 的 `token` 字段），扫码登录机器人 QQ。

### 2. 新建反向 WS

**WebUI → 网络配置 → 新建 → WebSocket 客户端**：

| 字段 | 填写 |
|---|---|
| 启用 | ✅ |
| URL | `ws://<MC服务器地址>:6700/` |
| Token | 与 QQGate `access-token` **逐字符一致** |
| 消息格式 | `string` 或 `array` 均可（QQGate 两种都能解析） |
| 心跳间隔 | 15000（默认） |
| 重连间隔 | 默认（NapCat 自动重连） |
| 上报自身消息 | 关 |

### 3. 验证

MC 控制台出现：

```
[QQGate] OneBot connected (self_id=机器人QQ号)
```

`/qqgateadmin status` 显示 `connected=true`。

### 4. 拉机器人进群

目标群 → 群设置 → 机器人 → 添加。然后 QQGate `config.yml`：

```yaml
groups:
  allowed: [群号]     # 踢出页显示的群号取第一项
```

`/qqgateadmin reload` 生效。

---

## 三、Forward-WS 模式（Lagrange 等）

QQGate 主动连接协议端，适合 MC 在内网、协议端在公网的拓扑。

1. NapCat WebUI → 网络配置 → 新建 → **WebSocket 服务器**，监听如 `0.0.0.0:3001`，token 与 QQGate 一致
2. QQGate：

```yaml
onebot:
  mode: forward-ws
  forward-url: "ws://<NapCat地址>:3001/"
  access-token: "同上"
  reconnect-seconds: 5   # 断线自动重连间隔
```

3. 重启服务器

---

## 四、Docker 网络排障

### 症状：`getaddrinfo ENOTFOUND <容器名>`

NapCat 解析不到 MC 容器的主机名——两个容器**不在同一 Docker 网络**，或容器名抄错。

```bash
# 1. 查 MC 容器与网络
docker ps
docker inspect <MC容器名> --format '{{json .NetworkSettings.Networks}}'

# 2. 查 NapCat 容器网络
docker inspect <NapCat容器名> --format '{{json .NetworkSettings.Networks}}'

# 3. 网络不一致 → 把 NapCat 接进 MC 的网络
docker network connect <MC的网络名> <NapCat容器名>

# 4. NapCat URL 改用容器名（同网络内自动 DNS 解析）
#    ws://<MC容器名>:6700/
```

### 备选方案（不想动网络）

| 方案 | NapCat URL 写法 | 前提 |
|---|---|---|
| 容器 IP 直连 | `ws://172.x.x.x:6700/` | 同网络；**容器重启 IP 可能漂移**，应急用 |
| 宿主机网关 | `ws://172.17.0.1:6700/` | MC 容器有 `-p 6700:6700` 映射，NapCat 在默认 bridge |
| 宿主机内网 IP | `ws://192.168.x.x:6700/` | 同上 |
| 公网 IP | `ws://公网IP:6700/` | 安全组放行 + **token 必须强** |

验证连通（宿主机执行）：

```bash
docker exec <NapCat容器> node -e "require('net').connect(6700,'<目标IP>').on('connect',()=>{console.log('OK');process.exit(0)}).on('error',e=>{console.log('FAIL',e.message);process.exit(1)})"
```

---

## 五、时区与中文（Docker）

踢出页 `{expire_time}` 默认跟随服务器系统时区。容器部署加环境变量：

```yaml
# docker-compose.yml
environment:
  - TZ=Asia/Shanghai        # 关键：Java 原生识别
  - LANG=zh_CN.UTF-8        # 控制台中文（镜像无此 locale 时用 C.UTF-8）
```

```bash
# docker run
docker run -e TZ=Asia/Shanghai -e LANG=zh_CN.UTF-8 ...
```

已在跑的容器（临时）：

```bash
docker exec -u root 容器名 ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
docker restart 容器名
```

`TZ` 只对新进程生效，改完必须重启。容器重建会丢，写进 compose 是长期正解。

---

## 六、AuthMe 联动

两者零依赖，同时安装时顺序由事件优先级保证（QQGate `@LOWEST` 先裁决）：

```
未绑定 → QQGate 踢出（到不了 AuthMe）
已绑定 → 放行 → AuthMe /register（首次）或 /login
```

建议：

- **离线服**：保留 AuthMe——离线 UUID 由用户名派生，改名即冒充，密码是最后防线
- **正版服**：Mojang 验证已保证身份，只装 QQGate 即可

---

## 七、快速故障对照

| 症状 | 原因与处理 |
|---|---|
| NapCat 反复重连失败 | URL 错 / 端口未放行 / IP 不可达（Docker 注意容器网络） |
| 连上秒断 code=401 | **token 不一致**——两边逐字符核对（多余空格也算错） |
| connected=true 但发码无反应 | 机器人不在 `allowed` 群；或 `allowed-self-id` 配错 |
| 群回复正常但无 @/引用 | NapCat 版本过旧，升级 |
| 运行一阵变 disconnected | 心跳超时：`heartbeat-timeout-seconds` 调大 |
| 私聊指令无反应 | `private.allow-bind: false`（默认）+ 机器人需加好友 |
| 踢出页时间不对 | `bind.time-zone` 设 `Asia/Shanghai`，或容器加 `TZ` |
| 玩家"连接中断"进不来 | 玩家侧代理/加速器掐断握手；或 `kick.delay-ms` 调 300~800 |
