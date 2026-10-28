package br.com.example.inventory.infrastructure.persistence;

import br.com.example.inventory.application.port.out.ReservationRepository;
import br.com.example.inventory.domain.Reservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JdbcReservationRepository implements ReservationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcReservationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Reservation> byOrder(String orderId) {
        return jdbc.sql("SELECT status, items::text AS items FROM reservation WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, n) -> Reservation.restore(orderId, readItems(rs.getString("items")),
                        Reservation.Status.valueOf(rs.getString("status"))))
                .optional();
    }

    @Override
    public void save(Reservation reservation) {
        jdbc.sql("""
                        INSERT INTO reservation (order_id, status, items) VALUES (:orderId, :status, CAST(:items AS jsonb))
                        ON CONFLICT (order_id) DO UPDATE SET status = EXCLUDED.status, updated_at = now()
                        """)
                .param("orderId", reservation.orderId())
                .param("status", reservation.status().name())
                .param("items", writeItems(reservation.items()))
                .update();
    }

    private List<Reservation.Item> readItems(String items) {
        try {
            return List.of(json.readValue(items, Reservation.Item[].class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeItems(List<Reservation.Item> items) {
        try {
            return json.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
