#!/bin/bash
# 统一启动脚本 - 启动所有服务（DINO + FastAPI）

set -e

echo "=========================================="
echo "🚀 启动自动标注平台 - 全部服务"
echo "=========================================="

# 激活conda环境
source $(conda info --base)/etc/profile.d/conda.sh

# ==================== 1. 启动 DINO 服务 (端口 5003) ====================
echo ""
echo "📦 启动 DINO 服务 (groundingdino310 环境)..."
echo "   端口: 5003"
echo "   地址: http://127.0.0.1:5003"

cd /root/autodl-fs/Annotation-Platform/algorithm-service

# 使用 groundingdino310 环境，强制 CPU 运行
conda activate groundingdino310
export CUDA_VISIBLE_DEVICES=""
python dino_model_server.py &

DINO_PID=$!
echo "   ✅ DINO 服务启动中 (PID: $DINO_PID)"

# 等待 DINO 服务启动
sleep 5

# 检查 DINO 服务是否正常启动
if curl -s http://127.0.0.1:5003 > /dev/null 2>&1; then
    echo "   ✅ DINO 服务已就绪"
else
    echo "   ⚠️  DINO 服务启动可能需要更多时间..."
fi

# ==================== 2. 启动 FastAPI 服务 (端口 8001) ====================
echo ""
echo "📦 启动 FastAPI 算法服务 (algo_service 环境)..."
echo "   端口: 8001"
echo "   地址: http://127.0.0.1:8001"

cd /root/autodl-fs/Annotation-Platform/algorithm-service

conda activate algo_service
export CUDA_VISIBLE_DEVICES=""

# 启动 FastAPI 服务
uvicorn main:app --host 0.0.0.0 --port 8001 --reload &

API_PID=$!
echo "   ✅ FastAPI 服务启动中 (PID: $API_PID)"

# 等待服务启动
sleep 3

# 检查 FastAPI 服务是否正常启动
if curl -s http://127.0.0.1:8001/api/v1/health > /dev/null 2>&1; then
    echo "   ✅ FastAPI 服务已就绪"
else
    echo "   ⚠️  FastAPI 服务启动可能需要更多时间..."
fi

# ==================== 3. 显示服务状态 ====================
echo ""
echo "=========================================="
echo "✅ 服务启动完成！"
echo "=========================================="
echo "📊 服务列表:"
echo "   - DINO 服务:      http://127.0.0.1:5003 (PID: $DINO_PID)"
echo "   - FastAPI 服务:   http://127.0.0.1:8001 (PID: $API_PID)"
echo "   - Spring Boot:    http://localhost:8080"
echo "   - Label Studio:   http://localhost:5001"
echo ""
echo "📝 API 文档:"
echo "   - FastAPI Docs:   http://127.0.0.1:8001/api/v1/docs"
echo "=========================================="

# ==================== 4. 保持脚本运行 ====================
echo ""
echo "⏳ 服务正在运行中，按 Ctrl+C 停止所有服务..."

# 捕获中断信号
trap "echo ''; echo '🛑 停止所有服务...'; kill $DINO_PID $API_PID 2>/dev/null; exit 0" INT TERM

# 等待后台进程
wait
