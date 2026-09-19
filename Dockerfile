# syntax=docker/dockerfile:1.7

# ---- Build ------------------------------------------------------------------------------------------------------
# Runs on the build machine's own platform (no emulation); its output is platform-independent bytecode.
FROM --platform=$BUILDPLATFORM eclipse-temurin:24-jdk-noble AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/banking-api.jar extract --layers --launcher --destination target/extracted

# CA bundle for verifying the RDS server certificate (sslmode=verify-full).
ADD --chmod=444 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem certs/rds-global-bundle.pem

# ---- Runtime ----------------------------------------------------------------------------------------------------
FROM eclipse-temurin:24-jre-noble
WORKDIR /app

# Dependencies change far less often than application code, so they get their own cacheable layers.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
COPY --from=build /workspace/certs/ ./certs/

# Unprivileged user. In ECS the root filesystem is also mounted read-only, with a scratch volume at /tmp.
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
