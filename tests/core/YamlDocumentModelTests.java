// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.ui.YamlDocumentModel;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Mapping;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Scalar;
import io.github.niwantha33.zew.ui.YamlDocumentModel.Sequence;

/** Headless Java 21 checks against real Step-3 producer observations. */
public final class YamlDocumentModelTests {
    private static final String OBSERVED = """
            {"schema":"zew.yaml-observation/1","status":"parsed","reason":"syntax-observed","classification":{"kind":"devicetree-binding","reason":"binding-directory-candidate"},"provenance":{"source_root":"zephyr","relative_path":"dts/bindings/my.yaml","sha256":"674d7ec84a3096582089e7e6e70e2009ca3d9245d3725455197a80545985e704","size_bytes":58},"documents":[{"root":{"type":"mapping","entries":[{"key":"description","key_range":{"start":{"line":1,"column":1},"end":{"line":1,"column":12}},"value":{"type":"scalar","scalar_type":"str","value":"Test","range":{"start":{"line":1,"column":14},"end":{"line":1,"column":18}}}},{"key":"properties","key_range":{"start":{"line":2,"column":1},"end":{"line":2,"column":11}},"value":{"type":"mapping","entries":[{"key":"pins","key_range":{"start":{"line":3,"column":3},"end":{"line":3,"column":7}},"value":{"type":"sequence","items":[{"type":"scalar","scalar_type":"int","value":"5","range":{"start":{"line":4,"column":7},"end":{"line":4,"column":8}}},{"type":"scalar","scalar_type":"str","value":"six","range":{"start":{"line":5,"column":7},"end":{"line":5,"column":12}}}],"range":{"start":{"line":4,"column":5},"end":{"line":6,"column":1}}}}],"range":{"start":{"line":3,"column":3},"end":{"line":6,"column":1}}}}],"range":{"start":{"line":1,"column":1},"end":{"line":6,"column":1}}}}]}
            """.trim();
    private static final String INVALID = """
            {"schema":"zew.yaml-observation/1","status":"invalid","reason":"yaml-syntax-invalid","classification":{"kind":"generic-yaml","reason":"no-specific-role-established"},"provenance":{"source_root":"application","relative_path":"file.yaml","sha256":"89e081346f761cbb5b12aeae71033afad5f3065c7c07384e50538c9e06c68fcc","size_bytes":4},"documents":[]}
            """.trim();
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS YAML MODEL: " + message);
    }
    private static void rejects(String value) {
        try {
            YamlDocumentModel.parse(value);
            throw new AssertionError("Accepted invalid observation");
        } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains("SECRET"), "bad reports never echo source data");
        }
    }
    public static void main(String[] args) {
        var observation = YamlDocumentModel.parse(OBSERVED);
        check(observation.parsed() && "devicetree-binding".equals(observation.kind()),
                "parsed state and path classification preserved");
        check(observation.sha256().length() == 64 && observation.sizeBytes() == 58,
                "SHA-256 and byte length preserved");
        check(observation.root() instanceof Mapping, "mapping typed node");
        Mapping mapping = (Mapping) observation.root();
        check(mapping.entries().size() == 2 && mapping.entries().get(0).key().equals("description"),
                "ordered mappings and original keys");
        check(mapping.entries().get(0).keyRange().start().line() == 1
                && mapping.entries().get(0).keyRange().start().column() == 1,
                "1-based source locations");
        Mapping nested = (Mapping) mapping.entries().get(1).value();
        Sequence seq = (Sequence) nested.entries().get(0).value();
        check(seq.items().size() == 2 && ((Scalar) seq.items().get(0)).value().equals("5")
                && ((Scalar) seq.items().get(0)).scalarType().equals("int"),
                "sequences and scalars preserve YAML spellings");
        check(((Scalar) seq.items().get(1)).value().equals("six"),
                "quoted scalar decoded by upstream parser");
        try {
            mapping.entries().clear();
            throw new AssertionError("Mutable entries");
        } catch (UnsupportedOperationException expected) {
            check(true, "all collection rows are immutable");
        }
        check(!YamlDocumentModel.parse(INVALID).parsed()
                && YamlDocumentModel.parse(INVALID).root() == null,
                "invalid reports preserve reason without invented nodes");
        // Actual parser can report a successful empty YAML document.
        String noDocs = OBSERVED.substring(0, OBSERVED.indexOf("\"documents\":")) + "\"documents\":[]}";
        check(YamlDocumentModel.parse(noDocs).parsed()
                && YamlDocumentModel.parse(noDocs).root() == null,
                "parsed empty document has no root");
        rejects(null);
        rejects("{}");
        rejects(OBSERVED + " trailing");
        rejects(OBSERVED.substring(0, OBSERVED.length() - 1));
        rejects(OBSERVED.replace("zew.yaml-observation/1", "zew.yaml-observation/2"));
        rejects(OBSERVED.replace("\"status\":\"parsed\"", "\"status\":\"invalid\""));
        rejects(OBSERVED.replace("\"sha256\":\"", "\"sha256\":\"invalid"));
        rejects(OBSERVED.replace("\"line\":1", "\"line\":0"));
        rejects(OBSERVED.replace("\"type\":\"mapping\"", "\"type\":\"unknown\""));
        rejects(OBSERVED.replace("\"scalar_type\":\"int\"", "\"scalar_type\":\"exec\""));
        rejects("{\"schema\":null," + OBSERVED.substring(1));
        rejects("{\"schema\":\"zew.yaml-observation/1\",\"x\":null,\"x\":1}");
        rejects(OBSERVED.replace("\"kind\":\"devicetree-binding\"",
                "\"kind\":\"\\ud800\""));
        rejects(OBSERVED.replace("\"reason\":\"syntax-observed\"", "\"reason\":null"));
        rejects(" ".repeat(4 * 1024 * 1024 + 1));
        check(true, "malformed, duplicate-key, oversized and mismatched JSON rejected");
    }
}
