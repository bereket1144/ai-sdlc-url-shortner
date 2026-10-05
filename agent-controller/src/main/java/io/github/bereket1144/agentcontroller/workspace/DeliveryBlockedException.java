package io.github.bereket1144.agentcontroller.workspace;

/** Generated work is retained when it cannot safely be merged into the controller checkout. */
public class DeliveryBlockedException extends RuntimeException {

    public DeliveryBlockedException(String reason) {
        super(reason);
    }

    DeliveryBlockedException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
