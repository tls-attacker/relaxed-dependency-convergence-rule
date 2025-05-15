package de.rub.nds.enforcer.rules.dependency;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.enforcer.rule.api.AbstractEnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleError;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.shared.dependency.graph.*;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

@Named("relaxedDependencyConvergence")
public class RelaxedDependencyConvergenceRule extends AbstractEnforcerRule {

    @Inject
    private MavenProject project;

    @Inject
    private MavenSession session;

    @Inject
    private DependencyCollectorBuilder dependencyCollectorBuilder;

    @Override
    public void execute() throws EnforcerRuleException {
        // Collect major versions of all dependencies
        Map<String, Set<String>> majorVersions = new HashMap<>();

        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setProject(project);
        try {
            DependencyNode rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null);
            collectMajorVersions(rootNode, majorVersions);
        } catch (DependencyCollectorBuilderException e) {
            throw new EnforcerRuleError(e);
        }

        // Check for major version conflicts (i.e., set size > 1)
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : majorVersions.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add(entry.getKey() + " -> major versions: " + entry.getValue());
            }
        }

        // Throw if at least one major version conflict has been detected
        if (!conflicts.isEmpty()) {
            throw new EnforcerRuleException("Major version conflicts detected:\n" + String.join("\n", conflicts));
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
            if (node.getPremanagedVersion() != null && !isVersionRange(node.getPremanagedVersion())) {
                major = extractMajorVersion(node.getPremanagedVersion());
            } else {
                major = extractMajorVersion(artifact.getVersion());
            }
            if (major != null) {
                versions.computeIfAbsent(key, k -> new HashSet<>()).add(major);
            }
        }
    }

    private String extractMajorVersion(String version) {
        String baseVersion = version.split("-")[0];
        String[] parts = baseVersion.split("\\.");
        return parts.length > 0 ? parts[0] : version;
    }

    private boolean isVersionRange(String version) {
        return version.contains("[") || version.contains("]") || version.contains("(") || version.contains(")");
    }

    @Override
    public String toString() {
        return "RelaxedDependencyConvergenceRule{}";
    }
}
