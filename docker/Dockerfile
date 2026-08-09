# Seckill-Engine 通用服务镜像（Phase 6.24 演示部署）
# 构建：docker build --build-arg MODULE=gateway -f docker/Dockerfile -t seckill/gateway .
# compose 已按 MODULE 自动构建各服务

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 依赖层缓存：先复制 pom 再拉依赖
COPY pom.xml .
COPY seckill-common/pom.xml seckill-common/pom.xml
COPY test-support/pom.xml test-support/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY seckill-api/pom.xml seckill-api/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY seckill-service/pom.xml seckill-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY monitor/pom.xml monitor/pom.xml
COPY integration-test/pom.xml integration-test/pom.xml
RUN mvn -q -pl gateway,auth-service,seckill-service,inventory-service,order-service,payment-service \
    -am dependency:go-offline -DskipTests || true

COPY . .
ARG MODULE
RUN mvn -q -pl ${MODULE} -am package -DskipTests -Dspotless.check.skip=true

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG MODULE
COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
