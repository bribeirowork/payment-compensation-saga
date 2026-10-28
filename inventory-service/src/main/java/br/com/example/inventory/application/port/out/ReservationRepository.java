package br.com.example.inventory.application.port.out;

import br.com.example.inventory.domain.Reservation;

import java.util.Optional;

public interface ReservationRepository {

    Optional<Reservation> byOrder(String orderId);

    void save(Reservation reservation);
}
