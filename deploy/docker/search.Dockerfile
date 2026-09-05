FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY Searchengine_1/pom.xml ./pom.xml
COPY Searchengine_1/libs ./libs

COPY Searchengine_1/src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build /workspace/target/SearchEngine-1.0-SNAPSHOT.jar ./application.jar
RUN mkdir -p /app/data/assistant-vectors /app/logs && chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
