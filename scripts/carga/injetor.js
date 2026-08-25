// Injetor de carga do ticket 025. Roda dentro do container `grafana/k6`, na rede do Compose:
// nada e instalado no host, e o alvo e `videos:8080` direto, sem passar pelo docker-proxy da
// porta publicada.
//
// **k6 injeta, bash julga.** Este arquivo mede o que o *cliente* ve — status e latencia do
// `202` — e nada mais. O destino de cada Video e trabalho do oraculo (`oraculo.sh`), que
// pergunta ao Postgres e a API. Misturar os dois aqui produz o teste que ninguem rele.
//
// A saida que importa nao e o resumo: e uma linha `ACEITO <id>` por `202` e `RECUSADO
// <status>` por qualquer outra coisa, no `--console-output`. Essa lista e o **denominador**
// do experimento — conferir o banco contra ele mesmo responderia "o banco e consistente
// consigo mesmo", que nao e a pergunta.
import http from 'k6/http';
import { open as fsopen, SeekMode } from 'k6/experimental/fs';

const videosUrl = __ENV.VIDEOS_URL;
const keycloakUrl = __ENV.KEYCLOAK_URL;
const usuario = __ENV.USUARIO;
const senha = __ENV.SENHA;
const caminhoArquivo = __ENV.ARQUIVO;
const nomeArquivo = caminhoArquivo.split('/').pop();

// `k6/experimental/fs` em vez do `open()` do contexto de init: o `open()` deixa uma copia do
// arquivo **por VU**, e com 400 VUs carregando o fixture de 2 min isso seriam 16 GB. Aqui o
// arquivo e aberto uma vez e compartilhado; cada iteracao le do descritor comum.
const arquivo = await fsopen(caminhoArquivo);
const tamanhoArquivo = (await arquivo.stat()).size;

export const options = {
    scenarios: {
        rajada: {
            executor: 'shared-iterations',
            vus: Number(__ENV.VUS),
            iterations: Number(__ENV.ENVIOS),
            maxDuration: __ENV.DURACAO_MAXIMA || '10m',
        },
    },
    // A tag separa envio de token: o `grant_type=password` tambem e uma requisicao HTTP, e
    // sem a tag ele entraria no histograma de latencia da borda que esta sob medicao.
    thresholds: {
        'http_req_duration{tipo:envio}': ['p(95)>=0'],
        'http_req_failed{tipo:envio}': ['rate>=0'],
    },
    summaryTrendStats: ['min', 'avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    // Rajada quer conexao nova disputando com as outras, que e o que o pico faz de verdade.
    noConnectionReuse: false,
    insecureSkipTLSVerify: true,
};

// O `access_token` dura 5 min e uma corrida do 026 atravessa isso. Aumentar o
// `accessTokenLifespan` no realm-export.json esta fora de questao — aquele arquivo e fonte
// unica (Compose, Dev Services e banca leem o mesmo), e deformar o objeto medido para
// acomodar o instrumento e o pior tipo de erro de medicao, porque e invisivel.
let token = null;

function tokenValido() {
    if (token === null || Date.now() >= token.expiraEm) {
        const resposta = http.post(
            `${keycloakUrl}/realms/fiapx/protocol/openid-connect/token`,
            { grant_type: 'password', client_id: 'fiapx-videos', username: usuario, password: senha },
            { tags: { tipo: 'token' } });
        if (resposta.status !== 200) {
            throw new Error(`Keycloak devolveu ${resposta.status} no grant de senha`);
        }
        const corpo = resposta.json();
        // Renova um minuto antes de expirar: a requisicao que sai com token de vida curta
        // volta 401 e viraria "perda" no criterio, sem o sistema ter perdido nada.
        token = { valor: corpo.access_token, expiraEm: Date.now() + (corpo.expires_in - 60) * 1000 };
    }
    return token.valor;
}

export default async function () {
    const conteudo = new Uint8Array(tamanhoArquivo);
    await arquivo.seek(0, SeekMode.Start);
    await arquivo.read(conteudo);

    const resposta = http.post(
        `${videosUrl}/videos`,
        { arquivo: http.file(conteudo.buffer, nomeArquivo, 'video/mp4') },
        { headers: { Authorization: `Bearer ${tokenValido()}` }, tags: { tipo: 'envio' }, timeout: '180s' });

    if (resposta.status === 202) {
        console.log(`ACEITO ${resposta.json('id')}`);
    } else {
        // status 0 e erro de transporte (conexao recusada, timeout): `resposta.error` diz
        // qual. Conexao recusada em pico *e* a requisicao perdida que o enunciado proibe,
        // entao ela precisa aparecer na lista com o mesmo destaque de um 500.
        const detalhe = resposta.error ? ` ${resposta.error.replace(/\s+/g, ' ')}` : '';
        console.log(`RECUSADO ${resposta.status}${detalhe}`);
    }
}

export function handleSummary(dados) {
    const envio = dados.metrics['http_req_duration{tipo:envio}'];
    const linhas = [
        '',
        `    envios ...........: ${dados.metrics.iterations.values.count}`,
        `    duracao da rajada : ${(dados.state.testRunDurationMs / 1000).toFixed(1)}s`,
        `    latencia do 202 ..: med=${envio.values.med.toFixed(0)}ms p(95)=${envio.values['p(95)'].toFixed(0)}ms max=${envio.values.max.toFixed(0)}ms`,
        '',
    ];
    return {
        stdout: linhas.join('\n'),
        '/saida/resumo.json': JSON.stringify(dados, null, 2),
    };
}
