package io.github.bereket1144.agentcontroller.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Merges a completed run into a clean checkout, retaining its history and refusing conflicts or unrelated writes. */
@Service
@ConditionalOnProperty(name = "agent.stages.development", havingValue = "agent")
public class WorkspaceMerge {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMerge.class);
    private static final Map<String, String> AUTHOR = Map.of(
            "GIT_AUTHOR_NAME", "controller", "GIT_AUTHOR_EMAIL", "controller@agent.invalid",
            "GIT_COMMITTER_NAME", "controller", "GIT_COMMITTER_EMAIL", "controller@agent.invalid");
    private static final List<String> OPERATIONS = List.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD",
            "rebase-merge", "rebase-apply", "sequencer");

    private final RunWorkspace workspaces;

    public WorkspaceMerge(RunWorkspace workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * A merge-tree preflight detects conflicts without touching the index or working files. The merge itself uses the
     * inspected commit ID, so a moving branch cannot substitute different work. Calls in this controller are serialized.
     */
    public synchronized String merge(UUID runId) {
        Path repository = workspaces.repository();
        try {
            String source = git("rev-parse", "refs/heads/run/" + runId);
            String before = git("rev-parse", "HEAD");
            String branch = requireReady(runId);
            GitCommands.Result ancestry = GitCommands.execute(repository, Map.of(),
                    "merge-base", "--is-ancestor", source, before);
            if (ancestry.exitCode() == 0) {
                log.info("event=run_delivery_unchanged runId={} commit={}", runId, before);
                return before;
            }
            if (ancestry.exitCode() != 1) {
                throw blocked(runId, "GIT_FAILED");
            }
            String ancestor = git("merge-base", before, source);
            String changes = git("diff", "--name-only", "-z", ancestor, source);
            for (String path : changes.split("\u0000")) {
                if (!path.isEmpty() && !path.startsWith(workspaces.productPath() + "/")
                        && !path.startsWith("runs/")) {
                    throw blocked(runId, "UNRELATED_CHANGES");
                }
            }
            GitCommands.Result preview = GitCommands.execute(repository, Map.of(),
                    "merge-tree", "--write-tree", before, source);
            if (preview.exitCode() != 0) {
                throw blocked(runId, preview.exitCode() == 1 ? "MERGE_CONFLICT" : "GIT_FAILED");
            }
            // A human may have changed this checkout while Git computed the merge. Do not merge into another branch.
            if (!git("rev-parse", "HEAD").equals(before) || !requireReady(runId).equals(branch)) {
                throw blocked(runId, "CHECKOUT_CHANGED");
            }
            log.info("event=run_delivery_started runId={} sourceCommit={}", runId, source);
            mergeCommit(runId, source);
            String merged = git("rev-parse", "HEAD");
            log.info("event=run_delivered runId={} sourceCommit={} mergeCommit={}", runId, source, merged);
            return merged;
        } catch (DeliveryBlockedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            log.error("event=run_delivery_blocked runId={} reason=GIT_FAILED errorType={}",
                    runId, failure.getClass().getName());
            throw new DeliveryBlockedException("GIT_FAILED", failure);
        }
    }

    private String requireReady(UUID runId) {
        GitCommands.Result branch = GitCommands.execute(workspaces.repository(), Map.of(),
                "symbolic-ref", "--quiet", "HEAD");
        if (branch.exitCode() != 0) {
            throw blocked(runId, "DETACHED_HEAD");
        }
        for (String operation : OPERATIONS) {
            Path marker = Path.of(git("rev-parse", "--path-format=absolute", "--git-path", operation));
            if (Files.exists(marker)) {
                throw blocked(runId, "GIT_OPERATION_IN_PROGRESS");
            }
        }
        if (!git("status", "--porcelain=v1", "--untracked-files=all").isEmpty()) {
            throw blocked(runId, "DIRTY_CHECKOUT");
        }
        return branch.output();
    }

    private void abortOwnMerge(String source) {
        Path marker = Path.of(git("rev-parse", "--path-format=absolute", "--git-path", "MERGE_HEAD"));
        if (Files.exists(marker) && git("rev-parse", "MERGE_HEAD").equals(source)) {
            git("merge", "--abort");
        }
    }

    private void mergeCommit(UUID runId, String source) {
        try {
            GitCommands.Result result = GitCommands.execute(workspaces.repository(), AUTHOR,
                    "-c", "commit.gpgSign=false", "-c", "core.hooksPath=" + nullDevice(),
                    "merge", "--no-ff", "--no-edit", "--no-stat", "-m",
                    "controller: merge completed run " + runId, source);
            if (result.exitCode() != 0) {
                throw blocked(runId, "GIT_FAILED");
            }
        } catch (RuntimeException failure) {
            try {
                abortOwnMerge(source);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                log.error("event=run_delivery_cleanup_failed runId={} errorType={}",
                        runId, cleanupFailure.getClass().getName());
            }
            throw failure;
        }
    }

    private String git(String... arguments) {
        return GitCommands.run(workspaces.repository(), Map.of(), arguments);
    }

    private static DeliveryBlockedException blocked(UUID runId, String reason) {
        log.warn("event=run_delivery_blocked runId={} reason={}", runId, reason);
        return new DeliveryBlockedException(reason);
    }

    private static String nullDevice() {
        return System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
    }
}
