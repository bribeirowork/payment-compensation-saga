package br.com.example.fiscal.infrastructure.persistence;

import br.com.example.fiscal.application.TransientFailureException;
import br.com.example.fiscal.application.port.out.InvoiceRepository;
import br.com.example.fiscal.domain.Invoice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JdbcInvoiceRepository implements InvoiceRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcInvoiceRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<Invoice> byOrder(String orderId) {
        return jdbc.sql("SELECT data::text AS data, version FROM invoice WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query((rs, n) -> deserialize(rs.getString("data"), rs.getInt("version")))
                .optional();
    }

    @Override
    public long nextNumber() {
        return jdbc.sql("SELECT nextval('invoice_number_seq')").query(Long.class).single();
    }

    @Override
    public void insert(Invoice invoice) {
        jdbc.sql("""
                        INSERT INTO invoice (id, order_id, number, access_key, status, version, data)
                        VALUES (:id, :orderId, :number, :key, :status, 0, CAST(:data AS jsonb))
                        """)
                .param("id", invoice.id())
                .param("orderId", invoice.orderId())
                .param("number", invoice.number())
                .param("key", invoice.accessKey().value())
                .param("status", invoice.status().name())
                .param("data", serialize(invoice))
                .update();
    }

    @Override
    public void update(Invoice invoice) {
        int rows = jdbc.sql("""
                        UPDATE invoice SET status = :status, data = CAST(:data AS jsonb),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("id", invoice.id())
                .param("status", invoice.status().name())
                .param("data", serialize(invoice))
                .param("version", invoice.version())
                .update();
        if (rows == 0) {
            throw new TransientFailureException("NF-e " + invoice.accessKey() + " alterada por outra transação");
        }
    }

    private String serialize(Invoice invoice) {
        try {
            return json.writeValueAsString(invoice.data());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Invoice deserialize(String data, int version) {
        try {
            var d = json.readValue(data, Invoice.Data.class);
            return Invoice.restore(new Invoice.Data(d.id(), d.orderId(), d.series(), d.number(),
                    d.accessKey(), d.recipient(), d.items(), d.totalAmount(), d.status(), d.protocol(),
                    d.authorizedAt(), d.rejectionCode(), d.rejectionReason(), d.events(), version));
        } catch (Exception e) {
            throw new IllegalStateException("NF-e ilegível", e);
        }
    }
}
