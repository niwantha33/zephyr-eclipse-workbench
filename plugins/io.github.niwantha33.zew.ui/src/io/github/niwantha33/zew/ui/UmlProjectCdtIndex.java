// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.ASTVisitor;
import org.eclipse.cdt.core.dom.ast.IASTDeclaration;
import org.eclipse.cdt.core.dom.ast.IASTExpression;
import org.eclipse.cdt.core.dom.ast.IASTFileLocation;
import org.eclipse.cdt.core.dom.ast.IASTFunctionCallExpression;
import org.eclipse.cdt.core.dom.ast.IASTFunctionDefinition;
import org.eclipse.cdt.core.dom.ast.IASTIdExpression;
import org.eclipse.cdt.core.dom.ast.IASTName;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.IFunction;
import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexName;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.ITranslationUnit;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * CDT index-backed, read-only project graph. Only AST function definitions
 * and direct call expressions that resolve to a unique project definition
 * are drawn. Unresolved calls, macros, callbacks, dynamic/virtual dispatch
 * and files outside this workspace project are deliberately omitted.
 */
public final class UmlProjectCdtIndex {
    private static final int MAX_DISCOVERED_FILES = 3000;
    private static final int MAX_VISITS_PER_FILE = 8000;

    private UmlProjectCdtIndex() {}

    public static UmlProjectGraph.Snapshot inspect(IProject project, IProgressMonitor monitor) {
        String name = project == null ? "" : project.getName();
        if (project == null || !project.isAccessible() || project.getLocation() == null)
            return UmlProjectGraph.unavailable(name, "Select an accessible local CDT project.");
        try {
            var cProject = CoreModel.getDefault().create(project);
            if (cProject == null)
                return UmlProjectGraph.unavailable(name, "No CDT project. Build and refresh C/C++ editor settings.");
            List<IFile> files = new ArrayList<>();
            boolean[] incomplete = {false};
            int[] visited = {0};
            project.accept(resource -> {
                if (monitor.isCanceled()) return false;
                if (++visited[0] > MAX_DISCOVERED_FILES) {
                    incomplete[0] = true;
                    return false;
                }
                if (resource.getType() == IResource.FOLDER) {
                    String dir = resource.getName().toLowerCase(Locale.ROOT);
                    if (dir.startsWith("build") || dir.equals(".git") || dir.equals(".west")
                            || dir.equals(".settings") || dir.equals("out")) return false;
                }
                if (resource instanceof IFile file && isSource(file)) files.add(file);
                return true;
            });
            files.sort(Comparator.comparing(f -> f.getProjectRelativePath().toString()));
            if (files.size() > UmlProjectGraph.MAX_FILES) incomplete[0] = true;
            var nodes = new ArrayList<UmlProjectGraph.Node>();
            var calls = new ArrayList<UmlProjectGraph.Edge>();
            IIndex index = CCorePlugin.getIndexManager().getIndex(cProject);
            index.acquireReadLock();
            int scanned = 0;
            try {
                if (!index.isFullyInitialized()) incomplete[0] = true;
                for (IFile file : files) {
                    if (scanned == UmlProjectGraph.MAX_FILES || monitor.isCanceled()) break;
                    var element = CoreModel.getDefault().create(file);
                    if (!(element instanceof ITranslationUnit unit) || !unit.isStructureKnown()) {
                        incomplete[0] = true;
                        continue;
                    }
                    // The CDT read lock is held throughout parsing, binding
                    // lookup and AST traversal; no AST outlives that lock.
                    IASTTranslationUnit ast = unit.getAST(index, ITranslationUnit.AST_SKIP_ALL_HEADERS);
                    if (ast == null) {
                        incomplete[0] = true;
                        continue;
                    }
                    scanned++;
                    collect(ast, index, project, nodes, calls, incomplete, monitor);
                    if (nodes.size() > UmlProjectGraph.MAX_NODES
                            || calls.size() > UmlProjectGraph.MAX_EDGES * 3) {
                        incomplete[0] = true;
                        break;
                    }
                }
            } finally {
                index.releaseReadLock();
            }
            if (monitor.isCanceled())
                return UmlProjectGraph.unavailable(name, "Project graph generation cancelled.");
            String detail = "Static CDT direct-call evidence only; unresolved/indirect calls omitted.";
            if (incomplete[0]) detail += " Partial scan (file/AST/model limits or missing CDT outline).";
            return UmlProjectGraph.of(name, nodes, calls, scanned, incomplete[0], detail);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return UmlProjectGraph.unavailable(name, "CDT index operation cancelled.");
        } catch (CoreException | RuntimeException failure) {
            return UmlProjectGraph.unavailable(name,
                    "CDT project/index unavailable: " + failure.getClass().getSimpleName()
                    + ". Build once and use Tools and Setup > Refresh C/C++ Editor Settings.");
        }
    }

    private static void collect(IASTTranslationUnit ast, IIndex index, IProject project,
                                List<UmlProjectGraph.Node> nodes,
                                List<UmlProjectGraph.Edge> calls, boolean[] incomplete,
                                IProgressMonitor monitor) {
        ASTVisitor visitor = new ASTVisitor() {
            String caller;
            int visits;
            { shouldVisitDeclarations = true; shouldVisitExpressions = true; }

            private boolean stop() {
                if (monitor.isCanceled() || ++visits > MAX_VISITS_PER_FILE) {
                    incomplete[0] = true;
                    return true;
                }
                return false;
            }

            @Override public int visit(IASTDeclaration declaration) {
                if (stop()) return PROCESS_ABORT;
                if (!(declaration instanceof IASTFunctionDefinition fn)) return PROCESS_CONTINUE;
                caller = null;
                if (!fn.isPartOfTranslationUnitFile()) return PROCESS_SKIP;
                IASTName name = fn.getDeclarator().getName();
                if (name == null || !(name.resolveBinding() instanceof IFunction)) return PROCESS_SKIP;
                IASTFileLocation location = name.getFileLocation();
                String rel = relative(location, project);
                if (rel == null) return PROCESS_SKIP;
                caller = id(location);
                if (nodes.size() >= UmlProjectGraph.MAX_NODES) {
                    incomplete[0] = true;
                    caller = null;
                    return PROCESS_SKIP;
                }
                nodes.add(new UmlProjectGraph.Node(caller, name.toString(), rel,
                        location.getNodeOffset()));
                return PROCESS_CONTINUE;
            }

            @Override public int leave(IASTDeclaration declaration) {
                if (declaration instanceof IASTFunctionDefinition) caller = null;
                return PROCESS_CONTINUE;
            }

            @Override public int visit(IASTExpression expression) {
                if (stop()) return PROCESS_ABORT;
                if (caller == null || !(expression instanceof IASTFunctionCallExpression call)
                        || !call.isPartOfTranslationUnitFile()
                        || !(call.getFunctionNameExpression() instanceof IASTIdExpression direct))
                    return PROCESS_CONTINUE;
                IBinding binding = direct.getName().resolveBinding();
                if (!(binding instanceof IFunction)) return PROCESS_CONTINUE;
                // Only connect a unique, concrete definition. Do not connect
                // solely by spelling, even if two files use the same name.
                var targets = new LinkedHashSet<String>();
                for (IASTName definition : ast.getDefinitionsInAST(binding)) {
                    IASTFileLocation location = definition.getFileLocation();
                    if (relative(location, project) != null) targets.add(id(location));
                }
                try {
                    for (IIndexName definition : index.findDefinitions(binding)) {
                        IASTFileLocation location = definition.getFileLocation();
                        if (relative(location, project) != null) targets.add(id(location));
                        if (targets.size() > 1) break;
                    }
                } catch (CoreException unresolved) {
                    return PROCESS_CONTINUE;
                }
                if (targets.size() == 1 && calls.size() < UmlProjectGraph.MAX_EDGES * 3)
                    calls.add(new UmlProjectGraph.Edge(caller, targets.iterator().next()));
                else if (calls.size() >= UmlProjectGraph.MAX_EDGES * 3)
                    incomplete[0] = true;
                return PROCESS_CONTINUE;
            }
        };
        ast.accept(visitor);
    }

    static String id(IASTFileLocation location) {
        return normalized(location.getFileName()) + ":" + location.getNodeOffset();
    }

    private static String relative(IASTFileLocation location, IProject project) {
        if (location == null || location.getFileName() == null) return null;
        try {
            Path root = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
            Path path = Path.of(location.getFileName()).toAbsolutePath().normalize();
            return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : null;
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }

    private static String normalized(String path) {
        String result = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/');
        return result.length() > 1 && result.charAt(1) == ':'
                ? result.toLowerCase(Locale.ROOT) : result;
    }

    private static boolean isSource(IFile file) {
        if (!file.isAccessible()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".c") || name.endsWith(".cpp")
                || name.endsWith(".cc") || name.endsWith(".cxx");
    }
}
