/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package com.redhat.devtools.lsp4ij;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.LightPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The restarts of the highlighting of a file that {@link LSPFileSupport} runs for the requests to restart it: a request
 * that comes while a restart runs, once that restart has taken its requests, gets a restart of its own.
 */
public class LSPFileSupportRestartTest extends LightPlatformTestCase {

    public void testARequestWhileARestartRunsGetsARestartOfItsOwn() {
        var file = createFile("restart.txt", "text");
        var support = LSPFileSupport.getSupport(file);
        try {
            support.setRestartDebounce(Duration.ofMillis(10));
            // The checker of the second request: the request asks it once, and its restart once more when it runs
            var asked = new AtomicInteger();
            CancelChecker second = asked::incrementAndGet;
            support.setRestartTakenHook(() -> {
                support.setRestartTakenHook(null);
                // The restart that runs for the first request has taken it, and still runs
                support.restartDaemonCodeAnalyzerWithDebounce(second);
            });

            support.restartDaemonCodeAnalyzerWithDebounce(LSPFileSupport.NO_CANCELLABLE_CHECKER);

            PlatformTestUtil.waitWithEventsDispatching("a restart must run for the request that came while one ran",
                    () -> asked.get() >= 2, 10);
        } finally {
            // The light project outlives the test: disposing the support cancels its pending restarts
            Disposer.dispose(support);
        }
    }
}
