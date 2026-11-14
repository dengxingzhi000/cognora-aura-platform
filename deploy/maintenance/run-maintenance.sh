#!/bin/bash
# deploy/maintenance/run-maintenance.sh
#
# 鍒嗗尯 / 淇濈暀绛栫暐鐨勮皟搴?runner銆?# 鏈剼鏈笉瀹炵幇浠讳綍 SQL 璇箟锛屽彧璐熻矗锛氬畾鏃惰皟鐢?scripts/db 涓嬫棦鏈夌殑 4 涓剼鏈紝
# 鎶婅繛鎺ョ洰鏍囨墦鍒?stdout锛堝鍣ㄦ棩蹇楅┍鍔ㄤ細鏀堕泦 stdout锛夛紝骞朵繚璇佸崟涓换鍔″け璐ヤ笉褰卞搷鍏朵綑浠诲姟銆?#
# 鐢ㄦ硶锛?#   run-maintenance.sh once <create|create-audit|drop|retention|all>   # 璺戜竴涓换鍔″悗閫€鍑猴紙k8s CronJob 鐢級
#   run-maintenance.sh loop                                           # 甯搁┗寰幆锛坈ompose maintenance profile 鐢級
#
# 鐜鍙橀噺锛堜笌鏃㈡湁鑴氭湰鐨勭害瀹氬畬鍏ㄤ竴鑷达紝缂虹渷鍊间篃涓€鑷达級锛?#   DB_HOST / DB_PORT / DB_USER / DB_PASSWORD   -> postgres 杩炴帴锛堥粯璁?localhost / 5432 / admin / admin123锛?#   CH_HOST / CH_PORT / CH_USERNAME / CH_PASSWORD -> clickhouse 杩炴帴锛堥粯璁?localhost / 8123 / default / 绌猴級
#   MAINTENANCE_SCRIPTS_DIR   瑕嗙洊鑴氭湰鏍圭洰褰曪紙鍐呭惈 partition/ 涓?retention/锛夛紝榛樿鑷姩鎺㈡祴
#   MAINTENANCE_DAY_SECONDS   loop 妯″紡姣忚疆 sleep 绉掓暟锛堥粯璁?86400锛屽嵆涓€澶╋級锛岀敤浜庡揩閫熷啋鐑?#   MAINTENANCE_MONTHLY_DAYS  鏈堝害鍛ㄦ湡锛坈reate + create-audit锛夌殑澶╂暟锛岄粯璁?30
#   MAINTENANCE_WEEKLY_DAYS   鍛ㄥ害鍛ㄦ湡锛坉rop + retention锛夌殑澶╂暟锛岄粯璁?7
#   MAINTENANCE_CONNECT_TIMEOUT 杩為€氭€ф牎楠岀殑瓒呮椂绉掓暟锛岄粯璁?5
#
# 璇存槑锛歛pply-retention.sh 鑷繁甯?ClickHouse 鎺㈡椿锛坈url 缂哄け鎴栬繛涓嶄笂鏃跺彧璺宠繃 DWH 閭ｄ竴娈碉紝
# PostgreSQL 渚х殑淇濈暀绛栫暐鐓у父鎵ц锛夛紝鏈?runner 涓嶅幓骞查杩欎釜鑷繚鎶ら€昏緫銆?
set -u

# ---------------------------------------------------------------- 杩炴帴鍙傛暟
# 缂虹渷鍊间笌 4 涓棦鏈夎剼鏈繚鎸佷竴鑷达紱瀵煎嚭鍚庡瓙鑴氭湰缁ф壙鍚屼竴浠介厤缃€?DB_HOST="${DB_HOST:-localhost}"
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

# 浠诲姟鍚?-> 鐩稿鑴氭湰鏍圭洰褰曠殑鑴氭湰璺緞
SCRIPT_CREATE="partition/create-partitions.sh"
SCRIPT_CREATE_AUDIT="partition/create-audit-partitions.sh"
SCRIPT_DROP="partition/drop-old-partitions.sh"
SCRIPT_RETENTION="retention/apply-retention.sh"

SCRIPT_ROOT=""

# ---------------------------------------------------------------- 灏忓伐鍏?log() {
    printf '[maintenance] %s\n' "$*"
}

# ISO-8601 鏃堕棿鎴筹紱GNU date 鏀寔 -Iseconds锛孊SD/macOS 閫€鍥炲埌绛変环鏍煎紡銆?now_iso() {
    date -Iseconds 2>/dev/null || date "+%Y-%m-%dT%H:%M:%S%z"
}

usage() {
    cat >&2 <<'EOF'
Usage:
  run-maintenance.sh once <create|create-audit|drop|retention|all>
  run-maintenance.sh loop

Tasks:
  create        partition/create-partitions.sh      棰勫缓鏈潵 3 涓湀鐨勫垎鍖?  create-audit  partition/create-audit-partitions.sh  棰勫缓瀹¤琛ㄧ殑鍒嗗尯
  drop          partition/drop-old-partitions.sh     娓呯悊瓒呰繃淇濈暀鏈熺殑鍒嗗尯
  retention     retention/apply-retention.sh         鎵ц鏁版嵁淇濈暀绛栫暐
  all           浠ヤ笂鍥涗釜锛屾寜 create -> create-audit -> drop -> retention 鐨勯『搴?EOF
}

# ---------------------------------------------------------------- 鑴氭湰鏍圭洰褰曟帰娴?# 椤哄簭锛?MAINTENANCE_SCRIPTS_DIR -> runner 鑷韩浣嶇疆鎺ㄥ鍑虹殑浠撳簱甯冨眬 -> /opt/db锛坈ompose 鎸傝浇鐐癸級
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
            # 鐩綍瀛樺湪浣嗗唴瀹逛笉瀵癸細鐣欎綔鍏滃簳锛岀己鍝釜鑴氭湰鐢辨瘡涓换鍔¤嚜宸辨姤閿?            if [ -z "${SCRIPT_ROOT}" ]; then
                SCRIPT_ROOT="${candidate}"
            fi
        fi
    done

    [ -n "${SCRIPT_ROOT}" ]
}

# ---------------------------------------------------------------- 杩為€氭€ф牎楠?# create-partitions.sh 鐢?`|| true` 鍚炴帀浜?psql 鐨勫け璐ワ紝鑴氭湰鏈韩浼氶€€鍑?0銆?# 涓轰簡涓嶆妸銆屾暟鎹簱鏍规湰杩炰笉涓娿€嶆姤鎴愮淮鎶ゆ垚鍔燂紝杩欓噷鍦ㄨ剼鏈窇瀹屼箣鍚庤ˉ涓€娆¤繛閫氭€ф牎楠岋細
#   0 = 杩炲緱涓婏紝1 = 杩炰笉涓婏紝2 = psql 涓嶅彲鐢?# 鍙傛暟鏄帰娴嬬敤鐨勬暟鎹簱锛屽繀椤绘槸璇ヤ换鍔?*鐪熸杩炶繃**鐨勯偅涓簱銆備笉瑕佺敤 `-d postgres`锛?# 鍙楅檺瑙掕壊甯稿父娌℃湁 postgres 搴撶殑 CONNECT 鏉冮檺锛岄偅鏍蜂細鎶婂凡缁忔垚鍔熺殑缁存姢鎶ユ垚 FAILED銆?# `DB_PROBE_DB` 鍙暣浣撹鐩栵紙瑙?run_task 鐨勬槧灏勶級銆?pg_reachable() {
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

# ---------------------------------------------------------------- 鍗曚釜浠诲姟
# 澶辫触鍙繑鍥為潪 0锛屼笉涓柇璋冪敤鏂癸紙寰幆闈犲畠缁х画涓嬩竴涓换鍔★級銆?run_task() {
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

    # 鍏?cd 杩涜剼鏈嚜宸辩殑鐩綍锛岃剼鏈唴閮ㄧ殑鐩稿璺緞鎵嶈兘瑙ｆ瀽锛涘啀浠ョ粷瀵硅矾寰勮皟鐢ㄣ€?    ( cd "${directory}" && bash "${absolute}" )
    status=$?

    if [ "${status}" -ne 0 ]; then
        log "${task} FAILED (exit ${status})"
        return "${status}"
    fi

    # 鑴氭湰閫€鍑?0 涓嶇瓑浜庣淮鎶ょ湡鐨勫仛杩囦簡锛氳ˉ涓€娆¤繛閫氭€ф牎楠屻€?    # 鎺㈡祴搴撹窡鐫€浠诲姟璧帮細create/drop 鍔ㄧ殑鏄?db_order锛宑reate-audit/retention 鍔ㄧ殑鏄?db_audit銆?    case "${task}" in
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

# ---------------------------------------------------------------- 鍛ㄦ湡
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

# ---------------------------------------------------------------- once 妯″紡
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

# ---------------------------------------------------------------- loop 妯″紡
# 鏈堝害鍛ㄦ湡锛坈reate + create-audit锛夊惎鍔ㄥ嵆璺戯紝涔嬪悗姣?30 澶╀竴娆★紱
# 鍛ㄥ害鍛ㄦ湡锛坉rop + retention锛夋瘡 7 澶╀竴娆°€傞€愭棩 sleep + 涓や釜璁℃暟鍣紝涓嶇敤 cron銆?run_loop() {
    local monthly_days="${MAINTENANCE_MONTHLY_DAYS}"
    local weekly_days="${MAINTENANCE_WEEKLY_DAYS}"
    local monthly_left="${MAINTENANCE_MONTHLY_DAYS}"
    local weekly_left="${MAINTENANCE_WEEKLY_DAYS}"
    local sleep_pid=""

    # sleep 鏀惧悗鍙?+ wait锛氳繖鏍?SIGTERM锛坉ocker stop / k8s 椹遍€愶級鑳界珛鍒绘墦鏂瓑寰咃紝
    # 鍚﹀垯 bash 浼氱瓑鍓嶅彴 sleep 缁撴潫鎵嶈窇 trap锛屽鍣ㄤ細绛夊埌琚?SIGKILL銆?    trap 'if [ -n "${sleep_pid:-}" ]; then kill "${sleep_pid}" 2>/dev/null; fi; log "loop stopping"; exit 0' TERM INT

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

# ---------------------------------------------------------------- 鍏ュ彛
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
