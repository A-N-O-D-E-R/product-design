package com.anode.productdesigner.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HoteLocalTest {

    @Test
    void hotesLocauxSeulement() {
        assertThat(HoteLocalFilter.hoteLocal("localhost:8080")).isTrue();
        assertThat(HoteLocalFilter.hoteLocal("127.0.0.1")).isTrue();
        assertThat(HoteLocalFilter.hoteLocal("[::1]:8080")).isTrue();
        // DNS rebinding : un domaine externe qui résout vers 127.0.0.1.
        assertThat(HoteLocalFilter.hoteLocal("rebind.evil.example:8080")).isFalse();
        assertThat(HoteLocalFilter.hoteLocal(null)).isFalse();
    }
}
