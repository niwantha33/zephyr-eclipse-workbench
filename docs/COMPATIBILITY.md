# Hardware research and compatibility

Research date: 2026-10-23. Baseline: Zephyr v4.4.0, matching the user's current installation.
This is a repository/source review, not a completed build or hardware audit of these families.

## Sources and meaning

The organisation repository list alone is not a support catalogue. Combine:

- [Zephyr SoC definitions](https://github.com/zephyrproject-rtos/zephyr/tree/v4.4.0/soc)
- [Board definitions and documentation](https://github.com/zephyrproject-rtos/zephyr/tree/v4.4.0/boards)
- [Pinned upstream manifest](https://github.com/zephyrproject-rtos/zephyr/blob/v4.4.0/west.yml)
- [Supported board catalogue](https://docs.zephyrproject.org/latest/boards/index.html) (latest may differ from v4.4.0)
- Generated configuration and runner metadata for the selected build.

A HAL repository, SoC directory, or chip hardware feature does not prove a working board port, peripheral driver, radio stack, flash workflow, or debug workflow.

## Priority vendor findings

| Vendor | Source evidence in v4.4.0 | Integration considerations |
|---|---|---|
| TI | soc/ti contains k3, lm3s6965, mspm0, simplelink. SimpleLink contains cc13x2_cc26x2, cc13x2x7_cc26x2x7, cc23x0, cc32xx and msp432p4xx | Per-board probe/runner requirements; no MSP432E401Y found in reviewed TI SoC/board directories |
| ST | soc/st/stm32 contains F/G/H/L/U/WB/WL and other series | Exact part and board selection, overlays, revision and multi-core differences |
| Espressif | esp32, esp32s2, esp32s3, esp32c2/c3/c5/c6 and esp32h2 directories | Xtensa or RISC-V compiler, blobs, images, serial transport and architecture-specific debugging |
| Nordic | nrf51/52/53, nrf54h/l, nrf71, nrf91/92 directories | Selected part/core and upstream feature coverage must be checked; multi-image and vendor programming tools |
| Realtek | ameba (amebad, amebadplus, amebag2), bee (rtl8752h, rtl87x2g), ec and fingerprint | Blobs, image layouts, and board-specific programming procedures |
| Additional vendors | SoC directories include nxp, renesas, microchip, silabs, infineon, raspberrypi, adi, ambiq, nuvoton and others | Discover through the common mechanism, then validate selected targets |

TI MSP432P401R and MSP432E401Y are different devices. Do not advertise support for MSP432E401Y based on the MSP432P4 port. A missing upstream port would be a separate board/SoC/driver project.

## Representative boards

| Board | Evidence reviewed | Intended role |
|---|---|---|
| esp32s3_devkitc | board.yml identifies esp32s3 | Initial Xtensa target; user reports successful command-line procpu build |
| nucleo_f446re | board.yml identifies stm32f446xx | Proposed STM32 validation target |
| nrf52840dk | board.yml includes nrf52840 and nrf52811 emulation target | Proposed Nordic target; explicitly choose correct SoC |
| lp_mspm0g3507 | board.yml identifies mspm0g3507 | Proposed TI target |
| msp_exp432p401r_launchxl | Documentation describes XDS110/OpenOCD | Alternative TI target |
| rtl872xd_evb | board.yml, board.cmake and documentation | Proposed Realtek target; reconcile image-tool and runner behaviour during implementation |

Realtek RTL872xD documentation describes AmebaImageTool programming; its board.cmake includes a J-Link runner. Neither source alone proves a complete automated flash/debug workflow. Resolve this through build metadata, vendor documentation, and physical testing.

## Evidence levels

Track each capability independently:

| Capability | Evidence required |
|---|---|
| Discovered | Present in selected checkout with exact board target/revision/qualifiers |
| Build verified | Successful build log with commit, app, config, SDK and host |
| Flash verified | Physical board/probe/connection and successful programming log |
| Run verified | Observable expected behaviour or serial output |
| Debug verified | Breakpoint, step, variables and connection evidence |
| Peripheral verified | Specific test and wiring for each claimed peripheral |

Current plug-in validation: none.
Current external baseline: user-reported Zephyr v4.4.0 Hello World compilation for esp32s3_devkitc/esp32s3/procpu on Windows with West 1.5.0. Flash/run/debug unverified.

New hardware found by discovery must remain labelled discovered until further evidence exists.
