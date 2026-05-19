#!/bin/bash

set -e

echo "=========================================="
echo "  School Wechat Docker 部署脚本"
echo "=========================================="

if [ ! -f .env ]; then
    echo "📝 创建配置文件 .env ..."
    cp .env.example .env
    echo "✅ 配置文件已创建，你可以编辑 .env 文件修改配置"
fi

echo ""
echo "🔨 开始构建并启动所有服务..."
echo ""

docker-compose up -d --build

echo ""
echo "=========================================="
echo "✅ 部署完成！"
echo "=========================================="
echo ""
echo "🌐 访问地址："
echo "   前端页面: http://$(hostname -I | awk '{print $1}'):80"
echo "   或访问: http://localhost:80"
echo ""
echo "📋 服务状态:"
docker-compose ps
echo ""
echo "📝 查看日志: docker-compose logs -f"
echo "🛑 停止服务: docker-compose down"
echo "=========================================="
