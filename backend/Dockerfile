# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:25-jre
# Unicode fonts for PDFs: DejaVu (Latin, Greek, Cyrillic, Arabic, Hebrew) and WenQuanYi (Chinese, Japanese, Korean);
# YAZ for copy cataloging over Z39.50 (not packaged for Alpine, hence the Ubuntu-based image)
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core fonts-wqy-zenhei yaz \
    && rm -rf /var/lib/apt/lists/*
# Same user and group IDs as the earlier Alpine image, so existing uploaded-file volumes stay writable.
RUN groupadd --system --gid 101 app && useradd --system --uid 100 --gid app --no-create-home app \
    && mkdir -p /data/files && chown app:app /data/files
WORKDIR /app
COPY --from=build /app/target/smart-library-api-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
