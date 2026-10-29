// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.cdt.utils.elf.Elf;
import io.github.niwantha33.zew.core.OpenOcdThreadCheck.Region;

/** Extract only static, allocated thread-description bytes from the exact launch ELF. */
public final class OpenOcdThreadMetadata {
    private OpenOcdThreadMetadata() {}
    public static List<Region> read(Path file) throws IOException {
        try (var elf = new Elf(file.toString())) {
            elf.loadSymbols();
            var symbols = elf.getSymbols();
            var sections = elf.getSections();
            if (symbols == null || Arrays.stream(symbols).noneMatch(s -> s.toString().equals("_kernel") && !s.st_value.isZero()))
                throw new IOException("ELF has no Zephyr kernel symbols; rebuild with CONFIG_DEBUG_THREAD_INFO=y.");
            var order = elf.getELFhdr().e_ident[Elf.ELFhdr.EI_DATA] == Elf.ELFhdr.ELFDATA2LSB
                    ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            var result = new ArrayList<Region>();
            for (String suffix : List.of("size_t_size", "num_offsets", "offsets")) {
                var symbol = Arrays.stream(symbols).filter(s -> s.toString().equals("_kernel_thread_info_" + suffix))
                        .findFirst().orElseGet(() -> Arrays.stream(symbols)
                                .filter(s -> s.toString().equals("_kernel_openocd_" + suffix)).findFirst().orElse(null));
                if (symbol == null) throw new IOException("ELF lacks Zephyr thread metadata: " + suffix + ". Rebuild with CONFIG_DEBUG_THREAD_INFO=y.");
                int sectionIndex = Short.toUnsignedInt(symbol.st_shndx);
                if (sectionIndex == 0 || sectionIndex >= sections.length || symbol.st_size < 1 || symbol.st_size > 256)
                    throw new IOException("Invalid ELF thread symbol: " + symbol);
                var section = sections[sectionIndex];
                if (section.sh_type != Elf.Section.SHT_PROGBITS || (section.sh_flags & Elf.Section.SHF_ALLOC) == 0
                        || (section.sh_flags & Elf.Section.SHF_WRITE) != 0 || section.sh_size > 1024 * 1024)
                    throw new IOException("Thread metadata is not static allocated ELF data: " + symbol);
                long offset = symbol.st_value.getValue().subtract(section.sh_addr.getValue()).longValueExact();
                if (offset < 0 || offset > section.sh_size - symbol.st_size) throw new IOException("ELF thread symbol lies outside its section");
                byte[] data = section.loadSectionData();
                var bytes = new ArrayList<Integer>();
                for (int i = 0; i < symbol.st_size; i++) bytes.add(Byte.toUnsignedInt(data[(int) offset + i]));
                result.add(new Region(symbol.toString(), symbol.st_value.getValue().longValueExact(), bytes));
            }
            if (!result.get(0).bytes().equals(List.of(4))) throw new IOException("This OpenOCD Zephyr decoder requires 4-byte size_t; ELF declares " + result.get(0).bytes());
            if (result.get(1).bytes().size() != 4) throw new IOException("Invalid Zephyr thread offset count width");
            byte[] countBytes = new byte[4];
            for (int i = 0; i < 4; i++) countBytes[i] = (byte) result.get(1).bytes().get(i).intValue();
            int count = ByteBuffer.wrap(countBytes).order(order).getInt();
            if (count < 9 || count > 64 || result.get(2).bytes().size() != count * 4)
                throw new IOException("Inconsistent Zephyr thread offset table in ELF");
            return List.copyOf(result);
        } catch (ArithmeticException | IllegalArgumentException malformed) {
            throw new IOException("Invalid ELF thread metadata", malformed);
        }
    }
}
