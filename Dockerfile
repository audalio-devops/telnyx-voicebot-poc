FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/target/atendente-telnyx.jar app.jar
RUN mkdir -p /data/recordings && chown -R app:app /data/recordings
USER app
EXPOSE 8087
HEALTHCHECK --interval=30s --timeout=3s CMD sh -c "wget -qO- http://localhost:${SERVER_PORT:-8087}/actuator/health || exit 1"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

