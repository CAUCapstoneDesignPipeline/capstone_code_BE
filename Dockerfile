FROM eclipse-temurin:21-jdk-jammy@sha256:e0c60c487345d1dc9d0fc7b6f0496f3cc941e5132e09296cc17a6decc71b902b AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod 0755 gradlew
COPY src ./src
COPY deploy/image/Healthcheck.java /tmp/Healthcheck.java
RUN ./gradlew --no-daemon bootJar && javac -d /tmp/health /tmp/Healthcheck.java

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
ARG SOURCE_REVISION
ARG DOCS_CONTRACT_SHA
LABEL org.opencontainers.image.source="https://github.com/CAUCapstoneDesignPipeline/capstone_code_BE" \
      org.opencontainers.image.revision=$SOURCE_REVISION \
      art.capsnote.contract-sha=$DOCS_CONTRACT_SHA
RUN groupadd --gid 10001 capstone && useradd --uid 10001 --gid 10001 --no-create-home capstone
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/*.jar /app/app.jar
COPY --from=build /tmp/health /app/health
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:InitialRAMPercentage=20 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=6 CMD ["java", "-cp", "/app/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
