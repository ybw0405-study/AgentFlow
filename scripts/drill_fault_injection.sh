#!/usr/bin/env bash
# =============================================================================
# 批次 4 / 故障注入脚本骨架 — AgentFlow
# -----------------------------------------------------------------------------
# 三类注入（方案第 6 节批次 4）：
#   redis-halfopen   Redis TCP 半开 -> 租约 renew 不永久阻塞
#   mq-backlog       MQ 堆积        -> 事件投影 / 重投 / 终态不回写
#   rolling-restart  实例滚动关停    -> shutdown 三级收口、在途任务 drain（B4）
#
# 【脚手架定位 / 诚实边界】
#   * 这是可执行骨架，**本机未做真实故障演练**：没有真实实例/流量，
#     没有产生任何演练记录或观测结论。
#   * 默认 DRY-RUN：只打印「前置检查 / 注入命令 / 观测点 / 恢复命令 / 判据」，
#     不执行任何 docker / redis-cli / kill 命令。
#   * 只有显式 --apply 才会执行；且必须自带 --target。
#   * **不碰 AgentFlow 任何业务文件**；本文件为新增脚手架，不改现有脚本/配置。
#
# 运行（Windows Git Bash）：
#   bash drill_fault_injection.sh redis-halfopen                    # dry-run
#   bash drill_fault_injection.sh mq-backlog --apply --target agentflow-rmq-broker
#   bash drill_fault_injection.sh rolling-restart --apply --target agentflow-engine
# =============================================================================
set -euo pipefail

APPLY=0
TARGET=""
DURATION=30
case_name="${1:-}"
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --target) TARGET="${2:-}"; shift ;;
    --duration) DURATION="${2:-30}"; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { echo "$*"; }
run() {
  if [ "$APPLY" = 1 ]; then
    echo "  [EXEC] $*"
    # shellcheck disable=SC2086
    eval "$*"
  else
    echo "  [dry-run] 将执行: $*"
  fi
}

say "============================================================================"
say "批次4 故障注入骨架 (AgentFlow) | case=$case_name apply=$APPLY target=${TARGET:-<none>} duration=${DURATION}s"
say "本机未做真实故障演练；本脚本默认只打印计划，--apply 才会动真实目标。"
say "============================================================================"

case "$case_name" in
  redis-halfopen)
    say "目的：Redis 半开（连接不 reset、既不抛也不返回）时，lease.renew/release 不永久阻塞。"
    say "前置：redis client 设 socket_timeout/socket_connect_timeout；renew/held_by_me/release 用 wait_for 包裹；"
    say "      dispatcher finally 的 await 加超时与 return_exceptions。"
    say "注入思路（择一）："
    say "  1) redis-cli DEBUG SLEEP <秒>            # 阻塞 Redis 主线程"
    say "  2) docker network disconnect <net> <ct>  # 半开网络分区"
    say "  3) iptables DROP 已建立连接回包           # 最贴近 TCP 半开"
    run "redis-cli -h ${TARGET:-127.0.0.1} DEBUG SLEEP ${DURATION}"
    say "观测点："
    say "  - renew() 是否在 wait_for 超时内返回，而非让 _run 卡在 finally"
    say "  - _tasks 是否清空、租约是否 release、start_sweep 能否接管（不是永久僵死）"
    say "  - /internal/health readiness 是否探 Redis 并返回 503（，LB 摘除）"
    say "  - 不出现误导性 'worker crashed' 刷屏"
    say "恢复：DEBUG SLEEP 到期 / network connect / 删除 iptables 规则"
    say "判据：接管时延 < SLO（见 drill_slo_alerts.yaml）；无永久僵死执行；恢复后 PING 通。"
    ;;

  mq-backlog)
    say "目的：MQ 堆积 / 迟到事件下，终态不可逆、投影单调（I1）。"
    say "前置：记录基线堆积深度；确认事件流 last_seq 单调、execution_tracker 终态守卫。"
    say "注入思路：暂停 EventProjector 消费 / 批量投递事件，制造堆积与乱序重放。"
    run "docker pause ${TARGET:-agentflow-workflow} || true"
    say "  # 上一条暂停 workflow 容器（projector consumer 所在进程）；如需投递乱序/重复事件，"
    say "  # 用项目自带 e2e 的 MQ 客户端注入，或对 af-audit-event topic 直接投递测试消息。"
    say "  # TODO(可选)：用真实 RocketMQ SDK 投递含乱序/重复的 N 条事件（本骨架默认只做「暂停消费」注入）。"
    say "观测点："
    say "  - 终态后迟到的非终态事件不得把 tracker 回写成 OPEN"
    say "  - markTerminal 的 WHERE 带前置状态，CANCELLED 不被改回 SUCCEEDED"
    say "  - 恢复消费后 last_seq 单调、投影终态与事件流一致"
    say "恢复：docker unpause ${TARGET:-agentflow-workflow}；清理本次注入的测试事件（勿动业务 topic 存量）"
    say "判据：终态零回写、投影零漂移、堆积回落到基线。"
    ;;

  rolling-restart)
    say "目的：SIGTERM 滚动发布时 shutdown 三级收口，在途执行先 drain 再关 store/redis（B4/I6）。"
    say "前置：至少 2 个实例、注入一个真实在跑任务（不要空 _tasks）、LB 摘流、记录 digest。"
    say "注入思路：逐个实例 SIGTERM，观察在途任务收口顺序。"
    run "docker stop --signal=SIGTERM --time=${DURATION} ${TARGET:-agentflow-engine}"
    say "  # 逐个实例发 SIGTERM；多实例时重复对下一个 --target 执行，间隔 ${DURATION}s"
    say "观测点："
    say "  - shutdown 顺序：置停止接收 -> gather(*_tasks, return_exceptions=True) -> sweep/gate -> 才关 store/redis"
    say "  - 在途执行不撞已关闭连接池、不出现 'Task was destroyed but it is pending!'"
    say "  - 租约在 TTL 内被正常接管（不误导为 worker crashed）"
    say "  - preview 执行不因重启被恢复扫描续跑"
    say "  - 新实例 readiness 变绿后才接流量"
    say "恢复：按 digest 拉起实例（docker start ${TARGET:-agentflow-engine}）；确认 /internal/health readiness 200"
    say "判据：零在途丢失、store/redis 在任务结束后才关闭、租约接管时延 < SLO。"
    ;;

  *)
    say "用法: bash drill_fault_injection.sh {redis-halfopen|mq-backlog|rolling-restart} [--apply] [--target NAME] [--duration N]"
    exit 2
    ;;
esac

say "----------------------------------------------------------------------------"
if [ "$APPLY" = 1 ]; then
  say "注意：本次为 --apply，但骨架内多数注入点是 TODO 占位，未真正改动任何真实实例。"
else
  say "DRY-RUN 结束：以上全部为计划，未执行任何注入命令，未产生故障演练记录。"
fi
exit 0
