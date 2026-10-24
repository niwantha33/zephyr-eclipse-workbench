// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.equinox.p2.ui.ProvisioningUI;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import io.github.niwantha33.zew.core.GitHubUpdates;
import io.github.niwantha33.zew.core.ReleaseManifest;
import io.github.niwantha33.zew.core.UpdateStager;

/** Authenticated staging followed by standard p2 resolution/licence/trust/restart UI. */
public final class UpdateController {
    static final Object FAMILY = new Object();
    private static final AtomicReference<Object> OWNER = new AtomicReference<>();
    private UpdateController() {}

    public static void check(boolean manual) {
        Object owner = new Object();
        if (!OWNER.compareAndSet(null, owner)) return;
        var job = new Job("Check Zephyr Workbench updates") {
            @Override public boolean belongsTo(Object family) { return family == FAMILY; }
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    var release = new GitHubUpdates(UpdateConnection.credential()).check(monitor::isCanceled);
                    var installed = Activator.getDefault().getBundle().getVersion();
                    var store = Activator.getDefault().getPreferenceStore();
                    store.setValue("updates.lastAvailableVersion", release.version());
                    store.setValue("updates.lastSuccessfulCheck", java.time.Instant.now().toString());
                    store.setValue("updates.lastReleaseNotes", release.notes());
                    if (org.osgi.framework.Version.parseVersion(release.version()).compareTo(installed) <= 0) {
                        if (manual) ui(() -> MessageDialog.openInformation(shell(), "Workbench updates", "Installed version: " + installed + "\nChannel version: " + release.version() + "\n\nNo newer version is available.\n\n" + release.notes().substring(0, Math.min(1000, release.notes().length()))));
                    } else {
                        var preferences = Activator.getDefault().getPreferenceStore();
                        if (manual || !release.version().equals(preferences.getString("updates.lastNotified"))) {
                            preferences.setValue("updates.lastNotified", release.version());
                            ui(() -> notifyAvailable(release));
                        }
                    }
                    return Status.OK_STATUS;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); return Status.CANCEL_STATUS;
                } catch (InterruptedIOException error) { return Status.CANCEL_STATUS; }
                catch (Exception error) {
                    report(error, manual); return Status.CANCEL_STATUS;
                } finally { OWNER.compareAndSet(owner, null); }
            }
        };
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override public void done(IJobChangeEvent event) { OWNER.compareAndSet(owner, null); }
        });
        job.setUser(manual); job.setSystem(!manual); job.schedule();
    }

    private static void notifyAvailable(ReleaseManifest release) {
        var display = PlatformUI.getWorkbench().getDisplay();
        var notification = new Shell(display, SWT.TOOL | SWT.ON_TOP | SWT.CLOSE);
        notification.setText("Zephyr Workbench update available");
        notification.setLayout(new GridLayout(2, true));
        var label = new Label(notification, SWT.WRAP);
        label.setText("Installed version: " + Activator.getDefault().getBundle().getVersion()
                + "\nAvailable version: " + release.version() + "\n\nWhat's new:\n"
                + release.notes().substring(0, Math.min(1000, release.notes().length())));
        var layout = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        layout.widthHint = 420; label.setLayoutData(layout);
        var install = new Button(notification, SWT.PUSH);
        install.setText("Install Update...");
        install.addListener(SWT.Selection, event -> { notification.dispose(); install(release); });
        var later = new Button(notification, SWT.PUSH);
        later.setText("Later"); later.addListener(SWT.Selection, event -> notification.dispose());
        notification.pack();
        var area = display.getPrimaryMonitor().getClientArea();
        var size = notification.getSize();
        notification.setLocation(area.x + area.width - size.x - 20, area.y + area.height - size.y - 40);
        notification.setVisible(true);
    }

    private static boolean activeWork() {
        if (Job.getJobManager().find(ProjectActionHandler.FAMILY).length > 0 || Job.getJobManager().find(EditorIntegration.FAMILY).length > 0) return true;
        if (WorkbenchHandler.isOperationActive() || ToolsDialog.isOperationActive() || SetupWizard.isOperationActive()) return true;
        if (Job.getJobManager().find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length > 0
                || Job.getJobManager().find(ResourcesPlugin.FAMILY_AUTO_BUILD).length > 0) return true;
        for (var launch : DebugPlugin.getDefault().getLaunchManager().getLaunches()) if (!launch.isTerminated()) return true;
        return false;
    }

    private static void install(ReleaseManifest release) {
        if (activeWork()) {
            MessageDialog.openInformation(shell(), "Workbench update postponed", "Finish the active build/run/debug operation, then use Zephyr > Check for Workbench Updates."); return;
        }
        Object owner = new Object();
        if (!OWNER.compareAndSet(null, owner)) return;
        var provisioning = ProvisioningUI.getDefaultUI();
        var handedToUI = new AtomicBoolean();
        var job = new Job("Download and verify Zephyr Workbench " + release.version()) {
            @Override public boolean belongsTo(Object family) { return family == FAMILY; }
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    monitor.beginTask("Downloading private workbench update", release.artifacts().size());
                    var source = new GitHubUpdates(UpdateConnection.credential());
                    var cache = Activator.getDefault().getStateLocation().toFile().toPath().resolve("update-cache");
                    var site = new UpdateStager().stage(release, cache, file -> source.artifact(release, file, monitor::isCanceled),
                            monitor::isCanceled, name -> { monitor.subTask(name); monitor.worked(1); });
                    URI uri = site.toUri();
                    var agent = provisioning.getSession().getProvisioningAgent();
                    var manager = (IMetadataRepositoryManager) agent.getService(IMetadataRepositoryManager.SERVICE_NAME);
                    if (manager == null) throw new IOException("Eclipse p2 metadata service is unavailable.");
                    var repository = manager.loadRepository(uri, monitor);
                    var matches = repository.query(QueryUtil.createIUQuery(ReleaseManifest.FEATURE, Version.create(release.version())), monitor).toSet();
                    if (matches.size() != 1) throw new IOException("Verified repository does not contain the declared workbench feature/version.");
                    var operation = provisioning.getInstallOperation(List.copyOf(matches), new URI[] {uri});
                    var result = operation.resolveModal(monitor);
                    if (result.matches(IStatus.ERROR | IStatus.CANCEL)) throw new IOException("Eclipse could not resolve this update: " + result.getMessage());
                    handedToUI.set(true);
                    ui(() -> {
                        try {
                            if (activeWork()) {
                                MessageDialog.openInformation(shell(), "Update ready", "The update is downloaded. Finish the active operation, then check for updates again."); return;
                            }
                            provisioning.openInstallWizard(List.copyOf(matches), operation, null);
                        } finally { OWNER.compareAndSet(owner, null); }
                    });
                    return Status.OK_STATUS;
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); return Status.CANCEL_STATUS; }
                catch (InterruptedIOException error) { return Status.CANCEL_STATUS; }
                catch (Exception error) { report(error, true); return Status.CANCEL_STATUS; }
                finally { monitor.done(); if (!handedToUI.get()) OWNER.compareAndSet(owner, null); }
            }
        };
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override public void done(IJobChangeEvent event) { if (!handedToUI.get()) OWNER.compareAndSet(owner, null); }
        });
        job.setUser(true); job.schedule();
    }

    private static Shell shell() {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window == null ? null : window.getShell();
    }
    private static void ui(Runnable action) {
        if (PlatformUI.isWorkbenchRunning()) PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
            if (Activator.getDefault() != null) action.run();
        });
    }
    private static void report(Exception error, boolean manual) {
        String message = error.getMessage() == null ? "Workbench update failed." : error.getMessage();
        var plugin = Activator.getDefault();
        if (plugin != null) plugin.getLog().log(new Status(IStatus.WARNING, Activator.ID, message));
        if (manual) ui(() -> MessageDialog.openError(shell(), "Workbench update", message));
    }
}

