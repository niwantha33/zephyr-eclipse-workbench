// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.IField;
import org.eclipse.cdt.core.model.IFunctionDeclaration;
import org.eclipse.cdt.core.model.IParent;
import org.eclipse.cdt.core.model.ISourceReference;
import org.eclipse.cdt.core.model.IStructure;
import org.eclipse.cdt.core.model.ITranslationUnit;
import org.eclipse.core.resources.IFile;

/**
 * Read-only CDT model adapter: no regex parsing, build invocation, source
 * mutation, speculative associations or whole-project dependency claims.
 */
public final class UmlCdtSource {
    private static final int MAX_VISITED = 4000;
    private static final int MAX_DEPTH = 12;

    private UmlCdtSource() {}

    public static UmlClassModel.Snapshot inspect(IFile file) {
        if (file == null || !file.isAccessible())
            return UmlClassModel.unavailable("", "Select an accessible C/C++ source or header.");
        String source = file.getProjectRelativePath().toString();
        try {
            ICElement element = CoreModel.getDefault().create(file);
            if (!(element instanceof ITranslationUnit unit))
                return UmlClassModel.unavailable(source,
                        "The selected file has no CDT translation unit. Check project C/C++ indexing.");
            if (!unit.isStructureKnown())
                return UmlClassModel.unavailable(source, "CDT cannot currently resolve this file's outline.");
            var types = new ArrayList<UmlClassModel.Type>();
            int[] visited = {0};
            collect(unit, "", types, 0, visited);
            String note = visited[0] >= MAX_VISITED
                    ? "CDT outline traversal limit reached; this is an incomplete diagram."
                    : "CDT source outline only (not runtime state, complete inheritance or call graph).";
            if (types.isEmpty())
                return UmlClassModel.unavailable(source,
                        "No class/struct/union definitions were found in the selected CDT source outline.");
            return UmlClassModel.of(source, types, note);
        } catch (CModelException | RuntimeException failed) {
            return UmlClassModel.unavailable(source, "CDT source outline unavailable: "
                    + failed.getClass().getSimpleName());
        }
    }

    private static void collect(IParent parent, String prefix,
                                List<UmlClassModel.Type> result, int depth,
                                int[] visited) throws CModelException {
        if (depth > MAX_DEPTH || result.size() > UmlClassModel.MAX_TYPES
                || visited[0] >= MAX_VISITED) return;
        for (ICElement child : parent.getChildren()) {
            if (++visited[0] > MAX_VISITED) return;
            if (child instanceof IStructure structure) {
                String raw = structure.getElementName();
                int offset = offset(structure);
                String name = raw == null || raw.isBlank()
                        ? "(anonymous at offset " + offset + ")" : raw;
                name = prefix + name;
                String kind = structure.isClass() ? "class" :
                        structure.isUnion() ? "union" : "struct";
                List<String> fields = new ArrayList<>();
                for (IField field : structure.getFields()) {
                    if (fields.size() >= UmlClassModel.MAX_MEMBERS - 1) {
                        fields.add("... additional fields omitted");
                        break;
                    }
                    fields.add(field.getElementName() + ": " + field.getTypeName());
                }
                List<String> methods = new ArrayList<>();
                for (var method : structure.getMethods()) {
                    if (methods.size() >= UmlClassModel.MAX_MEMBERS - 1) {
                        methods.add("... additional methods omitted");
                        break;
                    }
                    methods.add(signature(method));
                }
                result.add(new UmlClassModel.Type(kind, name, offset, fields, methods));
                collect(structure, name + "::", result, depth + 1, visited);
            } else if (child instanceof IParent nested) {
                String scope = child.getElementType() == ICElement.C_NAMESPACE
                        ? prefix + child.getElementName() + "::" : prefix;
                collect(nested, scope, result, depth + 1, visited);
            }
            if (result.size() > UmlClassModel.MAX_TYPES || visited[0] >= MAX_VISITED) break;
        }
    }

    private static String signature(IFunctionDeclaration method) throws CModelException {
        String[] parameters = method.getParameterTypes();
        String returns = method.getReturnType();
        String suffix = returns == null || returns.isBlank() ? "" : ": " + returns;
        return method.getElementName() + "(" + String.join(", ", parameters) + ")" + suffix;
    }

    private static int offset(ISourceReference source) throws CModelException {
        var range = source.getSourceRange();
        return Math.max(0, range.getIdStartPos() >= 0
                ? range.getIdStartPos() : range.getStartPos());
    }
}
