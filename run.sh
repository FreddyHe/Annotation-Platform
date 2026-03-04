#!/bin/bash
# 启动脚本 - 自动标注与清洗平台

cd /root/autodl-fs/web_biaozhupingtai

echo "🚀 启动自动标注与清洗平台..."
echo "📍 工作目录: $(pwd)"
echo "🌐 服务地址: http://0.0.0.0:6006"
echo ""

# 检查Python环境
if ! command -v python3 &> /dev/null; then
    echo "❌ 错误: 未找到 python3"
    exit 1
fi

# 检查依赖
echo "📦 检查依赖..."
python3 -c "import streamlit" 2>/dev/null || {
    echo "⚠️  警告: streamlit 未安装，尝试安装依赖..."
    pip install -r requirements.txt
}

# 启动Streamlit
echo "✅ 启动服务..."
streamlit run main.py \
    --server.port 6006 \
    --server.address 0.0.0.0 \
    --server.headless true \
    --browser.gatherUsageStats false




