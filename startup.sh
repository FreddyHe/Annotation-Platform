#!/bin/bash
# =============================================================================
# 智能标注平台 - 一键启动/停止/状态检查脚本
# 用法:
#   ./startup.sh          # 启动所有未运行的服务
#   ./startup.sh start    # 同上
#   ./startup.sh stop     # 停止所有服务
#   ./startup.sh restart  # 重启所有服务
#   ./startup.sh status   # 检查所有服务状态
#   ./startup.sh build    # 重新编译后端并启动所有服务
# =============================================================================

PROJECT_ROOT="/root/autodl-fs/Annotation-Platform"
CONDA_SH="$(conda info --base 2>/dev/null)/etc/profile.d/conda.sh"
NVM_SH="/root/.nvm/nvm.sh"
TORCH_LIB_DIR="/root/miniconda3/envs/groundingdino310/lib/python3.10/site-packages/torch/lib"
LABEL_STUDIO_PROXY_PYTHON="${LABEL_STUDIO_PROXY_PYTHON:-/root/miniconda3/bin/python}"
QWEN_VLM_MODEL_PATH="${QWEN_VLM_MODEL_PATH:-/root/autodl-fs/科研文件夹/MACGD/third_party/models/Qwen/Qwen3-VL-4B-Instruct}"
ALGORITHM_UVICORN="${ALGORITHM_UVICORN:-/root/miniconda3/bin/uvicorn}"

# 日志文件
LOG_SPRINGBOOT="/tmp/springboot.log"
LOG_LABELSTUDIO="/tmp/labelstudio.log"
LOG_LABELSTUDIO_PROXY="/tmp/labelstudio-proxy.log"
LOG_DINO="/tmp/dino.log"
LOG_QWEN_VLM="/tmp/qwen3-vl-4b-vllm.log"
LOG_LOCATE_ANYTHING="/tmp/locate-anything.log"
LOG_ALGORITHM="/tmp/algorithm.log"
LOG_FRONTEND="/tmp/frontend.log"

# 端口定义
PORT_SPRINGBOOT=8080
PORT_LABELSTUDIO=5001
PORT_LABELSTUDIO_UPSTREAM=15001
PORT_DINO=5003
PORT_QWEN_VLM=5008
PORT_LOCATE_ANYTHING=5010
PORT_ALGORITHM=8001
PORT_FRONTEND=6006

PID_ALGORITHM="$PROJECT_ROOT/logs/algorithm-service-${PORT_ALGORITHM}.pid"

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

check_port() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN > /dev/null 2>&1
}

listening_pid() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1
}

wait_for_port() {
    local port=$1 name=$2 timeout=$3
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        if check_port "$port"; then
            echo -e "  ${GREEN}✅ $name (端口 $port) 已就绪${NC}"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    echo -e "  ${YELLOW}⚠️  $name (端口 $port) 启动超时，请检查日志${NC}"
    return 1
}

kill_port() {
    local port=$1
    local pids
    pids=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null)
    if [ -n "$pids" ]; then
        echo "$pids" | xargs kill -9 2>/dev/null
        sleep 1
    fi
}

# ---------------------------------------------------------------------------
# status - 检查所有服务状态
# ---------------------------------------------------------------------------
do_status() {
    echo -e "${CYAN}=========================================="
    echo "  智能标注平台 - 服务状态"
    echo -e "==========================================${NC}"
    echo ""

    local all_ok=true
    for entry in \
        "$PORT_SPRINGBOOT:Spring Boot 后端" \
        "$PORT_LABELSTUDIO:Label Studio 公开代理" \
        "$PORT_LABELSTUDIO_UPSTREAM:Label Studio 内部服务" \
        "$PORT_DINO:DINO 模型服务" \
        "$PORT_QWEN_VLM:Qwen3-VL-4B 本地 VLM" \
        "$PORT_LOCATE_ANYTHING:LocateAnything-3B 本地服务" \
        "$PORT_ALGORITHM:算法服务 FastAPI" \
        "$PORT_FRONTEND:前端 Vue"; do
        local port="${entry%%:*}"
        local name="${entry#*:}"
        if check_port "$port"; then
            local pid
            pid=$(listening_pid "$port")
            echo -e "  ${GREEN}✅${NC} 端口 $port ($name) — PID $pid"
        else
            echo -e "  ${RED}❌${NC} 端口 $port ($name) — 未运行"
            all_ok=false
        fi
    done

    echo ""
    if $all_ok; then
        echo -e "  ${GREEN}全部服务运行中${NC}"
    else
        echo -e "  ${YELLOW}部分服务未运行，执行 ./startup.sh 可启动缺失服务${NC}"
    fi
    echo ""
}

# ---------------------------------------------------------------------------
# stop - 停止所有服务
# ---------------------------------------------------------------------------
do_stop() {
    echo -e "${CYAN}=========================================="
    echo "  智能标注平台 - 停止所有服务"
    echo -e "==========================================${NC}"
    echo ""

    for entry in \
        "$PORT_SPRINGBOOT:Spring Boot 后端" \
        "$PORT_LABELSTUDIO:Label Studio 公开代理" \
        "$PORT_LABELSTUDIO_UPSTREAM:Label Studio 内部服务" \
        "$PORT_DINO:DINO 模型服务" \
        "$PORT_QWEN_VLM:Qwen3-VL-4B 本地 VLM" \
        "$PORT_LOCATE_ANYTHING:LocateAnything-3B 本地服务" \
        "$PORT_ALGORITHM:算法服务 FastAPI" \
        "$PORT_FRONTEND:前端 Vue"; do
        local port="${entry%%:*}"
        local name="${entry#*:}"
        if check_port "$port"; then
            kill_port "$port"
            echo -e "  ${RED}⏹${NC}  $name (端口 $port) 已停止"
        else
            echo -e "  ${YELLOW}—${NC}  $name (端口 $port) 本就未运行"
        fi
    done

    # 补充按进程名杀残留
    pkill -f "vite" 2>/dev/null
    pkill -f "dino_model_server" 2>/dev/null
    pkill -f "vllm serve .*Qwen3-VL-4B-Instruct" 2>/dev/null
    pkill -f "locate_anything_model_server" 2>/dev/null

    echo ""
    echo -e "  ${GREEN}全部服务已停止${NC}"
    echo ""
}

# ---------------------------------------------------------------------------
# start - 启动所有未运行的服务（跳过已运行的）
# ---------------------------------------------------------------------------
do_start() {
    local do_build=$1

    echo -e "${CYAN}=========================================="
    echo "  智能标注平台 - 启动服务"
    echo -e "==========================================${NC}"
    echo ""

    source "$CONDA_SH" 2>/dev/null
    if [ -f "$NVM_SH" ]; then
        source "$NVM_SH" 2>/dev/null
    fi
    if [ -f "$PROJECT_ROOT/.env.local" ]; then
        set -a
        source "$PROJECT_ROOT/.env.local"
        set +a
    fi
    if [ -f "$HOME/.config/ai-keys/env" ]; then
        set -a
        source "$HOME/.config/ai-keys/env"
        set +a
    fi

    # --- 1. Spring Boot 后端 (8080) ---
    if check_port $PORT_SPRINGBOOT; then
        echo -e "  ${GREEN}✅${NC} Spring Boot (端口 $PORT_SPRINGBOOT) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动 Spring Boot 后端..."
        if [ "$do_build" = "build" ]; then
            echo "     编译中 (mvn clean package -DskipTests) ..."
            cd "$PROJECT_ROOT/backend-springboot"
            mvn clean package -DskipTests -q 2>&1 | tail -3
        fi
        # 确认 jar 存在
        local jar="$PROJECT_ROOT/backend-springboot/target/platform-backend-1.0.0.jar"
        if [ ! -f "$jar" ]; then
            echo -e "  ${RED}❌ JAR 不存在，先执行编译: ./startup.sh build${NC}"
        else
            nohup setsid -f java -jar "$jar" --server.port=$PORT_SPRINGBOOT \
                > "$LOG_SPRINGBOOT" 2>&1 &
            wait_for_port $PORT_SPRINGBOOT "Spring Boot" 30
        fi
    fi

    # --- 2. Label Studio (内部 15001 + 公开代理 5001) ---
    if check_port $PORT_LABELSTUDIO_UPSTREAM; then
        echo -e "  ${GREEN}✅${NC} Label Studio 内部服务 (端口 $PORT_LABELSTUDIO_UPSTREAM) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动 Label Studio 内部服务..."
        conda activate web_annotation 2>/dev/null
        export LABEL_STUDIO_LOCAL_FILES_SERVING_ENABLED=true
        export LABEL_STUDIO_LOCAL_FILES_DOCUMENT_ROOT=/root/autodl-fs
        export COLLECT_ANALYTICS=false
        nohup setsid -f label-studio start --host 0.0.0.0 --port $PORT_LABELSTUDIO_UPSTREAM --no-browser --log-level INFO \
            > "$LOG_LABELSTUDIO" 2>&1 &
        wait_for_port $PORT_LABELSTUDIO_UPSTREAM "Label Studio 内部服务" 30
    fi

    if check_port $PORT_LABELSTUDIO; then
        echo -e "  ${GREEN}✅${NC} Label Studio 公开代理 (端口 $PORT_LABELSTUDIO) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动 Label Studio 公开代理..."
        conda activate web_annotation 2>/dev/null
        LABEL_STUDIO_PROXY_UPSTREAM="http://127.0.0.1:$PORT_LABELSTUDIO_UPSTREAM" \
        LABEL_STUDIO_PROXY_PORT="$PORT_LABELSTUDIO" \
        nohup setsid -f "$LABEL_STUDIO_PROXY_PYTHON" "$PROJECT_ROOT/scripts/label_studio_proxy.py" \
            > "$LOG_LABELSTUDIO_PROXY" 2>&1 &
        wait_for_port $PORT_LABELSTUDIO "Label Studio 公开代理" 15
    fi

    # --- 3. DINO 模型服务 (5003) ---
    if check_port $PORT_DINO; then
        echo -e "  ${GREEN}✅${NC} DINO 模型服务 (端口 $PORT_DINO) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动 DINO 模型服务 (模型约 662MB，加载需 30~60 秒)..."
        conda activate groundingdino310 2>/dev/null
        export LD_LIBRARY_PATH="$TORCH_LIB_DIR:${LD_LIBRARY_PATH:-}"
        nohup setsid -f python "$PROJECT_ROOT/algorithm-service/dino_model_server.py" \
            > "$LOG_DINO" 2>&1 &
        wait_for_port $PORT_DINO "DINO 模型服务" 90
    fi

    # --- 4. Qwen3-VL-4B 本地 VLM (5008) ---
    if check_port $PORT_QWEN_VLM; then
        echo -e "  ${GREEN}✅${NC} Qwen3-VL-4B 本地 VLM (端口 $PORT_QWEN_VLM) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动 Qwen3-VL-4B 本地 VLM..."
        export CUDA_VISIBLE_DEVICES="${LOCAL_VLM_CUDA_VISIBLE_DEVICES:-0}"
        nohup setsid -f /root/miniconda3/envs/LLM_DL/bin/vllm serve \
            "$QWEN_VLM_MODEL_PATH" \
            --host 0.0.0.0 \
            --port $PORT_QWEN_VLM \
            --allowed-local-media-path / \
            --gpu-memory-utilization "${LOCAL_VLM_GPU_MEMORY_UTILIZATION:-0.70}" \
            --served-model-name Qwen3-VL-4B-Instruct \
            --max-model-len "${LOCAL_VLM_MAX_MODEL_LEN:-16384}" \
            --trust-remote-code \
            --enforce-eager \
            --max-num-seqs "${LOCAL_VLM_MAX_NUM_SEQS:-4}" \
            > "$LOG_QWEN_VLM" 2>&1 &
        # 首次加载 4B 模型在当前机器上通常需要约 4 分钟，避免服务仍在正常加载时误报失败。
        wait_for_port $PORT_QWEN_VLM "Qwen3-VL-4B 本地 VLM" 360
    fi

    # --- 5. LocateAnything-3B 本地服务 (5010) ---
    if check_port $PORT_LOCATE_ANYTHING; then
        echo -e "  ${GREEN}✅${NC} LocateAnything-3B 本地服务 (端口 $PORT_LOCATE_ANYTHING) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动 LocateAnything-3B 本地服务..."
        LOCATE_ANYTHING_PORT="$PORT_LOCATE_ANYTHING" \
        LOCATE_ANYTHING_CUDA_VISIBLE_DEVICES="${LOCATE_ANYTHING_CUDA_VISIBLE_DEVICES:-1}" \
        "$PROJECT_ROOT/scripts/start_locate_anything_service.sh" > "$LOG_LOCATE_ANYTHING" 2>&1
        wait_for_port $PORT_LOCATE_ANYTHING "LocateAnything-3B 本地服务" 30
    fi

    # --- 6. 算法服务 FastAPI (8001) ---
    if check_port $PORT_ALGORITHM; then
        echo -e "  ${GREEN}✅${NC} 算法服务 FastAPI (端口 $PORT_ALGORITHM) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动算法服务 FastAPI..."
        mkdir -p "$PROJECT_ROOT/logs"
        if [ ! -x "$ALGORITHM_UVICORN" ]; then
            echo -e "  ${RED}❌ 算法服务启动器不存在: $ALGORITHM_UVICORN${NC}"
        else
            (
                cd "$PROJECT_ROOT/algorithm-service"
                nohup setsid "$ALGORITHM_UVICORN" main:app --host 0.0.0.0 --port $PORT_ALGORITHM \
                    > "$LOG_ALGORITHM" 2>&1 &
                echo $! > "$PID_ALGORITHM"
            )
        fi
        wait_for_port $PORT_ALGORITHM "算法服务 FastAPI" 15
    fi

    # --- 7. 前端 Vue (6006) ---
    if check_port $PORT_FRONTEND; then
        echo -e "  ${GREEN}✅${NC} 前端 Vue (端口 $PORT_FRONTEND) 已在运行，跳过"
    else
        echo -e "  ${CYAN}🚀${NC} 启动前端 Vue..."
        cd "$PROJECT_ROOT/frontend-vue"
        nohup setsid -f npx vite --host 0.0.0.0 --port $PORT_FRONTEND \
            > "$LOG_FRONTEND" 2>&1 &
        wait_for_port $PORT_FRONTEND "前端 Vue" 15
    fi

    # --- 最终状态 ---
    echo ""
    echo -e "${CYAN}=========================================="
    echo "  启动完成 — 最终状态"
    echo -e "==========================================${NC}"
    do_status

    echo "日志文件:"
    echo "  Spring Boot:  $LOG_SPRINGBOOT"
    echo "  Label Studio: $LOG_LABELSTUDIO"
    echo "  LS 公开代理:  $LOG_LABELSTUDIO_PROXY"
    echo "  DINO:         $LOG_DINO"
    echo "  Qwen VLM:     $LOG_QWEN_VLM"
    echo "  LocateAny:    $LOG_LOCATE_ANYTHING"
    echo "  算法服务:     $LOG_ALGORITHM"
    echo "  前端:         $LOG_FRONTEND"
    echo ""
}

# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------
case "${1:-start}" in
    start)
        do_start
        ;;
    build)
        do_start "build"
        ;;
    stop)
        do_stop
        ;;
    restart)
        do_stop
        sleep 3
        do_start
        ;;
    status)
        do_status
        ;;
    *)
        echo "用法: $0 {start|stop|restart|status|build}"
        echo ""
        echo "  start   — 启动所有未运行的服务（默认）"
        echo "  stop    — 停止所有服务"
        echo "  restart — 重启所有服务"
        echo "  status  — 检查所有服务状态"
        echo "  build   — 重新编译后端 JAR 并启动所有服务"
        exit 1
        ;;
esac
