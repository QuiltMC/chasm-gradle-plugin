package org.quiltmc.chasm.gradle;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.compile.AbstractCompile;

public class ChasmPlugin implements Plugin<Project> {
    /**
     * Applies this {@link Plugin} to the given {@link Project}.
     * Configures all {@link SourceSet SourceSets} and {@link JavaExec} tasks to use chasm.
     * This also automatically applies all transformers found in classpath and source resources.
     *
     * @param project The target project.
     */
    @Override
    public void apply(Project project) {
        project.getTasks().withType(JavaExec.class, ChasmPlugin::transformClasspath);

        project.getExtensions().configure(SourceSetContainer.class, sourceSets ->
                sourceSets.configureEach(sourceSet -> transformClasspath(project, sourceSet))
        );
    }

    /**
     * Transforms the {@link SourceSet#getCompileClasspath() CompileClasspath} of the given {@link SourceSet}.
     * This also applies the transformers found in the set's {@link SourceSet#getResources() Resources}.
     *
     * @param project   The current project.
     * @param sourceSet The source set to transform.
     */
    public static void transformClasspath(Project project, SourceSet sourceSet) {
        transformClasspath(
                project,
                ChasmTask.prefixName(sourceSet.getName()),
                sourceSet::getCompileClasspath,
                sourceSet::setCompileClasspath,
                () -> sourceSet.getResources().matching(p -> p.include("org/quiltmc/chasm/transformers/**/*.chasm"))
        );
    }

    /**
     * Transforms the {@link AbstractCompile#getClasspath() Classpath} of the given {@link AbstractCompile} task.
     *
     * @param compileTask The task to transform.
     */
    public static void transformClasspath(AbstractCompile compileTask) {
        transformClasspath(
                compileTask.getProject(),
                ChasmTask.prefixName(compileTask.getName()),
                compileTask::getClasspath,
                compileTask::setClasspath,
                compileTask.getProject()::files
        );
    }

    /**
     * Transforms the {@link JavaExec#getClasspath() Classpath} of the given {@link JavaExec} task.
     *
     * @param execTask The task to transform.
     */
    public static void transformClasspath(JavaExec execTask) {
        transformClasspath(
                execTask.getProject(),
                ChasmTask.prefixName(execTask.getName()),
                execTask::getClasspath,
                execTask::setClasspath,
                execTask.getProject()::files
        );
    }

    private static void transformClasspath(Project project,
                                           String taskName,
                                           Supplier<FileCollection> inClasspath,
                                           Consumer<FileCollection> outClasspath,
                                           Supplier<FileCollection> transformers) {
        // Create the chasm task, file dependency and output configuration
        ChasmTask task = project.getTasks().create(taskName, ChasmTask.class);
        Dependency dependency = project.getDependencies().create(ChasmTask.getFiles(task));
        Configuration configuration = project.getConfigurations().detachedConfiguration(dependency);

        project.afterEvaluate(p -> {
            task.getClasspath().set(inClasspath.get());
            task.getTransformers().set(transformers.get());
            outClasspath.accept(configuration);
        });
    }
}
