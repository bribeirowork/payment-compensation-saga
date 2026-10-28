package br.com.example.fiscal.domain;

import java.time.YearMonth;

/**
 * Chave de acesso da NF-e (44 dígitos):
 * cUF(2) AAMM(4) CNPJ(14) mod(2) série(3) nNF(9) tpEmis(1) cNF(8) DV(1).
 *
 * É gerada UMA vez, gravada antes do envio e nunca muda: funciona como chave de idempotência
 * natural junto à SEFAZ (Caso 3). Reemitir com outro número arrisca duas notas para a mesma venda.
 */
public record AccessKey(String value) {

    private static final String NFE_MODEL = "55";
    private static final String NORMAL_ISSUANCE = "1";

    public AccessKey {
        if (value == null || !value.matches("\\d{44}")) {
            throw new DomainException("Chave de acesso deve ter 44 dígitos");
        }
        if (checkDigit(value.substring(0, 43)) != value.charAt(43) - '0') {
            throw new DomainException("Dígito verificador da chave de acesso inválido");
        }
    }

    public static AccessKey generate(String ufCode, YearMonth period, String cnpj, int series, long number) {
        var numericCode = String.format("%08d", Math.floorMod(number * 7_919L + 13L, 100_000_000L));
        var base = ufCode
                + String.format("%02d%02d", period.getYear() % 100, period.getMonthValue())
                + cnpj
                + NFE_MODEL
                + String.format("%03d", series)
                + String.format("%09d", number)
                + NORMAL_ISSUANCE
                + numericCode;
        return new AccessKey(base + checkDigit(base));
    }

    /** Módulo 11 com pesos 2..9 da direita para a esquerda. */
    static int checkDigit(String base) {
        int sum = 0;
        int weight = 2;
        for (int i = base.length() - 1; i >= 0; i--) {
            sum += (base.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    @Override
    public String toString() {
        return value;
    }
}
