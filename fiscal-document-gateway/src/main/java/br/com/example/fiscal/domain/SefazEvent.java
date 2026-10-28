package br.com.example.fiscal.domain;

import java.time.Instant;

/**
 * Evento registrado na SEFAZ e vinculado à nota. A nota autorizada nunca é apagada:
 * cancelamento (110111) e Carta de Correção (110110) são fatos novos, como em Event Sourcing.
 */
public record SefazEvent(String code, String description, String protocol, String justification,
                        Instant registeredAt) {

    public static final String CANCELLATION = "110111";
    public static final String CORRECTION_LETTER = "110110";
}
