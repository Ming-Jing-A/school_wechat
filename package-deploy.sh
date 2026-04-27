#!/bin/bash
set -e

echo "📦 打包 School Wechat 部署包..."
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PACKAGE_NAME="school_wechat_deploy_${TIMESTAMP}.tar.gz"
TEMP_DIR="/tmp/school_wechat_package_${TIMESTAMP}"

echo "🔨 准备打包内容..."

mkdir -p "$TEMP_DIR"

cp -r docker-compose.yml "$TEMP_DIR/"
cp -r Dockerfile.backend "$TEMP_DIR/"
cp -r .env.example "$TEMP_DIR/"
cp -r deploy.sh "$TEMP_DIR/"
cp -r deploy-stop.sh "$TEMP_DIR/"

if [ -d "web" ]; then
    cp -r web/Dockerfile.frontend "$TEMP_DIR/web_Dockerfile.frontend"
    cp -r web/nginx.conf "$TEMP_DIR/web_nginx.conf"
fi

if [ -d "src" ]; then
    cp -r src "$TEMP_DIR/src"
fi

if [ -f "pom.xml" ]; then
    cp pom.xml "$TEMP_DIR/"
fi

if [ -d ".mvn" ]; then
    cp -r .mvn "$TEMP_DIR/.mvn"
fi

if [ -f "mvnw" ]; then
    cp mvnw "$TEMP_DIR/mvnw"
    chmod +x "$TEMP_DIR/mvnw"
fi

if [ -d "src/main/resources/db" ]; then
    mkdir -p "$TEMP_DIR/db"
    cp src/main/resources/db/init_school_wechat.sql "$TEMP_DIR/db/"
fi

cat > "$TEMP_DIR/README_DEPLOY.txt" << 'EOF'
========================================
  School Wechat Debian 一键部署包
========================================

【使用说明】

1. 上传此压缩包到Debian服务器：
   scp school_wechat_deploy_XXX.tar.gz user@172.16.8.73:/opt/

2. 在Debian服务器上解压：
   cd /opt
   tar -xzf school_wechat_deploy_XXX.tar.gz
   cd school_wechat_package_XXX/

3. 运行一键部署（需要root权限）：
   sudo bash deploy.sh

4. 等待部署完成后，访问：
   http://172.16.8.73:80

【系统要求】
- 操作系统：Debian 11/12 或 Ubuntu 20.04+
- 内存：至少 2GB RAM
- 磁盘空间：至少 10GB 可用空间
- 网络：可访问互联网（用于下载Docker镜像）

【配置修改】
部署前可以编辑 .env 文件修改配置：
- DB_PASSWORD: 数据库密码
- FRONTEND_PORT: 前端端口（默认80）
- BACKEND_PORT: 后端端口（默认8080）

【常用命令】
查看日志：docker compose logs -f
停止服务：bash deploy-stop.sh
重启服务：docker compose restart

【注意事项】
1. 首次部署会自动安装Docker（如果未安装）
2. 数据存储在Docker卷中，删除容器不会丢失数据
3. 如需完全清理数据，运行 bash deploy-stop.sh

========================================
EOF

cd /tmp
tar -czf "${PACKAGE_NAME}" "school_wechat_package_${TIMESTAMP}/"
mv "${PACKAGE_NAME}" "$SCRIPT_DIR/"

rm -rf "$TEMP_DIR"

PACKAGE_SIZE=$(du -h "$SCRIPT_DIR/${PACKAGE_NAME}" | awk '{print $1}')
PACKAGE_PATH="$SCRIPT_DIR/${PACKAGE_NAME}"

echo ""
echo "╔════════════════════════════════════════╗"
echo "║          ✅ 打包完成！                 ║"
echo "╠════════════════════════════════════════╣"
echo "║                                        ║"
echo "║  📦 包名: ${PACKAGE_NAME}"
echo "║  📏 大小: ${PACKAGE_SIZE}                        ║"
echo "║  📍 路径: ${PACKAGE_PATH}"
echo "║                                        ║"
echo "╚════════════════════════════════════════╝"
echo ""
echo "📋 下一步操作："
echo ""
echo "1️⃣  将部署包上传到Debian测试机 (172.16.8.73)："
echo "    scp ${PACKAGE_NAME} root@172.16.8.73:/opt/"
echo ""
echo "2️⃣  在Debian机器上执行："
echo "    ssh root@172.16.8.73"
echo "    cd /opt"
echo "    tar -xzf ${PACKAGE_NAME}"
echo "    cd school_wechat_package_${TIMESTAMP}/"
echo "    bash deploy.sh"
echo ""
echo "3️⃣  访问系统：http://172.16.8.73:80"
