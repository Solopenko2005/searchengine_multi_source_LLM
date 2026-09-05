FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY authorization/pom.xml ./pom.xml
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY authorization/src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build /workspace/target/*.jar ./application.jar
USER app
EXPOSE 5555
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
