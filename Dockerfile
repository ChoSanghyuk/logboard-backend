# Logboard backend: layered Spring Boot jar on a JRE 21 base, running as a non-root user.
# Build: docker build -t logboard-backend .   (the root docker-compose.yml does this)

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Gradle + dependencies first, so source changes reuse this layer.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon --quiet bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar$')" app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 logboard \
 && useradd --system --uid 10001 --gid logboard --home-dir /app --shell /usr/sbin/nologin logboard \
 && mkdir -p /data && chown logboard:logboard /data
WORKDIR /app
# Least to most frequently changing.
COPY --from=build /src/extracted/dependencies/ ./
COPY --from=build /src/extracted/spring-boot-loader/ ./
COPY --from=build /src/extracted/snapshot-dependencies/ ./
COPY --from=build /src/extracted/application/ ./
ENV LOGBOARD_STORAGE_ROOT=/data
VOLUME /data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
