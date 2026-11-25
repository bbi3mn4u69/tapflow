FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d AS build
WORKDIR /source
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY packages/protocol-kotlin packages/protocol-kotlin
COPY services/payment-api services/payment-api
COPY contracts/openapi.yaml contracts/openapi.yaml
# Android projects remain empty: this distribution builds only the API and JVM protocol.
RUN mkdir -p apps/android-card apps/android-pos packages/android-auth && chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew :services:payment-api:installDist --no-daemon --no-parallel -Dorg.gradle.jvmargs=-Xmx1g -Pkotlin.compiler.execution.strategy=in-process

RUN mkdir /native && cd /native && \
    jar xf /source/services/payment-api/build/install/payment-api/lib/argon2-jvm-2.12.jar && \
    for archive in /source/services/payment-api/build/install/payment-api/lib/jna-[0-9]*.jar; do jar xf "$archive"; done && \
    case "$(uname -m)" in aarch64) platform=linux-aarch64 ;; x86_64) platform=linux-x86-64 ;; *) exit 1 ;; esac && \
    cp "$platform/libargon2.so" ./libargon2.so && cp "com/sun/jna/$platform/libjnidispatch.so" ./libjnidispatch.so

FROM eclipse-temurin:21.0.13_9-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f
RUN groupadd --gid 10001 tapflow && useradd --uid 10001 --gid 10001 --no-create-home tapflow
WORKDIR /app
COPY --from=build --chown=10001:10001 /source/services/payment-api/build/install/payment-api/ ./
COPY --from=build /native/libargon2.so /native/libjnidispatch.so /app/native/
ENV JAVA_TOOL_OPTIONS="-Djna.boot.library.path=/app/native -Djna.library.path=/app/native -Djna.nounpack=true"
ENV API_HOST=0.0.0.0 APP_ENV=production DEMO_MODE=true JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 CMD ["java", "-cp", "/app/lib/*", "com.tapflow.api.HealthProbe"]
ENTRYPOINT ["/app/bin/payment-api"]
