package io.github.bereket1144.agentcontroller.run;

/** Where a run is in its life. More values are added by the stories that need them. */
public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_FOR_INPUT,
    SUCCEEDED,
    FAILED,
    /** A reviewer stopped the run. It is not a failure and is not claimed until it is resumed. */
    STOPPED
}
