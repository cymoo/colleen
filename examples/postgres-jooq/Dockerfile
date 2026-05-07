FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml .
COPY colleen/pom.xml colleen/pom.xml
COPY examples/pom.xml examples/pom.xml
COPY examples/postgres-jooq/pom.xml examples/postgres-jooq/pom.xml

RUN mvn -pl examples/postgres-jooq -am -DskipTests dependency:go-offline

COPY colleen/src colleen/src
COPY examples/postgres-jooq/src examples/postgres-jooq/src

RUN mvn -pl examples/postgres-jooq -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /workspace/examples/postgres-jooq/target/postgres-jooq.jar app.jar

ENV APP_ENV=production
EXPOSE 8000

ENTRYPOINT ["java", "-jar", "app.jar"]
