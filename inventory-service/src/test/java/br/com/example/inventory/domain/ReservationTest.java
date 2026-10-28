package br.com.example.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    @Test
    void reservesAllOrNothing() {
        var tshirt = new StockItem("SKU-TSHIRT", 10);
        var soldOut = new StockItem("SKU-SOLD-OUT", 0);
        var balance = Map.of(tshirt.sku(), tshirt, soldOut.sku(), soldOut);

        assertThatThrownBy(() -> Reservation.place("P1",
                List.of(new Reservation.Item("SKU-TSHIRT", 2), new Reservation.Item("SKU-SOLD-OUT", 1)), balance))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(tshirt.available()).isEqualTo(10);
    }

    @Test
    void releasingTwiceDoesNotRestockTwice() {
        var tshirt = new StockItem("SKU-TSHIRT", 10);
        var balance = Map.of(tshirt.sku(), tshirt);
        var reservation = Reservation.place("P1", List.of(new Reservation.Item("SKU-TSHIRT", 3)), balance);
        assertThat(tshirt.available()).isEqualTo(7);

        assertThat(reservation.release(balance)).isTrue();
        assertThat(reservation.release(balance)).isFalse();
        assertThat(tshirt.available()).isEqualTo(10);
    }
}
