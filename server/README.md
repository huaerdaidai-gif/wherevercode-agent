# Hand 服务端安装（阶段 B）

本文档列出管理员在远程 Linux 主机终端上执行的基本步骤。App 侧不需要
SSH 凭据；请在你管理的主机和网络中配置远程连接。

## 前置

- 你管理的远程主机终端；配对时使用你自己的私有网络地址。
- Python 3.10+，pip 可用。
- 本仓库 `server/` 目录已放到 远程主机上（例如 `/path/to/wherevercode-agent/server/`）。

## 1. 安装依赖

```sh
pip3 install --user cryptography
```

## 2. 初始化 agent 注册表

```sh
cd server
python3 hand_server.py init-agents
```

默认只注册一个 agent：`example-agent` → `/workspace/example-project/run-agent.sh`。
如需改入口或加 agent，编辑
`~/.wherevercode/hand-server/agents.json`（格式见
`agents.example.json`），改完重启服务。

## 3. 授权文件夹

```sh
python3 hand_server.py grant example-agent /workspace/example-project
```

每个 agent 只能访问被授权的文件夹；未授权的调用会被拒绝。

## 4. 启动服务

```sh
python3 hand_server.py serve --host 0.0.0.0 --port 18743
```

首次启动生成服务端密钥对（`~/.wherevercode/hand-server/server_key.json`，
0600 权限），并打印指纹（SHA-256）。记下指纹，供第 5 步在 App 内核对。

建议用 systemd 或 tmux 保持运行；服务本身无守护进程逻辑。

## 5. 配对（App 添加设备时）

1. 在 App 里添加远程设备（填你自己的私有网络地址、
   端口 `18743`），App 会发一次 hello；此时服务端把该设备记为
   PENDING，不执行任何调用。
2. 在远程主机上查看待配对设备：

   ```sh
   python3 hand_server.py pair-list
   ```

3. 核对 device-id 无误后批准：

   ```sh
   python3 hand_server.py pair-approve <device-id>
   ```

4. 在 App 里核对服务端指纹与第 4 步打印的指纹一致后确认配对。
   指纹随时可查：

   ```sh
   python3 hand_server.py fingerprint
   ```

撤销配对：`python3 hand_server.py pair-revoke <device-id>`。

## 6. 验证（本地自测，不依赖 App）

```sh
python3 -m unittest test_hand_server -v
```

13 个测试：RFC 7748 向量、密封往返、凭证校验、hello/配对、
list/read/write、路径穿越拒绝、fake agent 运行、暂停/继续/停止、
改目标后旧快照拒绝。全部通过才算服务端本身可用。

## 常用命令

```sh
python3 hand_server.py fingerprint   # 服务端公钥指纹
python3 hand_server.py pair-list     # 客户端配对列表
python3 hand_server.py agents        # 已注册 agent 及其可用性
```

## 审计与日志

- 审计日志：`~/.wherevercode/hand-server/audit.jsonl`（只记元数据，
  不含凭证与密钥）。
- 服务日志：启动时的标准输出（监听地址、指纹、state 目录）。

## 运行前检查

请在你管理的设备和项目目录中确认 Agent 入口存在且可执行，并确认远程
主机可通过私有网络访问。服务会报告不可用状态，不会伪装运行成功。
