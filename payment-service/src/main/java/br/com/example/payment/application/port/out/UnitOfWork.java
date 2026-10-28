package br.com.example.payment.application.port.out;

import java.util.function.Supplier;

/**
 * Fronteira de transação local. Mantém a camada de aplicação livre de anotações do Spring
 * e deixa explícito ONDE cada transação começa e termina, o que importa quando há chamadas
 * externas no meio (PSP, SEFAZ).
 */
public interface UnitOfWork {

    void execute(Runnable work);

    <T> T compute(Supplier<T> work);
}
