#!/bin/bash
# DINO检测测试脚本

echo "========================================"
echo "🔍 测试 Grounding DINO 检测脚本"
echo "========================================"

# 配置
IMAGE_DIR="$1"
LABELS="$2"
OUTPUT_FILE="/tmp/test_dino_output.json"

if [ -z "$IMAGE_DIR" ] || [ -z "$LABELS" ]; then
    echo "用法: $0 <图片目录> <类别列表>"
    echo "例如: $0 /path/to/images 'dog,cat,bird'"
    exit 1
fi

echo ""
echo "📂 图片目录: $IMAGE_DIR"
echo "🏷️  类别列表: $LABELS"
echo "💾 输出文件: $OUTPUT_FILE"
echo ""

# 检查图片目录是否存在
if [ ! -d "$IMAGE_DIR" ]; then
    echo "❌ 错误: 图片目录不存在: $IMAGE_DIR"
    exit 1
fi

# 检查图片数量
IMAGE_COUNT=$(find "$IMAGE_DIR" -type f \( -iname "*.jpg" -o -iname "*.jpeg" -o -iname "*.png" -o -iname "*.bmp" \) | wc -l)
echo "📷 找到 $IMAGE_COUNT 张图片"

if [ "$IMAGE_COUNT" -eq 0 ]; then
    echo "❌ 错误: 目录中没有图片文件"
    exit 1
fi

echo ""
echo "🚀 开始测试..."
echo ""

# 执行DINO检测
conda run -n groundingdino310 python /root/autodl-fs/web_biaozhupingtai/scripts/run_dino_detection.py \
    --config /root/autodl-fs/GroundingDINO/groundingdino/config/GroundingDINO_SwinT_OGC.py \
    --weights /root/autodl-fs/GroundingDINO/weights/groundingdino_swint_ogc.pth \
    --image-dir "$IMAGE_DIR" \
    --labels "$LABELS" \
    --output "$OUTPUT_FILE" \
    --box-threshold 0.3

EXIT_CODE=$?

echo ""
echo "========================================"
if [ $EXIT_CODE -eq 0 ]; then
    echo "✅ 测试成功!"
    echo ""
    
    if [ -f "$OUTPUT_FILE" ]; then
        DETECTION_COUNT=$(python3 -c "import json; data=json.load(open('$OUTPUT_FILE')); print(len(data))")
        echo "📊 检测结果统计:"
        echo "   检测数量: $DETECTION_COUNT"
        echo "   输出文件: $OUTPUT_FILE"
        echo ""
        echo "💡 查看结果:"
        echo "   cat $OUTPUT_FILE | python3 -m json.tool | head -50"
    else
        echo "⚠️  警告: 脚本执行成功但未生成输出文件"
    fi
else
    echo "❌ 测试失败! (退出码: $EXIT_CODE)"
fi
echo "========================================"