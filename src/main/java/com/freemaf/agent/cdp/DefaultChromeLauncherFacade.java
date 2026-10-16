
package com.freemaf.agent.cdp;

import java.io.IOException;

/**
 * Default facade delegating to ChromeProcessLauncher static methods.
 */
public final class DefaultChromeLauncherFacade implements ChromeLauncherFacade {

    @Override
    public Process start(boolean headless, int port) throws IOException {
        return ChromeProcessLauncher.start(headless, port);
    }

    @Override
    public void stop(Process process) {
        ChromeProcessLauncher.stop(process);
    }
}
