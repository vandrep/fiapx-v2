// Tráfego sintético do ticket 093. Roda dentro do container `grafana/k6`, na rede do Compose —
// nada é instalado no host, e o alvo é `videos:8080` direto.
//
// Este arquivo é irmão do `scripts/carga/injetor.js` e **não** é uma evolução dele. Aquele é o
// instrumento que produziu o denominador dos tickets 025-028: mexer nele quebraria a
// comparabilidade de qualquer corrida futura do overlay de carga. O que daqui é cópia consciente
// de lá: o trato do token e o `k6/experimental/fs`. O resto é outro desenho, com outra
// finalidade — aquele MEDE a borda, este ALIMENTA os painéis.
//
// A diferença de propósito aparece em tudo: aqui há mistura de verbos, mistura de fixtures,
// erros deliberados, dois donos e 20 minutos de blocos alternados. Nada disso faria sentido num
// experimento, onde cada variável a mais é ruído; tudo isso é o ponto quando o que se avalia é
// um painel.
//
// A saída que o bash consome continua sendo uma linha `ACEITO <id>` por `202`, no
// `--console-output` — é dela que sai a lista de ids que o `oraculo.sh` usa no censo.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { open as fsopen, SeekMode } from 'k6/experimental/fs';

const videosUrl = __ENV.VIDEOS_URL;
const keycloakUrl = __ENV.KEYCLOAK_URL;

const donos = [
    { usuario: __ENV.USUARIO, senha: __ENV.SENHA },
    { usuario: __ENV.USUARIO_2, senha: __ENV.SENHA_2 },
];

const duracaoMin = Number(__ENV.DURACAO_MIN);
const blocoMin = Number(__ENV.BLOCO_MIN);
const taxaSustentada = Number(__ENV.TAXA_SUSTENTADA);   // Vídeo/min
const rajadaEnvios = Number(__ENV.RAJADA_ENVIOS);
const rajadaVus = Number(__ENV.RAJADA_VUS);
const taxaCiclo = Number(__ENV.TAXA_CICLO);             // iterações/min
const taxaErro = Number(__ENV.TAXA_ERRO);               // iterações/min
const fracaoDownload = Number(__ENV.FRACAO_DOWNLOAD);

// A mistura de fixtures é o que espalha `fiapx.extracao.duracao`: só o de controle põe todas as
// observações no mesmo bucket. O `invalido.mp4` é o que produz `resultado=falhou` — sem ele o
// corte por resultado é uma série só, e o atributo deixa de provar qualquer coisa.
// Os limites de bucket continuam errados de qualquer forma; isso é o ticket 094.
const mistura = [
    { nome: 'controle-3s.mp4', peso: Number(__ENV.PESO_CONTROLE) },
    { nome: 'carga-2min.mp4', peso: Number(__ENV.PESO_CARGA) },
    { nome: 'invalido.mp4', peso: Number(__ENV.PESO_INVALIDO) },
];

// `k6/experimental/fs` em vez do `open()` do contexto de init, pela mesma razão do injetor: o
// `open()` deixa uma cópia do arquivo por VU. Aqui são três arquivos, um deles de 2 min.
for (const fixture of mistura) {
    fixture.arquivo = await fsopen(`/fixtures/${fixture.nome}`);
    fixture.tamanho = (await fixture.arquivo.stat()).size;
}
const pesoTotal = mistura.reduce((soma, f) => soma + f.peso, 0);

// ---------------------------------------------------------------------------------------
// Blocos alternados.
//
// Um `k6 run` só, com os cenários gerados por `startTime`, e não um laço em bash disparando um
// run por bloco: o laço pagaria boot de container a cada 5 min, fragmentaria o `handleSummary` e
// perderia o token entre blocos. Os blocos ficam exatos porque `startTime` é do próprio k6.
//
// Começa pela SUSTENTADA. Rajada primeiro não tem linha de base contra a qual ler o pico no
// painel, e a linha de base é o que faz o pico significar algo.
function blocos() {
    const cenarios = {};
    const quantos = Math.max(1, Math.round(duracaoMin / blocoMin));
    for (let i = 0; i < quantos; i++) {
        const inicio = `${i * blocoMin}m`;
        if (i % 2 === 0) {
            cenarios[`sustentada_${i}`] = {
                executor: 'constant-arrival-rate',
                exec: 'envia',
                startTime: inicio,
                duration: `${blocoMin}m`,
                rate: taxaSustentada,
                timeUnit: '1m',
                preAllocatedVUs: Math.max(2, Math.ceil(taxaSustentada / 2)),
                maxVUs: Math.max(4, taxaSustentada),
                tags: { bloco: 'sustentada' },
            };
        } else {
            // Tudo em t=0 do bloco, e o resto dele drenando. A DRENAGEM é o sinal que os painéis
            // de fila do ticket 092 mostram — profundidade subindo, não-confirmadas caindo,
            // consumidores ocupados. Taxa de chegada alta distribuída pelos 5 min produziria um
            // platô, que é só a sustentada mais rápida.
            cenarios[`rajada_${i}`] = {
                executor: 'shared-iterations',
                exec: 'envia',
                startTime: inicio,
                vus: rajadaVus,
                iterations: rajadaEnvios,
                maxDuration: `${blocoMin}m`,
                tags: { bloco: 'rajada' },
            };
        }
    }
    return cenarios;
}

export const options = {
    scenarios: Object.assign(blocos(), {
        // Os dois rodam a corrida INTEIRA, inclusive durante a drenagem da rajada: é o que
        // mantém os painéis de HTTP vivos quando ninguém está enviando, e "simular uso da API"
        // inclui quem está olhando, não só quem envia.
        ciclo: {
            executor: 'constant-arrival-rate',
            exec: 'ciclo',
            duration: `${duracaoMin}m`,
            rate: taxaCiclo,
            timeUnit: '1m',
            preAllocatedVUs: 4,
            maxVUs: 16,
        },
        // Cenário próprio, e não sorteio dentro do ciclo: com sorteio a proporção real flutuaria
        // com a duração de cada ciclo e deixaria de ser parâmetro legível.
        erro: {
            executor: 'constant-arrival-rate',
            exec: 'erro',
            duration: `${duracaoMin}m`,
            rate: taxaErro,
            timeUnit: '1m',
            preAllocatedVUs: 2,
            maxVUs: 4,
        },
    }),
    // Este script RELATA, nunca reprova (ticket 093): os thresholds existem só para o k6 imprimir
    // as séries separadas por tipo, com limite que nenhuma corrida alcança.
    thresholds: {
        'http_req_duration{tipo:envio}': ['p(95)>=0'],
        'http_req_duration{tipo:listagem}': ['p(95)>=0'],
        'http_req_duration{tipo:consulta}': ['p(95)>=0'],
        'http_req_duration{tipo:download}': ['p(95)>=0'],
    },
    // `count` entra na lista porque o resumo o usa para distinguir "sem amostra" de
    // "amostra zero": fora de `summaryTrendStats` ele nao aparece em `values` nenhum.
    summaryTrendStats: ['count', 'min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    noConnectionReuse: false,
    insecureSkipTLSVerify: true,
};

const aceitos = new Counter('fiapx_aceitos');
const recusados = new Counter('fiapx_recusados');
const errosComoEsperado = new Counter('fiapx_erros_como_esperado');
const errosInesperados = new Counter('fiapx_erros_inesperados');
const corridasDo409 = new Counter('fiapx_corridas_do_409');
const pacoteBytes = new Trend('fiapx_pacote_bytes');

// ---------------------------------------------------------------------------------------
// Token por dono. Renova um minuto antes de expirar, pela mesma razão do injetor: requisição que
// sai com token de vida curta volta 401 e viraria defeito aparente sem o sistema ter falhado.
const tokens = {};

function token(dono) {
    const cache = tokens[dono.usuario];
    if (cache && Date.now() < cache.expiraEm) {
        return cache.valor;
    }
    const resposta = http.post(
        `${keycloakUrl}/realms/fiapx/protocol/openid-connect/token`,
        { grant_type: 'password', client_id: 'fiapx-videos', username: dono.usuario, password: dono.senha },
        { tags: { tipo: 'token' } });
    if (resposta.status !== 200) {
        throw new Error(`Keycloak devolveu ${resposta.status} no grant de senha de ${dono.usuario}`);
    }
    const corpo = resposta.json();
    tokens[dono.usuario] = {
        valor: corpo.access_token,
        expiraEm: Date.now() + (corpo.expires_in - 60) * 1000,
    };
    return tokens[dono.usuario].valor;
}

function cabecalho(dono) {
    return { Authorization: `Bearer ${token(dono)}` };
}

// 70/30 entre os dois donos: os dois precisam ter Vídeo para a listagem de cada um devolver algo,
// e `demo` precisa ter a maioria porque é como ele que o `oraculo.sh amostra` confere.
function donoSorteado() {
    return Math.random() < 0.7 ? donos[0] : donos[1];
}

function fixtureSorteada() {
    let sorteio = Math.random() * pesoTotal;
    for (const fixture of mistura) {
        sorteio -= fixture.peso;
        if (sorteio <= 0) return fixture;
    }
    return mistura[0];
}

async function bytes(fixture) {
    const conteudo = new Uint8Array(fixture.tamanho);
    await fixture.arquivo.seek(0, SeekMode.Start);
    await fixture.arquivo.read(conteudo);
    return conteudo.buffer;
}

async function envio(dono, fixture, tipo) {
    return http.post(
        `${videosUrl}/videos`,
        { arquivo: http.file(await bytes(fixture), fixture.nome, 'video/mp4') },
        { headers: cabecalho(dono), tags: { tipo }, timeout: '180s' });
}

// ---------------------------------------------------------------------------------------
// `setup` produz o Vídeo do OUTRO dono, que é como o 404 do contrato nasce: "existe e não é
// seu". Um UUID aleatório também devolveria 404, mas pelo motivo errado — não prova a regra.
//
// Ele NÃO imprime `ACEITO`: o id não entra no denominador do censo porque o `oraculo.sh amostra`
// consulta como `demo`, e um Vídeo de `outro` apareceria como divergência sendo correção correta.
export async function setup() {
    const resposta = await envio(donos[1], mistura[0], 'envio-setup');
    if (resposta.status !== 202) {
        throw new Error(`setup: POST /videos como ${donos[1].usuario} devolveu ${resposta.status}`);
    }
    return { idDoOutroDono: resposta.json('id') };
}

// ---------------------------------------------------------------------------------------
export async function envia() {
    const dono = donoSorteado();
    const fixture = fixtureSorteada();
    const resposta = await envio(dono, fixture, 'envio');

    if (resposta.status === 202) {
        aceitos.add(1);
        console.log(`ACEITO ${resposta.json('id')} ${dono.usuario}`);
    } else {
        // status 0 é erro de transporte (conexão recusada, timeout); `resposta.error` diz qual.
        const detalhe = resposta.error ? ` ${resposta.error.replace(/\s+/g, ' ')}` : '';
        recusados.add(1);
        console.log(`RECUSADO ${resposta.status}${detalhe} ${fixture.nome} ${dono.usuario}`);
    }
}

// O ciclo do usuário que está OLHANDO: lista filtrado e paginado, consulta um, e às vezes baixa.
// A listagem filtrada é de propósito — exercita o parâmetro `estado` e a paginação, que nenhum
// outro script toca sob carga, e é o jeito realista de achar Vídeo sem estado compartilhado
// entre VUs (o k6 não tem).
export function ciclo() {
    const dono = donoSorteado();
    const estados = ['CONCLUIDO', 'RECEBIDO', 'PROCESSANDO', 'FALHOU'];
    const estado = estados[Math.floor(Math.random() * estados.length)];
    const pagina = Math.floor(Math.random() * 3);

    const listagem = http.get(
        `${videosUrl}/videos?estado=${estado}&pagina=${pagina}&tamanho=10`,
        { headers: cabecalho(dono), tags: { tipo: 'listagem' } });
    if (listagem.status !== 200) {
        errosInesperados.add(1);
        console.log(`INESPERADO listagem ${listagem.status} ${dono.usuario}`);
        return;
    }

    const itens = listagem.json('conteudo') || [];
    if (itens.length === 0) return;
    const video = itens[Math.floor(Math.random() * itens.length)];

    http.get(`${videosUrl}/videos/${video.id}`,
        { headers: cabecalho(dono), tags: { tipo: 'consulta' } });

    // Corpo INTEIRO, e só numa fração dos CONCLUIDO. Inteiro porque o download é o caminho
    // `RestMulti` de streaming (ticket 016) e descartar o corpo mediria o cabeçalho; fração
    // porque baixar sempre poria dezenas de MB por minuto competindo com a Extração no mesmo
    // host, e aí o script deformaria o que ele existe para observar.
    if (video.estado === 'CONCLUIDO' && Math.random() < fracaoDownload) {
        const pacote = http.get(`${videosUrl}/videos/${video.id}/pacote`,
            { headers: cabecalho(dono), tags: { tipo: 'download' }, timeout: '180s' });
        if (pacote.status === 200) {
            pacoteBytes.add(pacote.body.length);
        } else if (pacote.status !== 410) {
            // 410 é legítimo: o objeto pode ter expirado no MinIO entre a listagem e o pedido.
            errosInesperados.add(1);
            console.log(`INESPERADO download ${pacote.status} ${video.id}`);
        }
    }
}

// As quatro rejeições de borda do contrato, cada uma pelo caminho real. `410 Gone` fica fora: ele
// exigiria expirar o objeto no MinIO, que é injeção de falha — e injeção de falha é do
// `conservacao.sh`, não daqui (ticket 093).
export async function erro(dados) {
    const dono = donos[0];
    const confere = (rotulo, resposta, esperado) => {
        if (resposta.status === esperado) {
            errosComoEsperado.add(1);
        } else {
            errosInesperados.add(1);
            console.log(`INESPERADO ${rotulo} devolveu ${resposta.status}, esperava ${esperado}`);
        }
    };

    // 415: o que a borda julga é content-type e extensão, e nenhum dos dois precisa de arquivo em
    // disco. (`invalido.mp4` é inválido de CONTEÚDO — ele vira FALHOU pelo ffprobe, não 415.)
    confere('415', http.post(`${videosUrl}/videos`,
        { arquivo: http.file('nao sou um video', 'nao-e-video.txt', 'text/plain') },
        { headers: cabecalho(dono), tags: { tipo: 'erro-415' } }), 415);

    // 400: multipart com o campo errado. Um corpo urlencoded não serviria — ele testaria o
    // content-type da requisição, que é o caminho do 415.
    confere('400', http.post(`${videosUrl}/videos`,
        { naoEhArquivo: http.file('x', 'x.mp4', 'video/mp4') },
        { headers: cabecalho(dono), tags: { tipo: 'erro-400' } }), 400);

    // 404: Vídeo que EXISTE e não é seu — o id vem do `setup`, enviado como o outro dono.
    confere('404', http.get(`${videosUrl}/videos/${dados.idDoOutroDono}`,
        { headers: cabecalho(dono), tags: { tipo: 'erro-404' } }), 404);

    // 409: Pacote de um Vídeo que o próprio VU acabou de enviar. Pescar um RECEBIDO pela
    // listagem seria pior — aquele pode concluir entre a listagem e o pedido —, mas MEDIDO na
    // corrida de 10 min, nem este caminho é determinístico: 1 em ~40 voltou 200, porque a
    // Extração do fixture de controle leva 0,19 s e cabe inteira entre o 202 e o GET seguinte.
    //
    // Duas consequências, as duas aqui: o envio usa o fixture de 2 min (janela larga o bastante
    // para a corrida virar exceção), e um 200 só conta como defeito se o Vídeo NÃO estiver
    // CONCLUIDO. Um 200 sobre Vídeo concluído é o sistema certo respondendo rápido, e marcá-lo
    // como inesperado treinaria quem lê o relatório a ignorar a palavra.
    const recem = await envio(dono, mistura[1], 'envio');
    if (recem.status !== 202) {
        recusados.add(1);
        console.log(`RECUSADO ${recem.status} no envio do cenario de erro`);
        return;
    }
    const id = recem.json('id');
    aceitos.add(1);
    console.log(`ACEITO ${id} ${dono.usuario}`);

    const pacote = http.get(`${videosUrl}/videos/${id}/pacote`,
        { headers: cabecalho(dono), tags: { tipo: 'erro-409' } });
    if (pacote.status === 409) {
        errosComoEsperado.add(1);
    } else if (pacote.status === 200) {
        const estado = http.get(`${videosUrl}/videos/${id}`,
            { headers: cabecalho(dono), tags: { tipo: 'consulta' } }).json('estado');
        if (estado === 'CONCLUIDO') {
            corridasDo409.add(1);
            console.log(`CORRIDA o 409 virou 200: ${id} concluiu antes do pedido do Pacote`);
        } else {
            errosInesperados.add(1);
            console.log(`INESPERADO 200 no Pacote de ${id}, que esta em ${estado}`);
        }
    } else {
        errosInesperados.add(1);
        console.log(`INESPERADO 409 devolveu ${pacote.status}, esperava 409`);
    }
}

export function handleSummary(dados) {
    const conta = (nome) => (dados.metrics[nome] ? dados.metrics[nome].values.count : 0);
    const trend = (nome) => dados.metrics[nome];
    // `t.values.count` e o que distingue "sem amostra" de "amostra zero": os thresholds criam a
    // submetrica mesmo sem nenhuma requisicao daquele tipo, e ela imprime med=0ms max=0ms — que
    // lê como latencia instantanea em vez de download que nao aconteceu. Achado na primeira
    // corrida de um minuto, curta demais para algum Video concluir e ser baixado.
    const linha = (rotulo, nome) => {
        const t = trend(nome);
        return t && t.values.count > 0
            ? `    ${rotulo}: n=${t.values.count} med=${t.values.med.toFixed(0)}ms p(95)=${t.values['p(95)'].toFixed(0)}ms max=${t.values.max.toFixed(0)}ms`
            : `    ${rotulo}: sem amostra`;
    };

    const linhas = [
        '',
        `    duracao ..........: ${(dados.state.testRunDurationMs / 60000).toFixed(1)} min`,
        `    envios aceitos ...: ${conta('fiapx_aceitos')}`,
        `    envios recusados .: ${conta('fiapx_recusados')}`,
        `    erros esperados ..: ${conta('fiapx_erros_como_esperado')}  (415, 400, 404, 409)`,
        `    erros inesperados : ${conta('fiapx_erros_inesperados')}`,
        `    corridas do 409 ..: ${conta('fiapx_corridas_do_409')}  (Video concluiu antes do pedido; nao e defeito)`,
        `    requisicoes ......: ${conta('http_reqs')}`,
        linha('latencia do 202 ..', 'http_req_duration{tipo:envio}'),
        linha('latencia listagem ', 'http_req_duration{tipo:listagem}'),
        linha('latencia consulta ', 'http_req_duration{tipo:consulta}'),
        linha('latencia download ', 'http_req_duration{tipo:download}'),
        '',
    ];
    return {
        stdout: linhas.join('\n'),
        '/saida/resumo.json': JSON.stringify(dados, null, 2),
    };
}
