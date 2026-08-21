# Contexto de build: o diretorio deste servico, apos `./mvnw package` na raiz.
# Diferente dos outros dois, esta imagem carrega o ffmpeg: a Extracao roda como processo
# externo (ticket 006), nao como biblioteca embarcada.
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache ffmpeg

ENV LANGUAGE=pt_BR:pt

COPY target/quarkus-app/lib/ /app/lib/
COPY target/quarkus-app/*.jar /app/
COPY target/quarkus-app/app/ /app/app/
COPY target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080
USER 185
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
