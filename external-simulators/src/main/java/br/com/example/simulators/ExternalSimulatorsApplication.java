package br.com.example.simulators;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Simula os sistemas que NÃO são seus: a SEFAZ e o gateway de pagamento (PSP).
 * Os cenários de falha são disparados por dados "mágicos" (NCM e cardToken),
 * listados em {@link br.com.exemplo.simulators.sefaz.SefazController} e
 * {@link br.com.exemplo.simulators.psp.PspController}.
 */
@SpringBootApplication
public class ExternalSimulatorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExternalSimulatorsApplication.class, args);
    }
}
