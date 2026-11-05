FROM eclipse-temurin:21-jre-jammy

RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin app \
    && mkdir -p /data/files \
    && chown app:app /data/files
WORKDIR /app
# 审核身份配置随 JAR 发布；服务用户可读，不能改写受保护制品。
COPY --chown=root:root target/gulupai-server-0.1.0-P16.jar /app/app.jar
USER app

ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx448m"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
