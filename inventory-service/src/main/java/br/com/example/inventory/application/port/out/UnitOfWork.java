package br.com.example.inventory.application.port.out;

import java.util.function.Supplier;

public interface UnitOfWork {

    void execute(Runnable work);

    <T> T compute(Supplier<T> work);
}
