#!/bin/bash

echo "🛑 停止并清理所有服务..."
docker-compose down -v

echo ""
echo "✅ 所有服务已停止并清理完成！"
echo "   数据已清除（包括数据库和上传文件）"
