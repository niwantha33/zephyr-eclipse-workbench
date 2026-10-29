// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import org.eclipse.cdt.dsf.datamodel.IDMContext;
import org.eclipse.cdt.dsf.debug.service.command.ICommand;
import org.eclipse.cdt.dsf.debug.service.command.ICommandControl;
import org.eclipse.cdt.dsf.gdb.service.GdbDebugServicesFactory;
import org.eclipse.cdt.dsf.gdb.service.command.GDBControl_7_12;
import org.eclipse.cdt.dsf.gdb.service.command.CommandFactory_6_8;
import org.eclipse.cdt.dsf.mi.service.command.output.MIInfo;
import org.eclipse.cdt.dsf.service.DsfSession;
import org.eclipse.debug.core.ILaunchConfiguration;

/** Use OpenOCD's extended remote protocol through the existing DSF launch sequence. */
public final class ZephyrDebugServicesFactory extends GdbDebugServicesFactory {
    public ZephyrDebugServicesFactory(String version, ILaunchConfiguration config) { super(version, config); }
    public static final class OpenOcdCommands extends CommandFactory_6_8 {
        @Override public ICommand<MIInfo> createMITargetSelect(IDMContext context, String host, String port, boolean extended) {
            return super.createMITargetSelect(context, host, port, true);
        }
    }
    @Override protected ICommandControl createCommandControl(DsfSession session, ILaunchConfiguration configuration) {
        if (compareVersionWith(GDB_7_12_VERSION) >= 0)
            return new GDBControl_7_12(session, configuration, new OpenOcdCommands());
        return super.createCommandControl(session, configuration);
    }
}
