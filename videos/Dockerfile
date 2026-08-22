# Contexto de build: o diretorio deste servico, apos `./mvnw package` na raiz.
# Uma unica compilacao Maven serve as tres imagens -- por isso nao ha estagio de build aqui.
FROM eclipse-temurin:21-jre-alpine

ENV LANGUAGE=pt_BR:pt

# A ordem das copias segue o layout fast-jar: do que menos muda para o que mais muda.
COPY target/quarkus-app/lib/ /app/lib/
COPY target/quarkus-app/*.jar /app/
COPY target/quarkus-app/app/ /app/app/
COPY target/quarkus-app/quarkus/ /app/quarkus/

# Diretorio dos uploads em transito (ticket 011): o volume nomeado `fiapx-uploads` monta
# aqui. Criado na imagem com dono 185 de proposito -- um volume nomeado vazio herda dono e
# permissao do ponto de montagem da imagem, e sem isso ele nasceria de root e o processo
# nao conseguiria gravar os 200 MB do upload.
RUN mkdir -p /var/fiapx/uploads && chown -R 185:185 /var/fiapx

EXPOSE 8080
USER 185
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
