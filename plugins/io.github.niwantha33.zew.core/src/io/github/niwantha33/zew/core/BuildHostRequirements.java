// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Inspect executable Ninja bindings, including referenced rule files and variables. */
public final class BuildHostRequirements {
    private static final long MAX_NINJA_BYTES = 32L * 1024 * 1024;
    private static final Pattern MISSING=Pattern.compile("(?<![A-Za-z0-9_])([A-Z][A-Z0-9_]*)-NOTFOUND(?![A-Za-z0-9_])");
    private static final Pattern ASSIGN=Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");
    private static final Pattern VARIABLE=Pattern.compile("(?<!\\$)\\$(?:\\{([A-Za-z_][A-Za-z0-9_]*)}|([A-Za-z_][A-Za-z0-9_]*))");
    private BuildHostRequirements(){}
    /** Ninja -t commands traverses the default build graph without executing it. */
    public static Set<String> missingInCommands(String commands) throws IOException {
        if (commands.length() > MAX_NINJA_BYTES) throw new IOException("Generated commands exceed inspection limit.");
        var missing = new LinkedHashSet<String>();
        var matcher = MISSING.matcher(commands.toUpperCase(java.util.Locale.ROOT));
        while (matcher.find()) missing.add(matcher.group(1).toUpperCase(java.util.Locale.ROOT));
        return Set.copyOf(missing);
    }

    public static Set<String> missingInGeneratedBuild(Path directory)throws IOException{
        Path root=directory.toRealPath();var files=new ArrayDeque<Path>();files.add(root.resolve("build.ninja"));
        var visited=new HashSet<Path>();var bindings=new HashMap<String,List<String>>();var commands=new ArrayDeque<String>();long bytes=0;
        while(!files.isEmpty()){
            Path file=files.removeFirst();
            if(!Files.isRegularFile(file))throw new IOException("Configure-only preflight is missing generated Ninja file: "+file);
            file=file.toRealPath();if(!file.startsWith(root))throw new IOException("Generated Ninja include leaves the independent build directory: "+file);
            if(!visited.add(file))continue;
            if(visited.size()>128||(bytes+=Files.size(file))>32L*1024*1024)throw new IOException("Generated Ninja metadata exceeds preflight inspection limit");
            String text=Files.readString(file,StandardCharsets.UTF_8).replaceAll("\\$\\r?\\n[ \\t]*", "");
            for(String line:text.lines().toList()){
                if(line.stripLeading().startsWith("#"))continue;
                String trimmed=line.trim();
                if(trimmed.startsWith("include ")||trimmed.startsWith("subninja ")){
                    String path=trimmed.substring(trimmed.indexOf(' ')+1).trim().replace("$ "," ").replace("$:",":").replace("$$","$");
                    if(path.contains("$"))throw new IOException("Unresolved variable in generated Ninja include: "+path);
                    files.add(root.resolve(path).normalize());continue;
                }
                var assignment=ASSIGN.matcher(line);if(!assignment.matches())continue;
                String name=assignment.group(1),value=assignment.group(2);bindings.computeIfAbsent(name,k->new ArrayList<>()).add(value);
                if(name.equalsIgnoreCase("command"))commands.add(value);
            }
        }
        var missing=new LinkedHashSet<String>();var expanded=new HashSet<String>();
        while(!commands.isEmpty()){
            String value=commands.removeFirst();var matcher=MISSING.matcher(value);while(matcher.find())missing.add(matcher.group(1));
            var variables=VARIABLE.matcher(value);while(variables.find()){
                String name=variables.group(1)!=null?variables.group(1):variables.group(2);
                if(expanded.add(name))commands.addAll(bindings.getOrDefault(name,List.of()));
            }
        }
        return Set.copyOf(missing);
    }
}
