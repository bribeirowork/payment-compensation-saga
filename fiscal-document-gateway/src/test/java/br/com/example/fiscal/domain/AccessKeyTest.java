package br.com.example.fiscal.domain;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessKeyTest {

    @Test
    void generatesKeyWith44DigitsAndValidCheckDigit() {
        var key = AccessKey.generate("35", YearMonth.of(2026, 10), "12345678000195", 1, 42);

        assertThat(key.value()).hasSize(44).startsWith("352610" + "12345678000195" + "55" + "001" + "000000042");
        assertThat(new AccessKey(key.value())).isEqualTo(key);
    }

    @Test
    void sameNumberAlwaysGeneratesSameKey() {
        var a = AccessKey.generate("35", YearMonth.of(2026, 10), "12345678000195", 1, 7);
        var b = AccessKey.generate("35", YearMonth.of(2026, 10), "12345678000195", 1, 7);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void rejectsWrongCheckDigit() {
        var valid = AccessKey.generate("35", YearMonth.of(2026, 10), "12345678000195", 1, 7).value();
        var wrongCheckDigit = (valid.charAt(43) - '0' + 1) % 10;
        assertThatThrownBy(() -> new AccessKey(valid.substring(0, 43) + wrongCheckDigit))
                .isInstanceOf(DomainException.class);
    }
}
