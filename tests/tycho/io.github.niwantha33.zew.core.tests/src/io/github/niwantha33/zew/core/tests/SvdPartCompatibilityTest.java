// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import io.github.niwantha33.zew.core.SvdPartCompatibility;

/** Protect exact-chip matching from unsafe family/wildcard assumptions. */
public final class SvdPartCompatibilityTest {
    @Test
    public void exactModelsIgnoreSeparatorsAndCase() {
        assertTrue(SvdPartCompatibility.matches("STM32-F446RE", "stm32_f446re"));
        assertTrue(SvdPartCompatibility.matches("mspm0g3507", "MSPM0G3507"));
    }

    @Test
    public void allowsOnlyTheDocumentedTiSharedSvd() {
        assertTrue(SvdPartCompatibility.matches("MSPM0G3507", "MSPM0G350X"));
        assertFalse(SvdPartCompatibility.matches("MSPM0G3506", "MSPM0G350X"));
        assertFalse(SvdPartCompatibility.matches("MSPM0G3508", "MSPM0G350X"));
    }

    @Test
    public void rejectsVendorMixupsAndUnapprovedWildcards() {
        assertFalse(SvdPartCompatibility.matches("esp32s3", "MSPM0G350X"));
        assertFalse(SvdPartCompatibility.matches("stm32f446re", "STM32F446XX"));
        assertFalse(SvdPartCompatibility.matches("nrf52840", "NRF52*"));
    }

    @Test
    public void rejectsMissingIdentities() {
        assertFalse(SvdPartCompatibility.matches(null, "MSPM0G350X"));
        assertFalse(SvdPartCompatibility.matches("mspm0g3507", null));
        assertFalse(SvdPartCompatibility.matches("  ", "mspm0g3507"));
        assertFalse(SvdPartCompatibility.matches("mspm0g3507", ""));
    }
}
