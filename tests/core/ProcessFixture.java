// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Path;
import java.util.Locale;

public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "echo" -> { for (int i = 1; i < args.length; i++) System.out.println(args[i]); }
            case "fail" -> { System.err.println("intentional error"); System.exit(7); }
            case "flood" -> { for (int i=0; i<10000; i++) { System.out.println("line-" + i); System.err.println("error-" + i); } }
            case "capture" -> { for (int i=0; i<50000; i++) System.out.println("012345678901234567890123456789012345678901234567890123456789"); }
            case "sleep" -> Thread.sleep(60_000);
            case "child" -> {
                String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
                var child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "ProcessFixture", "sleep").start();
                System.out.println("CHILD=" + child.pid());
                child.waitFor();
            }
            default -> throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}
