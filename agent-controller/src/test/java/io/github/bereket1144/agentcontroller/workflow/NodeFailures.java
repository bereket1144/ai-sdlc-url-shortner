package io.github.bereket1144.agentcontroller.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Lets tests outside this package make a node of the real graph fail; the node methods are package-private. */
public final class NodeFailures {

    private NodeFailures() {
    }

    /** Makes the QA node of a spied {@link DummySdlcNodes} throw the given failure. */
    public static void qaThrows(DummySdlcNodes spiedNodes, Throwable failure) {
        doThrow(failure).when(spiedNodes).qa(any());
    }
}
