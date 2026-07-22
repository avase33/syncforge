# --- build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- run stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/syncforge-server-*.jar app.jar
EXPOSE 8080
# Virtual-thread server: a small heap serves a large connection count.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
