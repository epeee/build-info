package org.jfrog.build.extractor.maven;

import com.google.common.collect.Lists;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.jfrog.build.api.builder.ArtifactBuilder;
import org.jfrog.build.api.builder.BuildInfoBuilder;
import org.jfrog.build.api.builder.DependencyBuilder;
import org.jfrog.build.api.builder.ModuleBuilder;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.BuildInfoExtractor;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Noam Y. Tenne
 */
@Component(role = BuildInfoRecorder.class)
public class BuildInfoRecorder extends AbstractExecutionListener implements BuildInfoExtractor<ExecutionEvent, String> {

    @Requirement
    private Logger logger;

    private ExecutionListener wrappedListener;
    private BuildInfoBuilder buildInfoBuilder;
    private ModuleBuilder currentModule;
    private Set<Artifact> currentModuleArtifacts;
    private Set<Artifact> currentModuleDependencies;

    public void setListenerToWrap(ExecutionListener executionListener) {
        wrappedListener = executionListener;
    }

    @Override
    public void projectDiscoveryStarted(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.projectDiscoveryStarted(event);
        }
    }

    @Override
    public void sessionStarted(ExecutionEvent event) {
        initBuildInfo();

        if (wrappedListener != null) {
            wrappedListener.sessionStarted(event);
        }
    }

    @Override
    public void sessionEnded(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.sessionEnded(event);
        }
    }

    @Override
    public void projectSkipped(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.projectSkipped(event);
        }
    }

    @Override
    public void projectStarted(ExecutionEvent event) {
        MavenProject project = event.getProject();
        initModule(project);

        if (wrappedListener != null) {
            wrappedListener.projectStarted(event);
        }
    }

    @Override
    public void projectSucceeded(ExecutionEvent event) {
        extractArtifactsAndDependencies(event);
        finalizeAndAddModule(event);

        if (wrappedListener != null) {
            wrappedListener.projectSucceeded(event);
        }
    }

    @Override
    public void projectFailed(ExecutionEvent event) {
        extractArtifactsAndDependencies(event);
        finalizeAndAddModule(event);

        if (wrappedListener != null) {
            wrappedListener.projectFailed(event);
        }
    }

    @Override
    public void forkStarted(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkStarted(event);
        }
    }

    @Override
    public void forkSucceeded(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkSucceeded(event);
        }
    }

    @Override
    public void forkFailed(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkFailed(event);
        }
    }

    @Override
    public void mojoSkipped(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.mojoSkipped(event);
        }
    }

    @Override
    public void mojoStarted(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.mojoStarted(event);
        }
    }

    @Override
    public void mojoSucceeded(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info dependency extraction: Null project.");
            return;
        }
        extractModuleDependencies(project);

        if (wrappedListener != null) {
            wrappedListener.mojoSucceeded(event);
        }
    }

    @Override
    public void mojoFailed(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info dependency extraction: Null project.");
            return;
        }
        extractModuleDependencies(project);

        if (wrappedListener != null) {
            wrappedListener.mojoFailed(event);
        }
    }

    private void initBuildInfo() {
        logger.info("Initializing Artifactory Build-Info Recording");
        buildInfoBuilder = new BuildInfoBuilder("");
    }

    private void initModule(MavenProject project) {
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info module initialization: Null project.");
            return;
        }
        currentModule = new ModuleBuilder();
        currentModule.id(project.getId());
        currentModule.properties(project.getProperties());

        currentModuleArtifacts = new HashSet<Artifact>();
        currentModuleDependencies = new HashSet<Artifact>();
    }

    private void extractArtifactsAndDependencies(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info artifact and dependency extraction: Null project.");
            return;
        }
        extractModuleArtifact(project);
        extractModuleAttachedArtifacts(project);
        extractModuleDependencies(project);
    }

    private void extractModuleArtifact(MavenProject project) {
        Artifact artifact = project.getArtifact();
        if (artifact == null) {
            logger.warn("Skipping Artifactory Build-Info project artifact extraction: Null artifact.");
            return;
        }
        currentModuleArtifacts.add(artifact);
    }

    private void extractModuleAttachedArtifacts(MavenProject project) {
        List<Artifact> artifacts = project.getAttachedArtifacts();
        if (artifacts != null) {
            for (Artifact artifact : artifacts) {
                currentModuleArtifacts.add(artifact);
            }
        }
    }

    private void extractModuleDependencies(MavenProject project) {
        Set<Artifact> dependencies = project.getArtifacts();
        if (dependencies != null) {
            for (Artifact dependency : dependencies) {
                currentModuleDependencies.add(dependency);
            }
        }
    }

    private void finalizeAndAddModule(ExecutionEvent event) {
        addFilesToCurrentModule(event);

        currentModule = null;

        currentModuleArtifacts = null;
        currentModuleDependencies = null;
    }

    private void addFilesToCurrentModule(ExecutionEvent event) {
        if (currentModule == null) {
            logger.warn("Skipping Artifactory Build-Info module finalization: Null current module.");
            return;
        }
        addArtifactsToCurrentModule(event);
        addDependenciesToCurrentModule();

        buildInfoBuilder.addModule(currentModule.build());
    }

    private void addArtifactsToCurrentModule(ExecutionEvent event) {
        if (currentModuleArtifacts == null) {
            logger.warn("Skipping Artifactory Build-Info module artifact addition: Null current module artifact list.");
            return;
        }

        for (Artifact moduleArtifact : currentModuleArtifacts) {
            ArtifactBuilder artifactBuilder = new ArtifactBuilder(moduleArtifact.getId())
                    .type(moduleArtifact.getType());
            setArtifactChecksums(moduleArtifact.getFile(), artifactBuilder);
            currentModule.addArtifact(artifactBuilder.build());

            if (!isPomProject(moduleArtifact)) {
                for (ArtifactMetadata metadata : moduleArtifact.getMetadataList()) {
                    if (metadata instanceof ProjectArtifactMetadata) {
                        Model model = event.getProject().getModel();
                        if (model != null) {
                            File pomFile = model.getPomFile();
                            setArtifactChecksums(pomFile, artifactBuilder);
                        }
                        artifactBuilder.type("pom");
                        artifactBuilder.name(moduleArtifact.getId().replace(moduleArtifact.getType(), "pom"));
                        currentModule.addArtifact(artifactBuilder.build());
                    }
                }
            }
        }
    }

    private void addDependenciesToCurrentModule() {
        if (currentModuleDependencies == null) {
            logger.warn("Skipping Artifactory Build-Info module dependency addition: Null current module dependency " +
                    "list.");
            return;
        }
        for (Artifact dependency : currentModuleDependencies) {
            DependencyBuilder dependencyBuilder = new DependencyBuilder()
                    .id(dependency.getId())
                    .type(dependency.getType())
                    .scopes(Lists.newArrayList(dependency.getScope()));
            setDependencyChecksums(dependency.getFile(), dependencyBuilder);
            currentModule.addDependency(dependencyBuilder.build());
        }
    }

    private boolean isPomProject(Artifact moduleArtifact) {
        return "pom".equals(moduleArtifact.getType());
    }

    private void setArtifactChecksums(File artifactFile, ArtifactBuilder artifactBuilder) {
        if ((artifactFile != null) && (artifactFile.isFile())) {
            try {
                Map<String, String> checksums = FileChecksumCalculator.calculateChecksums(artifactFile, "md5", "sha1");
                artifactBuilder.md5(checksums.get("md5"));
                artifactBuilder.sha1(checksums.get("sha1"));
            } catch (Exception e) {
                logger.error("Could not set checksum values on '" + artifactBuilder.build().getName() + "': " +
                        e.getMessage());
            }
        }
    }

    private void setDependencyChecksums(File dependencyFile, DependencyBuilder dependencyBuilder) {
        if ((dependencyFile != null) && (dependencyFile.isFile())) {
            try {
                Map<String, String> checksumsMap =
                        FileChecksumCalculator.calculateChecksums(dependencyFile, "md5", "sha1");
                dependencyBuilder.md5(checksumsMap.get("md5"));
                dependencyBuilder.sha1(checksumsMap.get("sha1"));
            } catch (Exception e) {
                logger.error("Could not set checksum values on '" + dependencyBuilder.build().getId() + "': " +
                        e.getMessage());
            }
        }
    }

    public String extract(ExecutionEvent context) {
        throw new UnsupportedOperationException("Implement me");
    }
}