# 모든 서비스가 같은 Dockerfile을 쓴다: docker build -f docker/service.Dockerfile --build-arg SERVICE=user .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
COPY build-logic build-logic
COPY libs libs
COPY services services
ARG SERVICE
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew ":services:${SERVICE}:bootJar" --no-daemon --console=plain

FROM eclipse-temurin:21-jre
ARG SERVICE
RUN useradd --system --uid 10001 app
USER app
WORKDIR /app
COPY --from=build /workspace/services/${SERVICE}/build/libs/app.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
