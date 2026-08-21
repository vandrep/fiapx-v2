package br.com.fiapx.extracao.bdd;

import io.quarkiverse.cucumber.CucumberQuarkusTest;

/**
 * Bootstrap do Cucumber sobre o contexto Quarkus de teste. Descobre automaticamente os
 * arquivos .feature em src/test/resources/features e as classes de step no classpath de
 * teste. Nome termina em "Test" de proposito, para rodar via `./mvnw test` junto dos
 * demais testes @QuarkusTest, e nao apenas em `./mvnw verify`.
 */
class CucumberTest extends CucumberQuarkusTest {
}
