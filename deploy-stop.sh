#!/bin/bash
set -e

echo "🛑 停止并清理 School Wechat 服务..."
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if command -v docker-compose &> /dev/null; then
    docker-compose down -v
elif docker compose version &> /dev/null; then
    docker compose down -v
else
    echo "❌ Docker Compose 未找到"
    exit 1
fi

echo ""
echo "✅ 所有服务已停止并清理完成！"
echo "   ⚠️  注意：数据库和上传文件已被删除"
echo ""
echo "如需重新部署，请运行：bash deploy.sh"
