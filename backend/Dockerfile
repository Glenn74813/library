# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:25-jre-alpine
# Unicode fonts for PDFs: DejaVu (Latin, Greek, Cyrillic, Arabic, Hebrew) and WenQuanYi (Chinese, Japanese, Korean)
RUN apk add --no-cache font-dejavu font-wqy-zenhei
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /data/files && chown app:app /data/files
WORKDIR /app
COPY --from=build /app/target/smart-library-api-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
