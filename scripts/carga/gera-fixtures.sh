#!/usr/bin/env bash
# Gera os videos de carga do ticket 025. Fora do git de proposito (.gitignore): sao dezenas
# de MB deterministicos, e versiona-los trocaria "rode um comando" por "clone mais pesado
# para sempre".
#
# Nao reaproveita `extracao/src/test/resources/fixtures/video-valido.mp4`: aquilo e fixture
# de *correcao* (8 KB, 320x240), dimensionado para o teste rodar rapido. Aqui os dois
# fixtures tem papeis diferentes:
#
#   controle-3s.mp4   720p real, so 3 segundos. O tempo de extracao dele e quase todo
#                     overhead fixo (fila, MinIO, boot do ffmpeg) — e por isso que ele
#                     mede a *borda* e nao o ffmpeg. E o fixture da rajada do 025.
#   carga-2min.mp4    720p, 120 segundos, 120 frames extraidos. Aqui o trabalho e do
#                     ffmpeg, que e o que o 026 precisa para medir vazao por replica.
#
# Uso: scripts/carga/gera-fixtures.sh [--forca]
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
destino="$raiz/scripts/carga/fixtures"

forca=false
[[ "${1:-}" == "--forca" ]] && forca=true

command -v ffmpeg >/dev/null || { echo "ffmpeg nao esta no PATH" >&2; exit 1; }
mkdir -p "$destino"

# testsrc2 em vez de testsrc: tem ruido e movimento, entao o h264 nao comprime o arquivo a
# quase nada — um fixture de 3 KB mediria o upload de um arquivo que nao existe na pratica.
gera() {
    local nome="$1" segundos="$2"
    local arquivo="$destino/$nome"

    if [[ -f "$arquivo" && "$forca" == false ]]; then
        echo "    ja existe  $nome ($(du -h "$arquivo" | cut -f1))"
        return
    fi

    ffmpeg -y -loglevel error \
        -f lavfi -i "testsrc2=size=1280x720:rate=30" \
        -t "$segundos" -c:v libx264 -preset veryfast -pix_fmt yuv420p \
        "$arquivo"
    echo "    gerado     $nome ($(du -h "$arquivo" | cut -f1), ${segundos}s)"
}

echo "==> Fixtures de carga em $destino"
gera controle-3s.mp4 3
gera carga-2min.mp4 120
