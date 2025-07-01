/*
 * Relaxed Dependency Convergence Rule - A custom enforcer plugin to enforce major version convergence
 *
 * Ruhr University Bochum, Paderborn University, Technology Innovation Institute, and Hackmanit GmbH
 *
 * Licensed under Apache License 2.0 http://www.apache.org/licenses/LICENSE-2.0
 */
package de.rub.nds.enforcer.rules.dependency;

import java.util.*;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleError;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.*;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;

@Named("relaxedDependencyConvergence")
public class RelaxedDependencyConvergenceRule extends AbstractEnforcerRule {

    @Inject private MavenProject project;

    @Inject private MavenSession session;

    @Inject private DependencyCollectorBuilder dependencyCollectorBuilder;

    @Override
    public void execute() throws EnforcerRuleException {
        // Collect major versions of all dependencies
        Map<String, Set<String>> majorVersions = new HashMap<>();

        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(project);
        DependencyNode rootNode;
        try {
            rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null);
            collectMajorVersions(rootNode, majorVersions);
        } catch (DependencyCollectorBuilderException e) {
            throw new EnforcerRuleError(e);
        }

        // Check for major version conflicts (i.e., set size > 1)
        List<String> conflictMessage = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : majorVersions.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflictMessage.add(entry.getKey() + " -> major versions: " + entry.getValue());
                List<String> paths = collectDependencyPaths(rootNode, entry.getKey());
                for (String path : paths) {
                    conflictMessage.add("  " + path);
                }
            }
        }

        // Throw if at least one major version conflict has been detected
        if (!conflictMessage.isEmpty()) {
            throw new EnforcerRuleException(
                    "Major version conflicts detected:\n" + String.join("\n", conflictMessage));
        }
    }

    private void collectMajorVersions(DependencyNode rootNode, Map<String, Set<String>> versions) {
        CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
        rootNode.accept(visitor);
        for (DependencyNode node : visitor.getNodes()) {
            Artifact artifact = node.getArtifact();
            if (artifact == null) {
                continue;
            }
            String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
            String major;
            if (node.getPremanagedVersion() != null
                    && !isVersionRange(node.getPremanagedVersion())) {
                major = extractMajorVersion(node.getPremanagedVersion());
            } else {
                major = extractMajorVersion(artifact.getVersion());
            }
            if (major != null) {
                versions.computeIfAbsent(key, k -> new HashSet<>()).add(major);
            }
        }
    }

    private List<String> collectDependencyPaths(DependencyNode rootNode, String dependencyKey) {
        List<String> paths = new ArrayList<>();
        CollectingDependencyNodeVisitor visitor = new CollectingDependencyNodeVisitor();
        rootNode.accept(visitor);
        for (DependencyNode node : visitor.getNodes()) {
            Artifact artifact = node.getArtifact();
            if (artifact != null
                    && (artifact.getGroupId() + ":" + artifact.getArtifactId())
                            .equals(dependencyKey)) {
                paths.add(buildDependencyPath(node));
            }
        }
        return paths;
    }

    private String buildDependencyPath(DependencyNode node) {
        StringBuilder builder = new StringBuilder();
        DependencyNode current = node;
        while (current != null) {
            if (builder.length() > 0) {
                builder.insert(0, " -> ");
            }
            Artifact artifact = current.getArtifact();
            if (artifact != null) {
                builder.insert(
                        0,
                        artifact.getGroupId()
                                + ":"
                                + artifact.getArtifactId()
                                + ":"
                                + artifact.getVersion());
            }
            current = current.getParent();
        }
        return builder.toString();
    }

    private String extractMajorVersion(String version) {
        String baseVersion = version.split("-")[0];
        String[] parts = baseVersion.split("\\.");
        return parts.length > 0 ? parts[0] : version;
    }

    private boolean isVersionRange(String version) {
        return version.contains("[")
                || version.contains("]")
                || version.contains("(")
                || version.contains(")");
    }

    @Override
    public String toString() {
        return "RelaxedDependencyConvergenceRule{}";
    }
}
