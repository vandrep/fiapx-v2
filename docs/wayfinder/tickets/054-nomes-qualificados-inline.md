# Higiene de estilo: nomes qualificados inline

- id: 054
- label: wayfinder:task
- status: fechado
- assignee: andrepinedacunha@gmail.com
- bloqueado-por:
- prioridade: P3

## Origem

Achado do eixo Standards da revisão de `3a3ec95...b4672ff`, convertido em ticket com
aprovação do usuário. O recurso HTTP do `videos` e o adapter de espaço de trabalho do
`extracao` usam nomes totalmente qualificados no meio do código — tipo, anotação e
utilitário escritos por extenso — destoando do resto dos próprios arquivos, que importam
normalmente. É cosmético: nenhum comportamento depende disso.

## O que entregar

Os dois arquivos lêem como o resto do repositório. Nada mais muda.

## Condições de aceite

- [x] Os nomes qualificados inline desses dois arquivos passam a ser importados, salvo onde
  a qualificação existir para desambiguar dois tipos homônimos — nesse caso, mantida e
  comentada.
- [x] Nenhuma mudança de comportamento: a suíte passa sem alteração em nenhum teste.
- [x] Executar a suíte de testes a partir da raiz com a infraestrutura exigida pelo projeto.

## Dependências

Nenhuma. Pode começar imediatamente.

## Resolução

`VideosResource` ganhou import para `java.net.URI`, `jakarta.ws.rs.DefaultValue` e
`java.util.concurrent.CompletableFuture` (o `Supplier` já estava importado, só usado
inline num dos dois lugares); `EspacoDeTrabalhoAdapter` ganhou import para
`java.util.function.Supplier`. Nenhum dos casos desambiguava tipo homônimo — nada ficou
qualificado. `./mvnw test` a partir da raiz, com Docker de pé e `ffmpeg`/`ffprobe` no
`PATH`: 205 (`videos`) + 269 (`extracao`) + 24 (`notificacao`) testes, sem falha.
Revisado com `/code-review` contra `ee1fe46` (commit anterior ao ticket): zero achados
nos dois eixos. Commit `cf612dc`.
