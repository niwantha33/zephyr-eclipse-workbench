// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

/**
 * Optional, runtime-linked Trace Compass bridge. No Trace Compass compile
 * dependency; no source-tree modifications, probe, download or launcher.
 */
final class TraceCompassNativeBridge {
    static final String TMF_UI = "org.eclipse.tracecompass.tmf.ui";
    static final String CTF_CORE = "org.eclipse.tracecompass.tmf.ctf.core";
    static final String TRACING_PERSPECTIVE = "org.eclipse.linuxtools.tmf.ui.perspective";
    private static final String ANALYSIS_PROJECT = "Zephyr_Trace_Analysis";

    private TraceCompassNativeBridge() {}

    static String availability() {
        Bundle ui = Platform.getBundle(TMF_UI);
        Bundle ctf = Platform.getBundle(CTF_CORE);
        if (ui == null || ui.getState() == Bundle.UNINSTALLED)
            return "Trace Compass TMF UI plug-in not installed in this Eclipse";
        if (ctf == null || ctf.getState() == Bundle.UNINSTALLED)
            return "CTF reader plug-in is missing; install Trace Compass CTF Analysis feature";
        if (!PlatformUI.isWorkbenchRunning()
                || PlatformUI.getWorkbench().getPerspectiveRegistry()
                        .findPerspectiveWithId(TRACING_PERSPECTIVE) == null)
            return "Trace Compass Tracing perspective is not registered; restart Eclipse and check plug-in resolution";
        return null;
    }

    static boolean installed() {
        return availability() == null;
    }

    static IStatus open(Path ctf, Shell shell) throws Exception {
        String problem = availability();
        if (problem != null) throw new IllegalStateException(problem);
        Path folder = ctf.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder)
                || !Files.isRegularFile(folder.resolve("metadata"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(folder.resolve("channel0_0"), LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Saved CTF requires metadata and channel0_0 regular files");
        Bundle bundle = Platform.getBundle(TMF_UI);
        Class<?> registry = bundle.loadClass(
                "org.eclipse.tracecompass.tmf.ui.project.model.TmfProjectRegistry");
        Class<?> projectElement = bundle.loadClass(
                "org.eclipse.tracecompass.tmf.ui.project.model.TmfProjectElement");
        Class<?> folderElement = bundle.loadClass(
                "org.eclipse.tracecompass.tmf.ui.project.model.TmfTraceFolder");
        Class<?> helper = bundle.loadClass(
                "org.eclipse.tracecompass.tmf.ui.project.model.TmfOpenTraceHelper");

        // Dedicated tracing workspace project; never change Zephyr project nature.
        IProject target = ResourcesPlugin.getWorkspace().getRoot().getProject(ANALYSIS_PROJECT);
        if (target.exists() && !target.hasNature("org.eclipse.linuxtools.tmf.project.nature"))
            throw new IllegalStateException("Workspace project Zephyr_Trace_Analysis exists "
                    + "but is not a Trace Compass tracing project");
        if (!target.exists()) {
            target = (IProject) invoke(registry.getMethod(
                    "createProject", String.class, URI.class, IProgressMonitor.class),
                    null, ANALYSIS_PROJECT, null, null);
        }
        if (target == null || !target.exists() || !target.isOpen())
            throw new IllegalStateException("Trace Compass tracing project could not be created");
        Object model = invoke(registry.getMethod("getProject", IProject.class, boolean.class),
                null, target, true);
        if (model == null)
            throw new IllegalStateException("Trace Compass project registry returned no model");
        Object traces = invoke(projectElement.getMethod("getTracesFolder"), model);
        if (traces == null)
            throw new IllegalStateException("Trace Compass traces folder is unavailable");
        return (IStatus) invoke(helper.getMethod(
                "openTraceFromPath", folderElement, String.class, Shell.class, String.class),
                null, traces, folder.resolve("metadata").toString(), shell, null);
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object... args)
            throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw error;
        }
    }
}
