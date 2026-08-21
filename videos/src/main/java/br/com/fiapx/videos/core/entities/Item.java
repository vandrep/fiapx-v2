package br.com.fiapx.videos.core.entities;

public final class Item {
    private String nome;

    public Item(String nome) {
        this.nome = validarNome(nome);
    }

    public void renomeiaPara(String nome) {
        this.nome = validarNome(nome);
    }

    public String nome() {
        return nome;
    }

    private static String validarNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("Nome do item é obrigatório");
        }
        return nome.trim();
    }
}
