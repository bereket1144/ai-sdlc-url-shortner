package io.github.bereket1144.agentcontroller.artifact;

import java.io.Serializable;

/**
 * A pointer to one stored artifact version. It is all that graph state, checkpoints and logs carry about an artifact:
 * the content is read from the {@link ArtifactStore} when it is needed. It is serializable because graph state is.
 *
 * @param kind        the artifact kind
 * @param version     the version within the run and kind, starting at 1
 * @param fingerprint the fingerprint of the content, so a reader can tell it has the content it was pointed to
 */
public record ArtifactRef(String kind, int version, String fingerprint) implements Serializable { }
