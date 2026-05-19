#!/bin/bash
set -e

echo "╔═══════════════════════════════════════════════════════════╗"
echo "║        School Wechat 一键部署脚本 (Debian版)              ║"
echo "║        适用于 Debian 11/12 / Ubuntu 20.04+                ║"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

check_root() {
    if [ "$EUID" -ne 0 ]; then
        echo "❌ 请使用 root 用户运行此脚本"
        echo "   使用方式: sudo bash deploy.sh"
        exit 1
    fi
}

detect_os() {
    echo "🔍 检测操作系统..."
    if [ -f /etc/debian_version ]; then
        DEBIAN_VERSION=$(cat /etc/debian_version)
        echo "✅ 检测到 Debian/Ubuntu 系统 (版本: $DEBIAN_VERSION)"
    else
        echo "❌ 此脚本仅支持 Debian/Ubuntu 系统"
        exit 1
    fi
}

install_docker() {
    echo ""
    echo "🐳 检查 Docker 安装状态..."
    if command -v docker &> /dev/null; then
        DOCKER_VERSION=$(docker --version | awk '{print $3}' | sed 's/,//')
        echo "✅ Docker 已安装 (版本: $DOCKER_VERSION)"
        
        if ! docker compose version &> /dev/null; then
            echo "⚠️  Docker Compose 未安装，正在安装..."
            install_docker_compose
        else
            COMPOSE_VERSION=$(docker compose version | awk '{print $5}' | sed 's/,//')
            echo "✅ Docker Compose 已安装 (版本: $COMPOSE_VERSION)"
        fi
    else
        echo "📦 Docker 未安装，开始自动安装..."
        install_docker_engine
    fi
}

install_docker_engine() {
    echo ""
    echo "📥 安装 Docker Engine..."
    
    apt-get update
    apt-get install -y \
        ca-certificates \
        curl \
        gnupg \
        lsb-release
    
    mkdir -p /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    
    echo \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian \
        $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
    
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
    
    systemctl enable docker
    systemctl start docker
    
    echo "✅ Docker 安装完成"
}

install_docker_compose() {
    apt-get update
    apt-get install -y docker-compose-plugin
    echo "✅ Docker Compose 安装完成"
}

setup_environment() {
    echo ""
    echo "⚙️  配置运行环境..."
    
    if [ ! -f .env ]; then
        cp .env.example .env
        echo "✅ 已从 .env.example 创建 .env 配置文件"
    fi
    
    LOCAL_IP=$(hostname -I | awk '{print $1}')
    echo "✅ 本机IP地址: $LOCAL_IP"
    
    sed -i "s|^FRONTEND_PORT=.*|FRONTEND_PORT=80|" .env
}

create_optimizations() {
    echo ""
    echo "🔧 应用 Debian 系统优化..."
    
    if [ -f /etc/sysctl.conf ]; then
        grep -q "net.core.somaxconn" /etc/sysctl.conf || echo "net.core.somaxconn = 65535" >> /etc/sysctl.conf
        grep -q "net.ipv4.tcp_max_syn_backlog" /etc/sysctl.conf || echo "net.ipv4.tcp_max_syn_backlog = 65535" >> /etc/sysctl.conf
        sysctl -p > /dev/null 2>&1 || true
    fi
    
    ulimit -n 65535 > /dev/null 2>&1 || true
}

deploy_services() {
    echo ""
    echo "🏗️  构建并启动所有服务..."
    echo "   ⏳ 这可能需要几分钟时间，请耐心等待..."
    echo ""
    
    docker compose up -d --build
    
    echo ""
    echo "⏳ 等待服务启动..."
    sleep 10
    
    check_services_health
}

check_services_health() {
    echo ""
    echo "🔍 检查服务健康状态..."
    
    MAX_RETRIES=30
    RETRY_COUNT=0
    
    while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
        MYSQL_STATUS=$(docker compose ps mysql --format json 2>/dev/null | grep -o '"Status":"[^"]*"' | head -1)
        BACKEND_STATUS=$(docker compose ps backend --format json 2>/dev/null | grep -o '"Status":"[^"]*"' | head -1)
        FRONTEND_STATUS=$(docker compose ps frontend --format json 2>/dev/null | grep -o '"Status":"[^"]*"' | head -1)
        
        if echo "$MYSQL_STATUS" | grep -q "running" && \
           echo "$BACKEND_STATUS" | grep -q "running" && \
           echo "$FRONTEND_STATUS" | grep -q "running"; then
            echo "✅ 所有服务已成功启动！"
            return 0
        fi
        
        RETRY_COUNT=$((RETRY_COUNT + 1))
        echo "   ⏳ 等待服务启动... ($RETRY_COUNT/$MAX_RETRIES)"
        sleep 3
    done
    
    echo "⚠️  部分服务可能未完全启动，请查看日志："
    echo "   docker compose logs -f"
}

show_success_message() {
    LOCAL_IP=$(hostname -I | awk '{print $1}')
    
    echo ""
    echo "╔═══════════════════════════════════════════════════════════╗"
    echo "║                    🎉 部署成功！                          ║"
    echo "╠═══════════════════════════════════════════════════════════╣"
    echo "║                                                         ║"
    echo "║  🌐 访问地址：                                           ║"
    echo "║     本机访问: http://localhost                            ║"
    echo "║     局域网: http://${LOCAL_IP}                              ║"
    echo "║                                                         ║"
    echo "║  📋 服务信息：                                           ║"
    echo "║     前端页面: http://${LOCAL_IP}:80                        ║"
    echo "║     后端API: http://${LOCAL_IP}:8080                      ║"
    echo "║     数据库: localhost:3306                                ║"
    echo "║                                                         ║"
    echo "║  🔧 常用命令：                                           ║"
    echo "║     查看日志: docker compose logs -f                     ║"
    echo "║     停止服务: docker compose down                         ║"
    echo "║     重启服务: docker compose restart                      ║"
    echo "║     完全清理: bash stop.sh                               ║"
    echo "║                                                         ║"
    echo "║  📁 数据存储：                                           ║"
    echo "║     数据库数据: Docker卷 school_wechat_mysql_data       ║"
    echo "║     上传文件: Docker卷 school_wechat_uploads             ║"
    echo "║                                                         ║"
    echo "╚═══════════════════════════════════════════════════════════╝"
    echo ""
    
    show_service_status
}

show_service_status() {
    echo "📊 当前服务运行状态："
    echo ""
    docker compose ps
    echo ""
    
    echo "💾 存储使用情况："
    docker system df 2>/dev/null | head -5 || true
    echo ""
}

main() {
    echo "开始执行部署流程..."
    echo ""
    
    check_root
    detect_os
    install_docker
    setup_environment
    create_optimizations
    deploy_services
    show_success_message
    
    echo ""
    echo "✨ 部署完成！现在可以在浏览器中打开 http://$(hostname -I | awk '{print $1}') 访问校园微信系统"
}

main "$@"
