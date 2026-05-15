# 智能题库应用部署指南

## 1. 部署概述

### 1.1 部署目标

- 确保应用在不同环境中稳定运行
- 提供标准化的部署流程
- 支持持续集成和持续部署
- 确保部署过程的可靠性和可重复性

### 1.2 部署环境

| 环境 | 用途 | 特点 |
|------|------|------|
| 开发环境 | 开发和测试 | 配置灵活，便于调试 |
| 测试环境 | 功能和性能测试 | 接近生产环境配置 |
| 预发布环境 | 发布前验证 | 与生产环境配置一致 |
| 生产环境 | 正式发布 | 高可用性，安全性要求高 |

### 1.3 部署架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                           客户端层                                   │
│                                                                     │
│  Android App → API Gateway → Load Balancer                          │
├─────────────────────────────────────────────────────────────────────┤
│                        应用服务层                                    │
│                                                                     │
│  API Service → Business Logic → Data Access                        │
├─────────────────────────────────────────────────────────────────────┤
│                        数据存储层                                    │
│                                                                     │
│  Database → Cache → File Storage                                   │
└─────────────────────────────────────────────────────────────────────┘
```

## 2. 开发环境搭建

### 2.1 系统要求

- **操作系统**：Windows 10/11, macOS, Linux
- **Java Development Kit (JDK)**：JDK 8 或更高版本
- **Android Studio**：最新稳定版本
- **Android SDK**：API 26 (Android 8.0) 或更高版本
- **Gradle**：8.1.3 或更高版本
- **Git**：最新稳定版本

### 2.2 环境配置

#### 2.2.1 JDK安装

1. 下载并安装JDK 8或更高版本
2. 配置JAVA_HOME环境变量
3. 验证安装：`java -version`

#### 2.2.2 Android Studio安装

1. 下载并安装最新版本的Android Studio
2. 启动Android Studio并安装必要的SDK组件
3. 配置Android SDK路径

#### 2.2.3 项目克隆

```bash
# 克隆项目代码
git clone https://github.com/your-username/smartquiz-app.git
cd smartquiz-app

# 安装依赖
./gradlew build
```

#### 2.2.4 配置文件

- **local.properties**：配置Android SDK路径
  ```
  sdk.dir=/path/to/android/sdk
  ```

- **gradle.properties**：配置Gradle属性
  ```
  org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
  android.useAndroidX=true
  android.enableJetifier=true
  ```

### 2.3 开发环境启动

1. 打开Android Studio并导入项目
2. 等待Gradle同步完成
3. 运行应用到模拟器或真机

## 3. 测试环境部署

### 3.1 环境配置

- **服务器**：至少2核4GB内存
- **操作系统**：Ubuntu 20.04 LTS或更高版本
- **数据库**：PostgreSQL 13或更高版本
- **缓存**：Redis 6或更高版本
- **Web服务器**：Nginx 1.18或更高版本

### 3.2 部署步骤

#### 3.2.1 服务器准备

1. 更新系统
   ```bash
   sudo apt update && sudo apt upgrade -y
   ```

2. 安装必要软件
   ```bash
   sudo apt install -y git openjdk-11-jdk postgresql postgresql-contrib redis-server nginx
   ```

#### 3.2.2 数据库配置

1. 启动PostgreSQL服务
   ```bash
   sudo systemctl start postgresql
   sudo systemctl enable postgresql
   ```

2. 创建数据库和用户
   ```bash
   sudo -u postgres psql
   CREATE DATABASE smartquiz;
   CREATE USER smartquizuser WITH PASSWORD 'your_password';
   GRANT ALL PRIVILEGES ON DATABASE smartquiz TO smartquizuser;
   \q
   ```

#### 3.2.3 应用部署

1. 克隆代码
   ```bash
   git clone https://github.com/your-username/smartquiz-api.git
   cd smartquiz-api
   ```

2. 构建应用
   ```bash
   ./gradlew build
   ```

3. 配置应用
   ```bash
   cp application.properties.example application.properties
   # 编辑application.properties文件，配置数据库连接等
   ```

4. 启动应用
   ```bash
   java -jar build/libs/smartquiz-api.jar
   ```

#### 3.2.4 Nginx配置

1. 创建Nginx配置文件
   ```bash
   sudo nano /etc/nginx/sites-available/smartquiz
   ```

2. 配置反向代理
   ```
   server {
       listen 80;
       server_name test.example.com;

       location / {
           proxy_pass http://localhost:8080;
           proxy_set_header Host $host;
           proxy_set_header X-Real-IP $remote_addr;
           proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
           proxy_set_header X-Forwarded-Proto $scheme;
       }
   }
   ```

3. 启用配置
   ```bash
   sudo ln -s /etc/nginx/sites-available/smartquiz /etc/nginx/sites-enabled/
   sudo nginx -t
   sudo systemctl reload nginx
   ```

## 4. 生产环境部署

### 4.1 环境配置

- **服务器**：至少4核8GB内存，建议使用云服务器
- **操作系统**：Ubuntu 20.04 LTS或更高版本
- **数据库**：PostgreSQL 13或更高版本（主从复制）
- **缓存**：Redis 6或更高版本（集群）
- **Web服务器**：Nginx 1.18或更高版本（负载均衡）
- **SSL证书**：Let's Encrypt或商业SSL证书

### 4.2 部署策略

#### 4.2.1 高可用性

- **负载均衡**：使用Nginx或ELB进行负载均衡
- **数据库集群**：配置PostgreSQL主从复制
- **缓存集群**：配置Redis集群
- **多可用区**：部署在多个可用区

#### 4.2.2 安全性

- **防火墙**：配置防火墙规则
- **SSL证书**：配置HTTPS
- **密钥管理**：使用密钥管理服务
- **定期安全扫描**：定期进行安全扫描

#### 4.2.3 监控

- **应用监控**：使用Prometheus和Grafana
- **服务器监控**：使用Node Exporter
- **数据库监控**：使用PostgreSQL Exporter
- **告警机制**：配置邮件或短信告警

### 4.3 部署步骤

#### 4.3.1 基础设施准备

1. 配置VPC和子网
2. 创建安全组
3. 启动EC2实例或其他云服务器
4. 配置负载均衡器
5. 配置数据库实例

#### 4.3.2 应用部署

1. 使用CI/CD工具（如Jenkins、GitHub Actions）自动部署
2. 配置环境变量和配置文件
3. 启动应用服务
4. 配置负载均衡器
5. 配置SSL证书

#### 4.3.3 数据库迁移

1. 执行数据库迁移脚本
2. 验证数据完整性
3. 配置数据库备份

#### 4.3.4 监控配置

1. 部署监控组件
2. 配置监控指标
3. 设置告警阈值
4. 验证监控功能

## 5. 持续集成与持续部署

### 5.1 CI/CD流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                           代码提交                                   │
│                                                                     │
│  Git Push → Webhook → CI Server                                   │
├─────────────────────────────────────────────────────────────────────┤
│                         构建与测试                                    │
│                                                                     │
│  Build → Test → Code Analysis → Security Scan                     │
├─────────────────────────────────────────────────────────────────────┤
│                         部署                                        │
│                                                                     │
│  Staging Deployment → Test → Production Deployment                │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 CI/CD配置

#### 5.2.1 GitHub Actions配置

```yaml
name: CI/CD Pipeline

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main, develop ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 11
        uses: actions/setup-java@v3
        with:
          java-version: '11'
          distribution: 'adopt'
      - name: Build with Gradle
        run: ./gradlew build
      - name: Run tests
        run: ./gradlew test
      - name: Code analysis
        run: ./gradlew check

  deploy:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    steps:
      - uses: actions/checkout@v3
      - name: Deploy to production
        run: |
          # 部署脚本
          ssh user@server 'bash -s' < deploy.sh
```

#### 5.2.2 Jenkins配置

1. 安装Jenkins
2. 配置Jenkins项目
3. 配置构建步骤
4. 配置部署步骤
5. 配置触发条件

### 5.3 部署脚本

#### 5.3.1 部署脚本示例

```bash
#!/bin/bash

# 部署脚本

# 设置变量
APP_NAME="smartquiz-api"
APP_VERSION="1.0.0"
SERVER_USER="deploy"
SERVER_HOST="prod.example.com"
DEPLOY_DIR="/opt/apps/$APP_NAME"

# 停止旧版本
ssh $SERVER_USER@$SERVER_HOST "systemctl stop $APP_NAME"

# 上传新版本
scp build/libs/$APP_NAME-$APP_VERSION.jar $SERVER_USER@$SERVER_HOST:$DEPLOY_DIR/

# 启动新版本
ssh $SERVER_USER@$SERVER_HOST "systemctl start $APP_NAME"

# 验证部署
ssh $SERVER_USER@$SERVER_HOST "systemctl status $APP_NAME"
```

#### 5.3.2 服务配置

```
# /etc/systemd/system/smartquiz-api.service
[Unit]
Description=Smart Quiz API Service
After=network.target

[Service]
User=deploy
WorkingDirectory=/opt/apps/smartquiz-api
ExecStart=/usr/bin/java -jar smartquiz-api.jar
Restart=always
RestartSec=10
Environment=JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
Environment=SPRING_PROFILES_ACTIVE=production

[Install]
WantedBy=multi-user.target
```

## 6. 数据库部署

### 6.1 数据库配置

#### 6.1.1 PostgreSQL配置

- **配置文件**：`/etc/postgresql/13/main/postgresql.conf`
- **主要参数**：
  - `shared_buffers`：内存的25%
  - `work_mem`：32MB
  - `maintenance_work_mem`：内存的10%
  - `effective_cache_size`：内存的50%
  - `random_page_cost`：1.1
  - `effective_io_concurrency`：200

#### 6.1.2 数据库备份

- **自动备份**：使用cron定时执行备份
- **备份策略**：
  - 每日增量备份
  - 每周全量备份
  - 备份保留30天

- **备份脚本**：
  ```bash
  #!/bin/bash
  
  # 备份脚本
  
  DATE=$(date +%Y-%m-%d)
  BACKUP_DIR="/backup/postgresql"
  DB_NAME="smartquiz"
  
  mkdir -p $BACKUP_DIR
  
  # 执行备份
  pg_dump -U smartquizuser -d $DB_NAME > $BACKUP_DIR/$DB_NAME-$DATE.sql
  
  # 压缩备份文件
  gzip $BACKUP_DIR/$DB_NAME-$DATE.sql
  
  # 删除30天前的备份
  find $BACKUP_DIR -name "*.gz" -mtime +30 -delete
  ```

### 6.2 数据库迁移

- **使用Flyway**：
  ```bash
  ./gradlew flywayMigrate
  ```

- **迁移脚本示例**：
  ```sql
  -- V1__create_users_table.sql
  CREATE TABLE IF NOT EXISTS users (
      id SERIAL PRIMARY KEY,
      username VARCHAR(255) NOT NULL UNIQUE,
      email VARCHAR(255) NOT NULL UNIQUE,
      password VARCHAR(255) NOT NULL,
      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  );
  ```

## 7. 监控与维护

### 7.1 监控系统

#### 7.1.1 Prometheus配置

- **安装Prometheus**：
  ```bash
  wget https://github.com/prometheus/prometheus/releases/download/v2.35.0/prometheus-2.35.0.linux-amd64.tar.gz
  tar xvfz prometheus-2.35.0.linux-amd64.tar.gz
  cd prometheus-2.35.0.linux-amd64
  ./prometheus --config.file=prometheus.yml
  ```

- **配置文件**：
  ```yaml
  global:
    scrape_interval: 15s

  scrape_configs:
    - job_name: 'smartquiz-api'
      static_configs:
        - targets: ['localhost:8080']
    - job_name: 'node'
      static_configs:
        - targets: ['localhost:9100']
    - job_name: 'postgres'
      static_configs:
        - targets: ['localhost:9187']
  ```

#### 7.1.2 Grafana配置

- **安装Grafana**：
  ```bash
  sudo apt install -y grafana
  sudo systemctl start grafana-server
  sudo systemctl enable grafana-server
  ```

- **配置仪表盘**：
  - 导入PostgreSQL仪表盘
  - 导入JVM仪表盘
  - 导入服务器仪表盘

### 7.2 日志管理

#### 7.2.1 日志配置

- **应用日志**：使用Logback配置
- **服务器日志**：使用rsyslog
- **日志轮转**：使用logrotate

#### 7.2.2 日志聚合

- **使用ELK Stack**：
  - Elasticsearch：存储日志
  - Logstash：处理日志
  - Kibana：可视化日志

- **配置示例**：
  ```yaml
  # logstash.conf
  input {
    file {
      path => "/var/log/smartquiz/*.log"
      start_position => "beginning"
    }
  }
  
  output {
    elasticsearch {
      hosts => ["localhost:9200"]
      index => "smartquiz-logs-%{+YYYY.MM.dd}"
    }
  }
  ```

### 7.3 维护计划

#### 7.3.1 日常维护

- **日志检查**：每日检查应用日志
- **备份验证**：每日验证备份完整性
- **性能监控**：监控系统性能指标

#### 7.3.2 定期维护

- **每周**：
  - 系统更新
  - 数据库优化
  - 日志清理

- **每月**：
  - 安全扫描
  - 性能评估
  - 容量规划

- **每季度**：
  - 完整备份
  - 系统审计
  - 灾难恢复测试

## 8. 故障处理

### 8.1 常见故障

| 故障类型 | 可能原因 | 解决方案 |
|---------|---------|----------|
| 应用崩溃 | 内存不足、代码错误 | 检查日志、增加内存、修复代码 |
| 数据库连接失败 | 网络问题、数据库服务停止 | 检查网络、重启数据库服务 |
| 性能下降 | 资源不足、查询优化 | 增加资源、优化查询 |
| 安全漏洞 | 未及时更新、配置错误 | 及时更新、修复配置 |

### 8.2 故障处理流程

1. **故障发现**：通过监控系统或用户报告发现故障
2. **故障定位**：分析日志和监控数据，定位故障原因
3. **故障隔离**：隔离故障，防止影响其他服务
4. **故障修复**：根据故障原因采取相应的修复措施
5. **故障验证**：验证故障是否已修复
6. **故障记录**：记录故障原因和修复过程

### 8.3 灾难恢复

1. **灾难评估**：评估灾难影响范围
2. **恢复计划**：执行灾难恢复计划
3. **数据恢复**：从备份恢复数据
4. **服务恢复**：恢复应用服务
5. **验证恢复**：验证系统是否正常运行
6. **恢复测试**：定期进行灾难恢复测试

## 9. 部署自动化

### 9.1 基础设施即代码

- **使用Terraform**：
  ```hcl
  # main.tf
  resource "aws_instance" "app_server" {
    ami           = "ami-0c55b159cbfafe1f0"
    instance_type = "t2.micro"
    
    tags = {
      Name = "smartquiz-app-server"
    }
  }
  ```

- **使用Ansible**：
  ```yaml
  # playbook.yml
  ---  
  - hosts: web_servers
    tasks:
      - name: Install Java
        apt:
          name: openjdk-11-jdk
          state: present
      
      - name: Deploy application
        copy:
          src: build/libs/smartquiz-api.jar
          dest: /opt/apps/
  ```

### 9.2 容器化部署

- **使用Docker**：
  ```dockerfile
  # Dockerfile
  FROM openjdk:11-jre-slim
  
  WORKDIR /app
  
  COPY build/libs/smartquiz-api.jar app.jar
  
  EXPOSE 8080
  
  ENTRYPOINT ["java", "-jar", "app.jar"]
  ```

- **使用Docker Compose**：
  ```yaml
  # docker-compose.yml
  version: '3'
  services:
    app:
      build: .
      ports:
        - "8080:8080"
      depends_on:
        - db
    db:
      image: postgres:13
      environment:
        POSTGRES_DB: smartquiz
        POSTGRES_USER: smartquizuser
        POSTGRES_PASSWORD: your_password
  ```

## 10. 总结

本部署指南详细介绍了智能题库应用的部署流程，包括开发环境搭建、测试环境部署、生产环境部署、持续集成与持续部署、数据库部署、监控与维护、故障处理和部署自动化等方面。

通过遵循本指南，可以确保应用在不同环境中稳定运行，提高部署效率，减少部署错误，确保系统的可靠性和可用性。同时，自动化部署和监控可以减少运维成本，提高系统的可维护性。

随着应用的发展和技术的进步，部署策略也需要不断调整和优化，以适应新的需求和技术趋势。