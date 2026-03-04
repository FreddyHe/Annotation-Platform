#!/bin/bash
# 新的启动脚本

cd /root/autodl-fs/web_biaozhupingtai

echo "=========================================="
echo "🚀 启动自动标注平台"
echo "=========================================="
echo "环境: web_annotation"
echo "端口: 6006"
echo "=========================================="

# 激活环境
source $(conda info --base)/etc/profile.d/conda.sh 2>/dev/null || true
conda activate web_annotation 2>/dev/null || true


# 启动Streamlit（启用详细日志）
streamlit run main.py \
    --server.port 6006 \
    --server.address 0.0.0.0 \
    --server.headless true \
    --browser.gatherUsageStats false \
    --logger.level info \
    --server.enableCORS false \
    --server.enableXsrfProtection false
