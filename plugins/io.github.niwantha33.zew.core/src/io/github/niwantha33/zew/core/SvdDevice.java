// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Bounded, offline CMSIS-SVD reader. Unsupported constructs fail explicitly. */
public record SvdDevice(String name, List<Register> registers) {
    public record Field(String name, int offset, int width, String description,
            String access, String readAction) {}
    public record Register(String peripheral, String name, BigInteger address, int size,
            BigInteger reset, BigInteger resetMask, String access, String readAction,
            String description, List<Field> fields) {
        public boolean readable() { return readBlockReason().isEmpty(); }
        /** Conservatively block a full-width read if any included field is unsafe to read. */
        public String readBlockReason() {
            if (!readAccess(access)) return "register access: " + access;
            if (!readAction.isEmpty()) return "register readAction: " + readAction;
            for (var field : fields) {
                if (!readAccess(field.access())) return "field " + field.name() + " access: " + field.access();
                if (!field.readAction().isEmpty()) return "field " + field.name() + " readAction: " + field.readAction();
            }
            return "";
        }
        public String key() { return peripheral + "." + name; }
    }
    public static SvdDevice read(InputStream input) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var device = factory.newDocumentBuilder().parse(input).getDocumentElement();
        if (!device.getTagName().equals("device")) throw new IllegalArgumentException("Expected CMSIS-SVD device");
        if (!text(device, "addressUnitBits", "8").equals("8"))
            throw new IllegalArgumentException("Only byte-addressed SVD devices are supported");
        var result = new ArrayList<Register>();
        var peripherals = child(device, "peripherals");
        if (peripherals == null) throw new IllegalArgumentException("SVD contains no peripherals");
        int[] budget = {0};
        for (var raw : children(peripherals)) {
            if (!raw.getTagName().equals("peripheral")) continue;
            var p = resolve(raw, peripherals, new ArrayList<>());
            if (child(p, "dim") != null) throw new IllegalArgumentException("Peripheral arrays are not yet supported");
            var registers = child(p, "registers");
            if (registers != null) walk(registers, text(p, "name", ""), "", number(text(p, "baseAddress", "")),
                    p, device, result, 0, budget);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("SVD contains no registers");
        var keys = new java.util.HashSet<String>();
        for (var register : result) if (!keys.add(register.key()))
            throw new IllegalArgumentException("Duplicate register: " + register.key());
        return new SvdDevice(text(device, "name", "Device"), List.copyOf(result));
    }
    private static void walk(Element container, String peripheral, String prefix, BigInteger base,
            Element parent, Element device, List<Register> out, int depth, int[] budget) {
        if (depth > 16) throw new IllegalArgumentException("SVD nesting limit exceeded");
        for (var raw : children(container)) {
            if (!List.of("register", "cluster").contains(raw.getTagName())) continue;
            var r = resolve(raw, container, new ArrayList<>());
            int count = number(text(r, "dim", "1")).intValueExact();
            if (count < 1 || count > 4096) throw new IllegalArgumentException("Invalid SVD array size");
            var indices = indices(text(r, "dimIndex", ""), count);
            var step = number(text(r, "dimIncrement", "0"));
            for (int i = 0; i < count; i++) {
                if (++budget[0] > 100000 || out.size() >= 100000) throw new IllegalArgumentException("SVD register limit exceeded");
                String name = prefix + text(r, "name", "").replace("%s", indices.get(i));
                if (count > 1 && !text(r, "name", "").contains("%s")) name += indices.get(i);
                var address = base.add(number(text(r, "addressOffset", ""))).add(step.multiply(BigInteger.valueOf(i)));
                if (address.signum() < 0 || address.bitLength() > 64) throw new IllegalArgumentException("Invalid register address");
                if (r.getTagName().equals("cluster")) {
                    // Resolve inherited register properties at each cluster boundary.
                    var properties = (Element) r.cloneNode(true);
                    for (String property : List.of("size", "access", "resetValue", "resetMask", "readAction"))
                        if (child(properties, property) == null && child(parent, property) != null)
                            properties.appendChild(child(parent, property).cloneNode(true));
                    walk(r, peripheral, name + ".", address, properties, device, out, depth + 1, budget);
                    continue;
                }
                int size = number(inherit(r, parent, device, "size", "32")).intValueExact();
                if (!List.of(8, 16, 32).contains(size)) throw new IllegalArgumentException("Unsupported register width: " + size);
                if (!address.mod(BigInteger.valueOf(size / 8)).equals(BigInteger.ZERO))
                    throw new IllegalArgumentException("Unaligned register: " + name);
                String access = inherit(r, parent, device, "access", "read-write");
                String action = inherit(r, parent, device, "readAction", "");
                var fields = new ArrayList<Field>();
                var fs = child(r, "fields");
                if (fs != null) for (var f : children(fs)) {
                    if (!f.getTagName().equals("field")) continue;
                    f = resolve(f, fs, new ArrayList<>());
                    if (child(f, "dim") != null)
                        throw new IllegalArgumentException("Array fields are not yet supported: " + name);
                    int offset, width;
                    if (child(f, "bitRange") != null) {
                        String[] range = text(f, "bitRange", "").replace("[", "").replace("]", "").split(":");
                        offset = Integer.parseInt(range[1]); width = Integer.parseInt(range[0]) - offset + 1;
                    } else if (child(f, "lsb") != null) {
                        offset = Integer.parseInt(text(f, "lsb", ""));
                        width = Integer.parseInt(text(f, "msb", "")) - offset + 1;
                    } else {
                        offset = Integer.parseInt(text(f, "bitOffset", "")); width = Integer.parseInt(text(f, "bitWidth", ""));
                    }
                    if (offset < 0 || width < 1 || offset + width > size) throw new IllegalArgumentException("Invalid field: " + name);
                    fields.add(new Field(text(f, "name", ""), offset, width, text(f, "description", ""),
                            text(f, "access", access), text(f, "readAction", "")));
                }
                String resetText = inherit(r, parent, device, "resetValue", "");
                BigInteger reset = resetText.isEmpty() ? null : number(resetText);
                BigInteger mask = number(inherit(r, parent, device, "resetMask", BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE).toString()));
                if ((reset != null && (reset.signum() < 0 || reset.bitLength() > size)) || mask.signum() < 0 || mask.bitLength() > size)
                    throw new IllegalArgumentException("Invalid reset value/mask: " + name);
                out.add(new Register(peripheral, name, address, size, reset, mask, access, action,
                        text(r, "description", ""), List.copyOf(fields)));
            }
        }
    }
    private static boolean readAccess(String access) {
        // Unknown/vendor-specific access modes cannot authorise an MMIO read.
        return List.of("read-only", "read-write", "read-writeOnce").contains(access);
    }
    private static List<String> indices(String value, int count) {
        var result = new ArrayList<String>();
        if (value.isEmpty()) for (int i = 0; i < count; i++) result.add(Integer.toString(i));
        else if (value.matches("[0-9]+-[0-9]+")) {
            String[] bounds = value.split("-"); int first = Integer.parseInt(bounds[0]);
            int last = Integer.parseInt(bounds[1]);
            if ((long)last - first + 1 != count) throw new IllegalArgumentException("Invalid dimIndex range");
            for (int i = 0; i < count; i++) result.add(Integer.toString(first + i));
        } else for (String index : value.split(",")) result.add(index.trim());
        if (result.size() != count) throw new IllegalArgumentException("Invalid dimIndex count");
        return result;
    }
    private static Element resolve(Element source, Element scope, List<String> chain) {
        if (!source.hasAttribute("derivedFrom")) return source;
        String name = source.getAttribute("derivedFrom");
        if (chain.contains(name) || chain.size() > 16) throw new IllegalArgumentException("Cyclic SVD inheritance");
        chain.add(name);
        Element base = null;
        for (var candidate : children(scope)) if (candidate.getTagName().equals(source.getTagName())
                && text(candidate, "name", "").equals(name)) base = candidate;
        if (base == null) throw new IllegalArgumentException("Unresolved SVD derivedFrom: " + name);
        var merged = (Element) resolve(base, scope, chain).cloneNode(true);
        merged.removeAttribute("derivedFrom");
        for (var override : children(source)) {
            var old = child(merged, override.getTagName());
            if (old != null) merged.removeChild(old);
            merged.appendChild(override.cloneNode(true));
        }
        return merged;
    }
    private static String inherit(Element r, Element p, Element d, String key, String fallback) {
        return text(r, key, text(p, key, text(d, key, fallback)));
    }
    private static BigInteger number(String value) {
        String s = value.trim().replace("_", "");
        if (s.startsWith("0x") || s.startsWith("0X")) return new BigInteger(s.substring(2), 16);
        if (s.startsWith("#")) return new BigInteger(s.substring(1), 2);
        return new BigInteger(s);
    }
    private static Element child(Element e, String name) {
        for (var c : children(e)) if (c.getTagName().equals(name)) return c;
        return null;
    }
    private static String text(Element e, String name, String fallback) {
        var c = child(e, name); return c == null ? fallback : c.getTextContent().trim();
    }
    private static List<Element> children(Element e) {
        var result = new ArrayList<Element>();
        for (var n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c) result.add(c);
        return result;
    }
}
