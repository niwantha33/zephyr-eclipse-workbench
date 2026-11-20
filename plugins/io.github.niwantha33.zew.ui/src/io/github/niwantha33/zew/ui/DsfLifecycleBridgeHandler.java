// SPDX-License-Identifier: Apache-2.0
// Adapted from RTOS Observatory W14 source-only bridge for the owning Workbench DSF session.
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.cdt.dsf.debug.service.IRunControl;
import org.eclipse.cdt.dsf.gdb.launching.GdbLaunch;
import org.eclipse.cdt.dsf.service.DsfServiceEventHandler;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opt-in passive observer of the EXISTING CDT/DSF-GDB owner.
 * There is NO target read, no MI command, no GDB/OpenOCD connection,
 * no halt, resume, reset, flash, breakpoint or thread control.
 *
 * Software integration only. Hardware/installed-product behaviour is not qualified.
 */
public final class DsfLifecycleBridgeHandler extends AbstractHandler {
    private static final AtomicReference<Subscription> ACTIVE = new AtomicReference<>();
    private static final String FILENAME = "rtos-observatory-dsf-status.tsv";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final Shell shell = HandlerUtil.getActiveShellChecked(event);
        final Subscription previous = ACTIVE.getAndSet(null);
        if (previous != null) {
            previous.close();
            MessageDialog.openInformation(shell, "RTOS Observatory",
                    "Stopped passive DSF lifecycle publishing. No debugger state was changed.");
            return null;
        }
        final ILaunch selected = selectedLaunch();
        if (!(selected instanceof GdbLaunch launch)) {
            MessageDialog.openWarning(shell, "RTOS Observatory",
                    "Select an already-running CDT DSF-GDB launch in the Debug view.");
            return null;
        }
        final DsfSession session = launch.getSession();
        if (launch.isTerminated() || session == null ||
            !DsfSession.isSessionActive(session.getId())) {
            MessageDialog.openWarning(shell, "RTOS Observatory",
                    "The selected CDT/DSF-GDB session is not active.");
            return null;
        }
        final String location = System.getenv("RTOS_OBSERVATORY_BRIDGE_DIR");
        if (location == null || location.isBlank()) {
            MessageDialog.openWarning(shell, "RTOS Observatory",
                    "Set RTOS_OBSERVATORY_BRIDGE_DIR to a private local directory, then restart Eclipse.");
            return null;
        }
        try {
            final Path configured = Path.of(location).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(configured))
                throw new IOException("Bridge directory must not be a symlink");
            final Path directory = configured.toRealPath();
            if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
                throw new IOException("Bridge directory must be an existing real directory");
            }
            final Path status = directory.resolve(FILENAME);
            if (Files.isSymbolicLink(status))
                throw new IOException("Refusing to replace a symlinked bridge status file");
            final Subscription subscription = new Subscription(launch,session,status);
            if (!ACTIVE.compareAndSet(null,subscription)) {
                subscription.close();
                throw new IOException("Another passive bridge is already active");
            }
            subscription.start();
            MessageDialog.openInformation(shell, "RTOS Observatory",
                    "Passive DSF lifecycle publishing started:\n" + status +
                    "\nNo GDB commands or target memory reads are performed.");
        } catch (IOException | RejectedExecutionException failure) {
            final Subscription active=ACTIVE.getAndSet(null);
            if (active != null) active.close();
            MessageDialog.openError(shell, "RTOS Observatory",
                    "Could not start passive bridge: " + failure.getMessage());
        }
        return null;
    }

    private static ILaunch selectedLaunch() {
        final var window=PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if(window==null)return null;
        final ISelection selected=DebugUITools.getDebugContextManager()
                .getContextService(window).getActiveContext();
        if(!(selected instanceof IStructuredSelection list))return null;
        final Object element=list.getFirstElement();
        if(element instanceof ILaunch launch)return launch;
        if(element instanceof IDebugElement debug)return debug.getLaunch();
        return null;
    }

    public static final class Subscription implements AutoCloseable, ILaunchesListener2 {
        private final GdbLaunch launch;
        private final DsfSession session;
        private final Path output;
        private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread=new Thread(runnable,"rtos-observatory-dsf-publisher");
                    thread.setDaemon(true);
                    return thread;
                });
        private final String nonce=UUID.randomUUID().toString();
        private final String launchId="launch-"+UUID.randomUUID();
        private final String dsfId=Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[] { 'd', 's', 'f' }) + "-" +
                UUID.randomUUID(); // opaque tag, does not expose actual DSF ID
        private final long epoch=(new SecureRandom().nextLong() & Long.MAX_VALUE) | 1L;
        private long sequence;
        private String state="ATTACHED";
        private boolean closed;

        private Subscription(GdbLaunch launch,DsfSession session,Path output) {
            this.launch=launch;
            this.session=session;
            this.output=output;
        }
        private void start() {
            // Never block the DSF executor waiting for I/O; publisher has one
            // dedicated thread and reads no debugger service state.
            publish("ATTACHED");
            DebugPlugin.getDefault().getLaunchManager().addLaunchListener(this);
            session.getExecutor().execute(() ->
                session.addServiceEventListener(this,null));
            // A metadata-only heartbeat says nothing about actual target readiness.
            writer.scheduleAtFixedRate(() -> {
                synchronized(Subscription.this) {
                    if (!closed) publish(state);
                }
            },2,2,TimeUnit.SECONDS);
        }

        @DsfServiceEventHandler
        public void eventDispatched(IRunControl.ISuspendedDMEvent event) {
            if (event.getDMContext().getSessionId().equals(session.getId()))
                publish("SUSPENDED");
        }
        @DsfServiceEventHandler
        public void eventDispatched(IRunControl.IResumedDMEvent event) {
            if (event.getDMContext().getSessionId().equals(session.getId()))
                publish("RUNNING");
        }

        private synchronized void publish(String nextState) {
            if (closed)return;
            state=nextState;
            final long next=++sequence;
            final String snapshot="RTOS-OBSERVATORY-DSF\t1\t"+nonce+"\t"+
                    launchId+"\t"+dsfId+"\t"+epoch+"\t"+next+"\t"+
                    Instant.now().toEpochMilli()+"\t"+state+"\tmetadata-only\n";
            try {
                writer.execute(() -> writeAtomic(snapshot));
            } catch (RejectedExecutionException ignored) {
                // Session is disposing; do not retry or touch target state.
            }
        }
        private void writeAtomic(String snapshot) {
            // Unique private temp file avoids cross-Eclipse writer collisions;
            // publication is all-or-nothing on the selected filesystem.
            Path temporary=null;
            try {
                if(Files.isSymbolicLink(output))return;
                temporary=Files.createTempFile(output.getParent(),"rtos-dsf-", ".tmp");
                Files.writeString(temporary,snapshot,StandardCharsets.UTF_8);
                Files.move(temporary,output,StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Fail CLOSED rather than fall back to partial replacement.
            } catch (IOException ignored) {
                // Metadata bridge failures must not break the active debugger.
            } finally {
                if(temporary!=null) {
                    try {Files.deleteIfExists(temporary);}
                    catch(IOException ignored) {}
                }
            }
        }

        private boolean contains(ILaunch[] launches) {
            for (final ILaunch item:launches)
                if (item==launch)return true;
            return false;
        }
        @Override
        public void launchesAdded(ILaunch[] launches) {}
        @Override
        public void launchesChanged(ILaunch[] launches) {
            if(contains(launches) && launch.isTerminated())close();
        }
        @Override
        public void launchesRemoved(ILaunch[] launches) {
            if(contains(launches))close();
        }
        @Override
        public void launchesTerminated(ILaunch[] launches) {
            if(contains(launches))close();
        }
        @Override
        public void close() {
            synchronized(this) {
                if(closed)return;
                publish(launch.isTerminated()?"TERMINATED":"DISCONNECTED");
                closed=true;
            }
            try {
                session.getExecutor().execute(() ->
                    session.removeServiceEventListener(this));
            } catch(RejectedExecutionException ignored) {
                // DSF session already disposed; no second connection attempted.
            }
            writer.shutdown();
            DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(this);
            ACTIVE.compareAndSet(this,null);
        }
    }
}
