package com.piotrcapecki.openclaw.skill.career.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class StringListConverterTest {

    private final StringListConverter converter = new StringListConverter();

    @Test
    void convertsToDatabaseColumn() {
        String result = converter.convertToDatabaseColumn(List.of("Java", "Spring Boot", "SQL"));
        assertThat(result).isEqualTo("Java,Spring Boot,SQL");
    }

    @Test
    void convertsNullToDatabaseColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertsToEntityAttribute() {
        List<String> result = converter.convertToEntityAttribute("Java,Spring Boot,SQL");
        assertThat(result).containsExactly("Java", "Spring Boot", "SQL");
    }

    @Test
    void convertsNullToEmptyList() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }

    @Test
    void convertsBlankStringToEmptyList() {
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }
}
