# Une image : le backend Spring Boot qui sert le front React construit.
#   docker build -t productdesigner .
# Le build Maven installe sa propre copie de Node (frontend-maven-plugin) et construit le front. La configuration à l'exécution
# est faite d'environnement seulement : voir deploy/compose.yaml et le README.

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY frontend frontend
COPY src src
RUN mvn --batch-mode --no-transfer-progress package -DskipTests && cp target/productdesigner-*.jar /app.jar

FROM eclipse-temurin:25-jre-noble
RUN useradd --system --uid 10001 --create-home pd
COPY --from=build /app.jar /app/app.jar
USER pd
WORKDIR /home/pd
# Hébergé par défaut : OIDC (fournisseur sous spring.security.oauth2.client.registration.oidc.*), pas de login mock.
# Pas de Docker Compose dans le conteneur ; la base est celle de SPRING_DATASOURCE_*.
ENV HOST=0.0.0.0 \
    PORT=8080 \
    PRODUCTDESIGNER_MOCK=false \
    PRODUCTDESIGNER_OIDC=true \
    SPRING_DOCKER_COMPOSE_ENABLED=false
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
