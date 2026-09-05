FROM mcr.microsoft.com/devcontainers/java:25-trixie

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        jq \
        ripgrep \
        fd-find \
        ffmpeg \
        fzf \
        shellcheck \
    && rm -rf /var/lib/apt/lists/*

USER vscode
