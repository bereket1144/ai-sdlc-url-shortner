package io.github.bereket1144.agentcontroller.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Prepared model replies for deterministic agent tests; it never makes a network request. */
public final class ScriptedModelClient implements ModelClient {

    private final Deque<Object> prepared = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    public void addResult(ModelResult result) {
        prepared.addLast(result);
    }

    public void addFailure(ModelCallException failure) {
        prepared.addLast(failure);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public ModelResult call(String role, ModelProfile profile, String systemText, String userText) {
        calls.add(new Call(role, profile, systemText, userText));
        if (prepared.isEmpty()) {
            throw new AssertionError("No scripted model answer prepared for call " + calls.size());
        }
        Object next = prepared.removeFirst();
        if (next instanceof ModelCallException failure) {
            throw failure;
        }
        return (ModelResult) next;
    }

    public record Call(String role, ModelProfile profile, String systemText, String userText) { }
}
