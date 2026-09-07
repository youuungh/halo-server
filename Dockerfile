# Stage 1: Build
FROM gradle:8.11-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle clean build -x test

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# 비디오 메타데이터/썸네일 추출용 (ffprobe 포함)
RUN apk add --no-cache ffmpeg
COPY --from=build /app/build/libs/*.jar app.jar
RUN mkdir -p /app/data

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
