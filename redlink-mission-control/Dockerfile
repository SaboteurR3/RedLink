# syntax=docker/dockerfile:1
# Build context: the repository root (needs the parent pom and redlink-contract).
#   docker build -f redlink-mission-control/Dockerfile .

# --- build -------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml lombok.config ./
COPY redlink-contract/pom.xml redlink-contract/pom.xml
COPY redlink-mission-control/pom.xml redlink-mission-control/pom.xml
COPY redlink-rover-sim/pom.xml redlink-rover-sim/pom.xml
RUN chmod +x mvnw
COPY redlink-contract/src redlink-contract/src
COPY redlink-mission-control/src redlink-mission-control/src
# Tests ran before the image is built (./mvnw verify); they need PostgreSQL, which is not here.
# The Maven repository is a BuildKit cache, so dependencies download once, not on every build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -pl redlink-mission-control -am -DskipTests package \
    && cp redlink-mission-control/target/redlink-mission-control-*.jar /app.jar \
    && cd / && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /extracted

# --- run ---------------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-noble
RUN groupadd --system redlink && useradd --system --gid redlink --no-create-home redlink
WORKDIR /app
# Layers from least to most often changing, so a code change rebuilds only the last one.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER redlink
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
