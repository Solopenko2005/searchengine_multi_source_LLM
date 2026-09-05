FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY emailsender/pom.xml ./pom.xml
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY emailsender/src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build /workspace/target/email-sender.jar ./application.jar
USER app
EXPOSE 8771
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
