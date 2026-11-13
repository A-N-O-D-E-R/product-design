package com.anode.product.designer.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrigineFilterTest {

    @Test
    void hotesLocauxSeulement() {
        assertThat(OrigineFilter.hoteLocal("localhost:8080")).isTrue();
        assertThat(OrigineFilter.hoteLocal("127.0.0.1")).isTrue();
        assertThat(OrigineFilter.hoteLocal("[::1]:8080")).isTrue();
        // DNS rebinding : un domaine externe qui résout vers 127.0.0.1.
        assertThat(OrigineFilter.hoteLocal("rebind.evil.example:8080")).isFalse();
        assertThat(OrigineFilter.hoteLocal(null)).isFalse();
    }

    @Test
    void originesLocalesOuAbsentes() {
        assertThat(OrigineFilter.origineLocale(null)).isTrue();
        assertThat(OrigineFilter.origineLocale("http://localhost:5173")).isTrue();
        assertThat(OrigineFilter.origineLocale("null")).isFalse();
        assertThat(OrigineFilter.origineLocale("https://evil.example")).isFalse();
        assertThat(OrigineFilter.origineLocale("http://localhost.evil.example")).isFalse();
    }
}
