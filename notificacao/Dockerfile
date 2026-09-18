# Contexto de build: o diretorio deste servico, apos `./mvnw package` na raiz.
# Uma unica compilacao Maven serve as tres imagens -- por isso nao ha estagio de build aqui.
FROM eclipse-temurin:21-jre-alpine

ENV LANGUAGE=pt_BR:pt

# Nome do container para a observabilidade (ticket 096) -- ver o proprio script. Antes do
# fast-jar porque muda menos que ele.
COPY --chmod=755 entrypoint.sh /app/entrypoint.sh

# A ordem das copias segue o layout fast-jar: do que menos muda para o que mais muda.
COPY target/quarkus-app/lib/ /app/lib/
COPY target/quarkus-app/*.jar /app/
COPY target/quarkus-app/app/ /app/app/
COPY target/quarkus-app/quarkus/ /app/quarkus/

EXPOSE 8080
USER 185
ENTRYPOINT ["/app/entrypoint.sh"]
