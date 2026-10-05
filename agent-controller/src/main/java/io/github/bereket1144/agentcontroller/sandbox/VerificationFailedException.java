package io.github.bereket1144.agentcontroller.sandbox;

/**
 * A build and test run that did not pass: the project did not build, a test failed, or no readable result arrived.
 * The message is fixed text with the outcome only, so it never repeats a test name or any report text.
 */
public class VerificationFailedException extends RuntimeException {

    public VerificationFailedException(VerificationResult.Outcome outcome) {
        super("Verification did not pass: " + outcome);
    }
}
