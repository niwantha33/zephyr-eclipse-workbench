# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
import os
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
TOOLS=ROOT/'plugins/io.github.niwantha33.zew.ui/tools'
spec=importlib.util.spec_from_file_location('target_metadata',TOOLS/'target_metadata.py')
metadata=importlib.util.module_from_spec(spec);spec.loader.exec_module(metadata)

class TargetTests(unittest.TestCase):
    def test_specific_soc_hal_wins_over_vendor_umbrella(self):
        info={'vendor':'st','type':'mcu','arch':'arm','folders':[Path('/z/soc/st/stm32/stm32f4x')],'source_text':''}
        self.assertEqual(metadata.hal_modules(info,{'hal_st':None,'hal_stm32':None,'hal_ti':None}),['hal_stm32'])

    def test_simulator_does_not_download_chip_vendor_hal(self):
        info={'vendor':'intel','type':'qemu','arch':'x86','folders':[Path('/z/soc/intel/atom')],'source_text':''}
        self.assertEqual(metadata.hal_modules(info,{'hal_intel':None}),[])

    def test_explicit_and_architecture_module_requirements_are_kept(self):
        info={'vendor':'vendor','type':'mcu','arch':'xtensa','folders':[], 'source_text':'${ZEPHYR_LIB_TEST_MODULE_DIR}'}
        self.assertEqual(metadata.hal_modules(info,{'hal_xtensa':None,'lib-test':None}),['lib-test','hal_xtensa'])

    @unittest.skipUnless(os.environ.get('ZEW_TEST_ZEPHYR'),'upstream checkout required')
    def test_real_cross_vendor_and_mixed_core_matrix(self):
        root=Path(os.environ['ZEW_TEST_ZEPHYR']).resolve()
        assets=json.loads((TOOLS/'sdk-toolchains-windows-1.0.1.json').read_text())['assets']
        cases=[('lp_mspm0g3507/mspm0g3507','arm-zephyr-eabi'),('nucleo_f446re/stm32f446xx','arm-zephyr-eabi'),('nrf52840dk/nrf52840','arm-zephyr-eabi'),('esp32s3_devkitc/esp32s3/procpu','xtensa-espressif_esp32s3_zephyr-elf'),('esp32s3_devkitc/esp32s3/appcpu','xtensa-espressif_esp32s3_zephyr-elf'),('esp32c3_devkitm/esp32c3','riscv64-zephyr-elf'),('frdm_k64f/mk64f12','arm-zephyr-eabi'),('ek_ra6m4/r7fa6m4af3cfb','arm-zephyr-eabi'),('rts5817_maa_evb/rts5817','arm-zephyr-eabi'),('qemu_x86/atom','x86_64-zephyr-elf'),('qemu_arc/qemu_arc_hs','arc-zephyr-elf'),('qemu_arc/qemu_arc_hs5x','arc64-zephyr-elf'),('qemu_arc/qemu_arc_hs6x','arc64-zephyr-elf'),('qemu_cortex_a53/qemu_cortex_a53','aarch64-zephyr-elf'),('nrf54l15dk/nrf54l15/cpuapp','arm-zephyr-eabi'),('nrf54l15dk/nrf54l15/cpuflpr','riscv64-zephyr-elf')]
        for target,compiler in cases:
            with self.subTest(target=target):
                info=metadata.describe(root,target)
                self.assertEqual(metadata.compiler(info,assets),compiler)
        self.assertRaises(ValueError,metadata.describe,root,'esp32s3_devkitc/esp32s3/invalid_cpu')
