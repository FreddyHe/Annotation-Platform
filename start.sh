#!/bin/bash
# 自动标注平台启动脚本

# 激活环境
source $(conda info --base)/etc/profile.d/conda.sh
conda activate web_annotation

# 切换到项目目录
cd "/root/autodl-fs/web_biaozhupingtai"

echo "=========================================="
echo "🚀 启动自动标注平台"
echo "=========================================="
echo "环境: web_annotation"
echo "端口: 6006"
echo "访问地址: http://localhost:6006"
echo "=========================================="
echo ""

# 启动Streamlit
streamlit run main.py \
    --server.port 6006 \
    --server.address 0.0.0.0 \
    --server.headless true \
    --browser.gatherUsageStats false
