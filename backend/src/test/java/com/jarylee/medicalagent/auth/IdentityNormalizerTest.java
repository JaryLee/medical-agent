package com.jarylee.medicalagent.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityNormalizerTest {

    @Test
    void canonicalizesHospitalCodesAndUnicodeUsernames() {
        assertThat(IdentityNormalizer.hospitalCode(" hosp_a-01 "))
                .isEqualTo("HOSP_A-01");
        assertThat(IdentityNormalizer.username(" Ａlice "))
                .isEqualTo("Alice");
        assertThat(IdentityNormalizer.usernameLookup(" Ａlice "))
                .isEqualTo("alice");
    }

    @Test
    void rejectsInvalidHospitalCodesAndControlCharacters() {
        assertThatThrownBy(() -> IdentityNormalizer.hospitalCode("医院 A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityNormalizer.username("bad\nname"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
