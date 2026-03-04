#!/bin/bash
# VLM服务管理脚本

PID_FILE="/tmp/vlm_service.pid"
LOG_FILE="/root/autodl-fs/web_biaozhupingtai/logs/vlm_service.log"
PORT=5008

case "$1" in
    start)
        if [ -f "$PID_FILE" ]; then
            PID=$(cat "$PID_FILE")
            if ps -p $PID > /dev/null 2>&1; then
                echo "❌ VLM服务已在运行 (PID: $PID)"
                exit 1
            fi
        fi
        
        echo "🚀 启动 VLM 服务..."
        echo "⏳ 这可能需要1-2分钟..."
        mkdir -p $(dirname "$LOG_FILE")
        
        # 激活环境并启动服务
        nohup bash -c '
            source $(conda info --base)/etc/profile.d/conda.sh
            conda activate LLM_DL
            VLLM_USE_MODELSCOPE=true vllm serve Qwen/Qwen3-VL-4B-Instruct \
                --port 5008 \
                --gpu-memory-utilization 0.75 \
                --served-model-name Qwen3-VL-4B-Instruct \
                --max-model-len 16384 \
                --trust-remote-code \
                --enforce-eager
        ' > "$LOG_FILE" 2>&1 &
        
        SERVICE_PID=$!
        echo $SERVICE_PID > "$PID_FILE"
        
        echo "✅ VLM 服务已启动 (PID: $SERVICE_PID)"
        echo "📡 端口: $PORT"
        echo "📋 日志: $LOG_FILE"
        echo ""
        echo "⏳ 等待服务就绪（约1-2分钟）..."
        echo "💡 查看日志: tail -f $LOG_FILE"
        echo "💡 检查状态: $0 status"
        echo "💡 停止服务: $0 stop"
        echo ""
        echo "🔍 提示: 看到 'Uvicorn running' 表示服务已就绪"
        ;;
    
    stop)
        if [ ! -f "$PID_FILE" ]; then
            echo "⚠️  PID文件不存在，服务可能未运行"
            exit 1
        fi
        
        PID=$(cat "$PID_FILE")
        
        if ps -p $PID > /dev/null 2>&1; then
            echo "🛑 停止 VLM 服务 (PID: $PID)..."
            kill $PID
            sleep 3
            
            if ps -p $PID > /dev/null 2>&1; then
                echo "⚠️  进程未响应，强制终止..."
                kill -9 $PID
            fi
            
            rm -f "$PID_FILE"
            echo "✅ VLM 服务已停止"
        else
            echo "⚠️  进程不存在 (PID: $PID)"
            rm -f "$PID_FILE"
        fi
        ;;
    
    status)
        if [ -f "$PID_FILE" ]; then
            PID=$(cat "$PID_FILE")
            if ps -p $PID > /dev/null 2>&1; then
                echo "✅ VLM 服务运行中 (PID: $PID)"
                echo "📡 端口: $PORT"
                echo ""
                
                # 测试健康检查
                echo "🔍 健康检查:"
                if command -v curl > /dev/null; then
                    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$PORT/health 2>/dev/null)
                    if [ "$HTTP_CODE" = "200" ]; then
                        echo "   ✅ 服务响应正常 (HTTP 200)"
                    elif [ "$HTTP_CODE" = "000" ]; then
                        echo "   ⏳ 服务启动中... (无响应)"
                        echo "   💡 提示: 刚启动需要1-2分钟加载模型"
                    else
                        echo "   ⚠️  服务异常 (HTTP $HTTP_CODE)"
                    fi
                else
                    echo "   ⚠️  无法执行curl命令"
                fi
            else
                echo "❌ PID文件存在但进程未运行"
                rm -f "$PID_FILE"
            fi
        else
            echo "❌ VLM 服务未运行"
        fi
        ;;
    
    restart)
        $0 stop
        sleep 3
        $0 start
        ;;
    
    logs)
        if [ -f "$LOG_FILE" ]; then
            tail -f "$LOG_FILE"
        else
            echo "❌ 日志文件不存在: $LOG_FILE"
        fi
        ;;
    
    test)
        echo "🧪 测试 VLM 服务..."
        
        # 健康检查
        echo "1️⃣ 健康检查:"
        curl -s http://localhost:$PORT/health | python3 -m json.tool || echo "❌ 失败"
        
        echo ""
        echo "2️⃣ 模型列表:"
        curl -s http://localhost:$PORT/v1/models | python3 -m json.tool || echo "❌ 失败"
        
        echo ""
        echo "3️⃣ 简单推理测试:"
        curl -X POST http://localhost:$PORT/v1/chat/completions \
            -H "Content-Type: application/json" \
            -d '{
                "model": "Qwen3-VL-4B-Instruct",
                "messages": [{"role": "user", "content": "Hello"}],
                "max_tokens": 10
            }' | python3 -m json.tool || echo "❌ 失败"
        ;;
    
    *)
        echo "用法: $0 {start|stop|status|restart|logs|test}"
        echo ""
        echo "命令说明:"
        echo "  start   - 启动VLM服务"
        echo "  stop    - 停止VLM服务"
        echo "  status  - 查看服务状态"
        echo "  restart - 重启服务"
        echo "  logs    - 查看实时日志"
        echo "  test    - 测试服务是否正常"
        exit 1
        ;;
esac