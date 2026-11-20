# 빌드: docker build -t nfcguard-server server-java
# 실행: docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=postgres -e NFCGUARD_DB_URL=... nfcguard-server
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests && cp target/server-*.jar /app.jar

FROM eclipse-temurin:17-jre
# root가 아닌 사용자로 실행
RUN useradd --system --uid 10001 nfcguard && mkdir -p /app/models && chown -R nfcguard /app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER nfcguard
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dnfcguard.model.dir=/app/models", "-jar", "/app/app.jar"]
