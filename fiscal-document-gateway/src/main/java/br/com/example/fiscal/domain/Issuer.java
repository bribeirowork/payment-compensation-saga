package br.com.example.fiscal.domain;

/** Quem emite a nota (a loja). ufCode segue a tabela do IBGE, ex.: 35 = SP. */
public record Issuer(String cnpj, String ufCode, int series) {

    public Issuer {
        if (cnpj == null || !cnpj.matches("\\d{14}")) throw new DomainException("CNPJ do emitente inválido");
        if (ufCode == null || !ufCode.matches("\\d{2}")) throw new DomainException("Código da UF inválido");
    }
}
