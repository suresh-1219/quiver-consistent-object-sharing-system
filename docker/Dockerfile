# ---- Build stage ----
# Compiles and packages the shaded jar. Kept separate from the runtime image so the
# final image doesn't carry Maven, the full JDK, or the dependency cache.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the pom first so Docker can cache the dependency download layer independently
# of source changes — editing a .java file won't force Maven to re-resolve gson/JUnit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
# Alpine + a JRE only (not a full JDK): this is the image that actually ships and runs.
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /build/target/quiver-0.0.1-SNAPSHOT.jar quiver.jar
COPY docker/config.json config.json

# Each node's journal lives here; the compose file mounts a named volume over this path
# so persistence survives a container restart, not just a JVM restart.
RUN mkdir -p /app/quiver-data

ENTRYPOINT ["java", "-jar", "quiver.jar"]
