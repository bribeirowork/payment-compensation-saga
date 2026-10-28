package br.com.example.inventory.infrastructure.persistence;

import br.com.example.inventory.application.port.out.UnitOfWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class SpringTransaction implements UnitOfWork {

    private final TransactionTemplate transaction;

    public SpringTransaction(TransactionTemplate transaction) {
        this.transaction = transaction;
    }

    @Override
    public void execute(Runnable work) {
        transaction.executeWithoutResult(status -> work.run());
    }

    @Override
    public <T> T compute(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
