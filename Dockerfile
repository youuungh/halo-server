# Stage 1: Build
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew clean build -x test --no-daemon

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# 비디오 메타데이터/썸네일 추출용 (ffprobe 포함)
RUN apk add --no-cache ffmpeg
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
# 힙 상한을 컨테이너 메모리 한도 기준으로
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
