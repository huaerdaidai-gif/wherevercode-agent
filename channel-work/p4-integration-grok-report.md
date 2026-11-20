# P4 平台适配器集成报告（Grok）

- 仓库：`huaerdaidai-gif/wherevercode-agent`
- 输入分支：`feat/channel-p4-awm-adapt-muse`
- 动手前 `git ls-remote`：`ab069529d865e7ebc16dd83882f607447ed94ae5`（与指定头 `ab069529` 一致）
- 本报告分支：`feat/channel-p4-integration-grok-20261121`（从上述头切出，只加本文件）
- 状态：**未上线。服务零改动，未重启，未建线上派发单。**

## 卡点（复验无法开始，按「不许带病上线」停住）

任务要求工作机是正在跑 `/wherevercode-platform/v5_platform.py` 的那台 Mac（`100.75.41.0:8443`，库 `/wherevercode-platform/data/v5.db`）。这次执行环境不是那台机器。

| 检查 | 结果 |
| --- | --- |
| `uname` | Linux x86_64，不是 Mac |
| `/wherevercode-platform` | 不存在 |
| `$HOME/wherevercode-platform` | 不存在 |
| 进程表里的 `v5_platform.py` | 没有 |
| 本机监听 | 没有 8443 |
| 对 `https://100.75.41.0:8443/` 的请求 | 无 HTTP 响应 |
| WhereverCode `list_my_tasks` | 空，没有可领的跨设备任务 |
| WhereverCode `project_run` | 拒绝：只能访问自己正在执行的跨设备任务 |

因此做不到：动手前备份 `v5_platform.py`、把适配器接进在跑的进程、把映射写到那台机器的 `~/wherevercode-platform/data/`、按原参数重启、线上建单再查、再重启验映射。

没有备份文件，因为目标文件不在这台机器上，不能造一份假备份。回滚点：**无。服务未被修改。** 不需要回滚。

## 输入分支实际内容（和任务描述有一处对不上）

`ab069529` 的说明是「P4 平台适配器 platform_adapter + 模拟派发整轮测试（13 项）」。

- 适配器：`server/awm/platform_adapter.py`。`PlatformAdapter` 文档写明票在内存：`self._tickets: dict[str, DispatchTicket] = {}`（约第 196 行）。`DispatchTicket` 含 `effect_key` / `message_id` / `task_id` / `target` / `assignee_id`。
- 同一文件里的仲裁库、证据库、中继库已经落在 `awm_dir` 下的 sqlite。缺的只是这张票表。
- `server/awm/tests` 共 **54** 项（adapter 13、store 13、statemachine 10、evidence 7、relay 6、keys 5）。在本环境 `python3 -m unittest discover -s server/awm/tests`：**54 通过，0 失败**（0.458s）。这是本机单测，不是 Mac 线上复验。
- **`channel-work/p4-muse-report.md` 不在这个头上**，全分支也搜不到 `CHANNEL-P4-AWM-DONE`。任务里写的三条缺口，能从代码对上两条：票只在内存；仓库里没有把适配器接进 `v5_platform.py`。人话路径按 SPEC-P0 留在状态机里（`human_required` 单向），本次没有改它。

## 接线方式

未接线。没有读到在跑的 `v5_platform.py`，不知道它的建单入口和现有进程参数（`--cert` / `--key` 的真实路径）。不猜参数、不改 host / 端口 / 数据库 / 证书。

## 落盘方案

未落盘。设计上票映射应单独写在数据目录（例如 `~/wherevercode-platform/data/dispatch_tickets.json`），与 `v5.db` 分开，重启再加载进 `PlatformAdapter._tickets` / `_by_message`。这一步要等能打开那台 Mac 上的服务源码再做，并和现有 54 项测试一起跑。本次没有改 `platform_adapter.py`。

## 复验实测

未做。下面四项都没有线上结果，不能写成通过：

1. 根端点 200
2. `tools/list`
3. 走适配器真实建一张派发单并查回状态
4. 再重启后 ticket 映射还在

## 边界核对

- 未改 App 代码，未动一期分支，未动 `main`
- 未碰 VPS
- 未改服务的 host、端口、数据库、证书（服务进程也没碰到）
- 未改大脑与频道客户端

要在那台 Mac 上重做：确认本进程能读到 `/wherevercode-platform/v5_platform.py` 和现行命令行，先按时间戳备份，再接线、落盘、用原参数重启，四项复验都过才算上线。现在不算。

CHANNEL-P4-INTEGRATION-DONE
