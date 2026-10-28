package br.com.example.fiscal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Anti-corruption layer fiscal: nenhum outro serviço fala com a SEFAZ.
 * Recebe comandos (IssueInvoice, CancelInvoice) pelo Kafka e responde com eventos.
 */
@SpringBootApplication
@EnableScheduling
public class FiscalDocumentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(FiscalDocumentGatewayApplication.class, args);
    }
}
