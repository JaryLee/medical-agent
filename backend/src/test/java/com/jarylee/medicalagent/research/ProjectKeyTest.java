package com.jarylee.medicalagent.research;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectKeyTest {

    @Test
    void generatesUniqueCrockfordKeysWithAtLeast128BitsOfEntropy() {
        var keys = IntStream.range(0, 10_000)
                .mapToObj(ignored -> ProjectKey.generate())
                .toList();

        assertThat(keys).allMatch(ProjectKey::isValid);
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void rejectsAmbiguousLowercaseAndMalformedValues() {
        assertThat(ProjectKey.isValid("prj_0123456789ABCDEFGHJKMNPQRS")).isTrue();
        assertThat(ProjectKey.isValid("PRJ_0123456789ABCDEFGHJKMNPQRS")).isFalse();
        assertThat(ProjectKey.isValid("prj_0123456789ABCDEFGHILMNOPQRS")).isFalse();
        assertThat(ProjectKey.isValid("prj_short")).isFalse();
    }
}
