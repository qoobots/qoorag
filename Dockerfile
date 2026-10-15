# ============================================================================
# qoorag 应用镜像（多阶段构建）
#
# 构建：docker compose --profile app build
# 运行：docker compose --profile app up -d
#
# 说明：
#   - 第一阶段用 Maven 打包；跳过测试（测试在 CI / 本地 mvn test 跑，
#     容器内重复跑会显著拖慢构建）。
#   - 第二阶段用 JRE 运行（pom 声明 java.version=17，故用 temurin 17）。
#   - 设置 -Dfile.encoding=UTF-8：中文 Windows / 容器环境下避免日志与
#     中文字面量乱码（该项目此前已因此踩坑）。
#   - 非 root 用户运行，降低容器逃逸风险。
# ============================================================================

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷依赖描述文件，利用 Docker 层缓存：依赖不变时不重新下载
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests


FROM eclipse-temurin:17-jre
WORKDIR /app

# 健康检查用 wget；安装后随即清理 apt 缓存以减小镜像
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/qoorag-*.jar /app/qoorag.jar

# 非 root 运行
RUN useradd -r -u 1001 -m qoorag \
    && chown -R qoorag:qoorag /app
USER qoorag

ENV JAVA_OPTS="-Xms256m -Xmx768m" \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

EXPOSE 8080

# 健康检查：actuator/health 由 spring-boot-starter-actuator 提供
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=10 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dfile.encoding=UTF-8 -jar /app/qoorag.jar"]
