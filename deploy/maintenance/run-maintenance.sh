#!/bin/bash
# deploy/maintenance/run-maintenance.sh
#
# 分区 / 保留策略的调度 runner。
# 本脚本不实现任何 SQL 语义，只负责：定时调用 scripts/db 下既有的 4 个脚本，
# 把连接目标打到 stdout（容器日志驱动会收集 stdout），并保证单个任务失败不影响其余任务。
#
# 用法：
#   run-maintenance.sh once <create|create-audit|drop|retention|all>   # 跑一个任务后退出（k8s CronJob 用）
#   run-maintenance.sh loop                                           # 常驻循环（compose maintenance profile 用）
#
# 环境变量（与既有脚本的约定完全一致，缺省值也一致）：
#   DB_HOST / DB_PORT / DB_USER / DB_PASSWORD   -> postgres 连接（默认 localhost / 5432 / admin / admin123）
#   CH_HOST / CH_PORT / CH_USERNAME / CH_PASSWORD -> clickhouse 连接（默认 localhost / 8123 / default / 空）
#   MAINTENANCE_SCRIPTS_DIR   覆盖脚本根目录（内含 partition/ 与 retention/），默认自动探测
#   MAINTENANCE_DAY_SECONDS   loop 模式每轮 sleep 秒数（默认 86400，即一天），用于快速冒烟
#   MAINTENANCE_MONTHLY_DAYS  月度周期（create + create-audit）的天数，默认 30
#   MAINTENANCE_WEEKLY_DAYS   周度周期（drop + retention）的天数，默认 7
#   MAINTENANCE_CONNECT_TIMEOUT 连通性校验的超时秒数，默认 5
#
# 说明：apply-retention.sh 自己带 ClickHouse 探活（curl 缺失或连不上时只跳过 DWH 那一段，
# PostgreSQL 侧的保留策略照常执行），本 runner 不去干预这个自保护逻辑。

set -u

# ---------------------------------------------------------------- 连接参数
# 缺省值与 4 个既有脚本保持一致；导出后子脚本继承同一份配置。
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-admin}"
DB_PASSWORD="${DB_PASSWORD:-admin123}"
CH_HOST="${CH_HOST:-localhost}"
CH_PORT="${CH_PORT:-8123}"
CH_USERNAME="${CH_USERNAME:-default}"
CH_PASSWORD="${CH_PASSWORD:-}"
export DB_HOST DB_PORT DB_USER DB_PASSWORD CH_HOST CH_PORT CH_USERNAME CH_PASSWORD

MAINTENANCE_DAY_SECONDS="${MAINTENANCE_DAY_SECONDS:-86400}"
MAINTENANCE_MONTHLY_DAYS="${MAINTENANCE_MONTHLY_DAYS:-30}"
MAINTENANCE_WEEKLY_DAYS="${MAINTENANCE_WEEKLY_DAYS:-7}"
MAINTENANCE_CONNECT_TIMEOUT="${MAINTENANCE_CONNECT_TIMEOUT:-5}"

# 任务名 -> 相对脚本根目录的脚本路径
SCRIPT_CREATE="partition/create-partitions.sh"
SCRIPT_CREATE_AUDIT="partition/create-audit-partitions.sh"
SCRIPT_DROP="partition/drop-old-partitions.sh"
SCRIPT_RETENTION="retention/apply-retention.sh"

SCRIPT_ROOT=""

# ---------------------------------------------------------------- 小工具
log() {
    printf '[maintenance] %s\n' "$*"
}

# ISO-8601 时间戳；GNU date 支持 -Iseconds，BSD/macOS 退回到等价格式。
now_iso() {
    date -Iseconds 2>/dev/null || date "+%Y-%m-%dT%H:%M:%S%z"
}

usage() {
    cat >&2 <<'EOF'
Usage:
  run-maintenance.sh once <create|create-audit|drop|retention|all>
  run-maintenance.sh loop

Tasks:
  create        partition/create-partitions.sh      预建未来 3 个月的分区
  create-audit  partition/create-audit-partitions.sh  预建审计表的分区
  drop          partition/drop-old-partitions.sh     清理超过保留期的分区
  retention     retention/apply-retention.sh         执行数据保留策略
  all           以上四个，按 create -> create-audit -> drop -> retention 的顺序
EOF
}

# ---------------------------------------------------------------- 脚本根目录探测
# 顺序：$MAINTENANCE_SCRIPTS_DIR -> runner 自身位置推导出的仓库布局 -> /opt/db（compose 挂载点）
resolve_scripts_root() {
    local candidates=()
    local runner_dir=""
    local candidate=""

    if [ -n "${MAINTENANCE_SCRIPTS_DIR:-}" ]; then
        candidates+=("${MAINTENANCE_SCRIPTS_DIR}")
    fi

    runner_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd)"
    if [ -n "${runner_dir}" ]; then
        candidate="$(cd "${runner_dir}/../.." 2>/dev/null && pwd)"
        if [ -n "${candidate}" ]; then
            candidate="${candidate%/}"
            candidates+=("${candidate}/scripts/db")
        fi
    fi

    candidates+=("/opt/db")

    for candidate in "${candidates[@]}"; do
        if [ -d "${candidate}" ]; then
            candidate="$(cd "${candidate}" 2>/dev/null && pwd)"
            if [ -z "${candidate}" ]; then
                continue
            fi
            if [ -f "${candidate}/partition/create-partitions.sh" ]; then
                SCRIPT_ROOT="${candidate}"
                return 0
            fi
            # 目录存在但内容不对：留作兜底，缺哪个脚本由每个任务自己报错
            if [ -z "${SCRIPT_ROOT}" ]; then
                SCRIPT_ROOT="${candidate}"
            fi
        fi
    done

    [ -n "${SCRIPT_ROOT}" ]
}

# ---------------------------------------------------------------- 连通性校验
# create-partitions.sh 用 `|| true` 吞掉了 psql 的失败，脚本本身会退出 0。
# 为了不把「数据库根本连不上」报成维护成功，这里在脚本跑完之后补一次连通性校验：
#   0 = 连得上，1 = 连不上，2 = psql 不可用
# 参数是探测用的数据库，必须是该任务**真正连过**的那个库。不要用 `-d postgres`：
# 受限角色常常没有 postgres 库的 CONNECT 权限，那样会把已经成功的维护报成 FAILED。
# `DB_PROBE_DB` 可整体覆盖（见 run_task 的映射）。
pg_reachable() {
    local database="${1:-${DB_PROBE_DB:-db_order}}"
    if ! command -v psql >/dev/null 2>&1; then
        return 2
    fi
    PGCONNECT_TIMEOUT="${MAINTENANCE_CONNECT_TIMEOUT}" PGPASSWORD="${DB_PASSWORD}" \
        psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${database}" -tAc 'SELECT 1' >/dev/null 2>&1
}

target_of() {
    case "$1" in
        retention)
            printf '%s:%s user=%s clickhouse=%s:%s' \
                "${DB_HOST}" "${DB_PORT}" "${DB_USER}" "${CH_HOST}" "${CH_PORT}"
            ;;
        *)
            printf '%s:%s user=%s' "${DB_HOST}" "${DB_PORT}" "${DB_USER}"
            ;;
    esac
}

# ---------------------------------------------------------------- 单个任务
# 失败只返回非 0，不中断调用方（循环靠它继续下一个任务）。
run_task() {
    local task="$1"
    local relative="$2"
    local absolute=""
    local directory=""
    local status=0

    log "${task} start $(now_iso)"
    log "${task} target $(target_of "${task}")"

    if [ -n "${SCRIPT_ROOT}" ]; then
        absolute="${SCRIPT_ROOT}/${relative}"
    else
        absolute="${relative}"
    fi

    if [ ! -f "${absolute}" ]; then
        log "${task} FAILED (exit 1) script not found: ${absolute}"
        return 1
    fi

    directory="$(cd "$(dirname "${absolute}")" 2>/dev/null && pwd)"
    if [ -z "${directory}" ]; then
        log "${task} FAILED (exit 1) cannot enter script directory: ${absolute}"
        return 1
    fi

    # 先 cd 进脚本自己的目录，脚本内部的相对路径才能解析；再以绝对路径调用。
    ( cd "${directory}" && bash "${absolute}" )
    status=$?

    if [ "${status}" -ne 0 ]; then
        log "${task} FAILED (exit ${status})"
        return "${status}"
    fi

    # 脚本退出 0 不等于维护真的做过了：补一次连通性校验。
    # 探测库跟着任务走：create/drop 动的是 db_order，create-audit/retention 动的是 db_audit。
    case "${task}" in
        create-audit | retention)
            pg_reachable "${DB_PROBE_DB:-db_audit}"
            ;;
        *)
            pg_reachable "${DB_PROBE_DB:-db_order}"
            ;;
    esac
    status=$?
    if [ "${status}" -eq 2 ]; then
        log "${task} FAILED (exit 1) psql not available in PATH - the script could not have reached the database"
        return 1
    fi
    if [ "${status}" -ne 0 ]; then
        log "${task} FAILED (exit 1) postgres target ${DB_HOST}:${DB_PORT} database ${DB_PROBE_DB:-db_order} unreachable - the script exited 0 but no connection was possible"
        return 1
    fi

    log "${task} ok"
    return 0
}

# ---------------------------------------------------------------- 周期
monthly_cycle() {
    log "monthly cycle start $(now_iso)"
    run_task create "${SCRIPT_CREATE}" || true
    run_task create-audit "${SCRIPT_CREATE_AUDIT}" || true
    log "cycle done $(now_iso)"
}

weekly_cycle() {
    log "weekly cycle start $(now_iso)"
    run_task drop "${SCRIPT_DROP}" || true
    run_task retention "${SCRIPT_RETENTION}" || true
    log "cycle done $(now_iso)"
}

# ---------------------------------------------------------------- once 模式
run_once() {
    local task="${1:-}"
    local failed=0

    if [ -z "${task}" ]; then
        usage
        return 2
    fi

    case "${task}" in
        create)
            run_task create "${SCRIPT_CREATE}" || failed=1
            ;;
        create-audit)
            run_task create-audit "${SCRIPT_CREATE_AUDIT}" || failed=1
            ;;
        drop)
            run_task drop "${SCRIPT_DROP}" || failed=1
            ;;
        retention)
            run_task retention "${SCRIPT_RETENTION}" || failed=1
            ;;
        all)
            run_task create "${SCRIPT_CREATE}" || failed=1
            run_task create-audit "${SCRIPT_CREATE_AUDIT}" || failed=1
            run_task drop "${SCRIPT_DROP}" || failed=1
            run_task retention "${SCRIPT_RETENTION}" || failed=1
            log "cycle done $(now_iso)"
            ;;
        *)
            usage
            return 2
            ;;
    esac

    return "${failed}"
}

# ---------------------------------------------------------------- loop 模式
# 月度周期（create + create-audit）启动即跑，之后每 30 天一次；
# 周度周期（drop + retention）每 7 天一次。逐日 sleep + 两个计数器，不用 cron。
run_loop() {
    local monthly_days="${MAINTENANCE_MONTHLY_DAYS}"
    local weekly_days="${MAINTENANCE_WEEKLY_DAYS}"
    local monthly_left="${MAINTENANCE_MONTHLY_DAYS}"
    local weekly_left="${MAINTENANCE_WEEKLY_DAYS}"
    local sleep_pid=""

    # sleep 放后台 + wait：这样 SIGTERM（docker stop / k8s 驱逐）能立刻打断等待，
    # 否则 bash 会等前台 sleep 结束才跑 trap，容器会等到被 SIGKILL。
    trap 'if [ -n "${sleep_pid:-}" ]; then kill "${sleep_pid}" 2>/dev/null; fi; log "loop stopping"; exit 0' TERM INT

    log "loop start $(now_iso) monthly=${monthly_days}d weekly=${weekly_days}d day=${MAINTENANCE_DAY_SECONDS}s"

    monthly_cycle
    monthly_left="${monthly_days}"

    while :; do
        sleep "${MAINTENANCE_DAY_SECONDS}" &
        sleep_pid=$!
        wait "${sleep_pid}" 2>/dev/null || true
        sleep_pid=""
        monthly_left=$((monthly_left - 1))
        weekly_left=$((weekly_left - 1))

        if [ "${monthly_left}" -le 0 ]; then
            monthly_cycle
            monthly_left="${monthly_days}"
        fi
        if [ "${weekly_left}" -le 0 ]; then
            weekly_cycle
            weekly_left="${weekly_days}"
        fi
    done
}

# ---------------------------------------------------------------- 入口
main() {
    local mode=""
    local task=""

    if [ "$#" -ge 1 ]; then
        mode="$1"
    fi
    if [ "$#" -ge 2 ]; then
        task="$2"
    fi

    if ! resolve_scripts_root; then
        log "ERROR scripts root not found - set MAINTENANCE_SCRIPTS_DIR or mount scripts/db at /opt/db"
        exit 1
    fi
    log "scripts root ${SCRIPT_ROOT}"

    case "${mode}" in
        once)
            run_once "${task}"
            exit $?
            ;;
        loop)
            run_loop
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
}

main "$@"
