
package com.freemaf.agent.cdp;

import java.io.IOException;

/**
 * Abstraction over ChromeProcessLauncher to allow unit-test injection.
 */
public interface ChromeLauncherFacade {

    Process start(boolean headless, int port) throws IOException;

    void stop(Process process);
}
