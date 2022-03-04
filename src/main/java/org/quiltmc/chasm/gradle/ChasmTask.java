package org.quiltmc.chasm.gradle;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.antlr.v4.runtime.CharStreams;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.quiltmc.chasm.api.ChasmProcessor;
import org.quiltmc.chasm.api.ClassData;
import org.quiltmc.chasm.api.metadata.Metadata;
import org.quiltmc.chasm.api.metadata.MetadataProvider;
import org.quiltmc.chasm.api.util.ClassLoaderClassInfoProvider;
import org.quiltmc.chasm.lang.ChasmLangTransformer;

public abstract class ChasmTask extends DefaultTask {
    /**
     * Specifies the classpath to transform.
     *
     * @return The input classpath.
     */
    @InputFiles
    public abstract Property<FileCollection> getClasspath();

    /**
     * Specifies additional transformers that are not found on the classpath.
     *
     * @return Additional transformers.
     */
    @InputFiles
    public abstract Property<FileCollection> getTransformers();

    /**
     * Specifies the directory in which to store the transformed classpath.
     *
     * @return The output directory.
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * Returns a FileCollection containing the classpath of the task.
     * The result can be passed into {@link DependencyHandler#create(Object)}.
     *
     * @param task The task from which to take the files.
     * @return A
     */
    public static FileCollection getFiles(ChasmTask task) {
        return task.getOutputDirectory().getAsFileTree()
                .matching(patternFilterable -> patternFilterable.include("*"))
                .plus(task.getProject().files(task.getOutputDirectory().dir("other")));
    }

    public ChasmTask() {
        getClasspath().convention(getProject().files());
        getTransformers().convention(getProject().files());
        getOutputDirectory().convention(getProject().getLayout().getBuildDirectory().dir("chasm/" + getName()));
    }

    public static String prefixName(String name) {
        return "chasm" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    @TaskAction
    public void run() throws IOException {
        // TODO: Use target JDK ClassInfo
        ChasmProcessor processor =
                new ChasmProcessor(new ClassLoaderClassInfoProvider(null, getClass().getClassLoader()));

        // Collect close-ables to close later
        Set<Closeable> toClose = new HashSet<>();

        // Delete output directory
        Path outDirectory = getOutputDirectory().getAsFile().get().toPath();
        getProject().delete(outDirectory);

        Path otherRoot = outDirectory.resolve("other");

        // Process classpath
        for (File file : getClasspath().get()) {
            // File may be either a jar, a zip or a directory
            if (file.isDirectory()) {
                // Walk files in directory with relative path
                Path sourceRoot = file.toPath();
                List<Path> paths = Files.walk(sourceRoot).filter(Files::isRegularFile).toList();

                for (Path sourcePath : paths) {
                    // Compute relative path
                    Path relative = sourceRoot.relativize(sourcePath);
                    String fileName = relative.getFileName().toString();

                    if (fileName.endsWith(".class")) {
                        // Add class files to the processor
                        byte[] bytes = Files.readAllBytes(sourcePath);
                        ClassData classData = new ClassData(bytes);
                        processor.addClass(classData);
                    } else {
                        if (relative.startsWith("org/quiltmc/chasm/transformers/") && fileName.endsWith(".chasm")) {
                            // Add transformers to the processor
                            ChasmLangTransformer transformer = ChasmLangTransformer.parse(sourcePath);
                            processor.addTransformer(transformer);
                        }

                        // Copy remaining files directly (including transformers)
                        Path targetPath = otherRoot.resolve(relative);
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath);
                    }
                }
            } else if (file.getName().endsWith(".jar")) {
                // Open jar/zip
                ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file));

                // Create target jar/zip
                Path targetPath = outDirectory.resolve(file.getName());
                Files.createDirectories(targetPath.getParent());
                ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(targetPath));
                toClose.add(zipOutputStream);

                ZipEntry entry;
                while ((entry = zipInputStream.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String fileName = entry.getName();
                    byte[] bytes = zipInputStream.readAllBytes();

                    if (fileName.endsWith(".class")) {

                        // Add class files to the processor
                        MetadataProvider metadataProvider = new MetadataProvider();
                        metadataProvider.put(TargetZipMetadata.class, new TargetZipMetadata(zipOutputStream));
                        ClassData classData = new ClassData(bytes, metadataProvider);
                        processor.addClass(classData);
                    } else {
                        if (fileName.startsWith("org/quiltmc/chasm/transformers/") && fileName.endsWith(".chasm")) {
                            // Add transformers to the processor
                            ChasmLangTransformer transformer = ChasmLangTransformer.parse(new String(bytes));
                            processor.addTransformer(transformer);
                        }

                        // Copy remaining files directly (including transformers)
                        zipOutputStream.putNextEntry(entry);
                        zipOutputStream.write(bytes);
                    }
                }

                // Close input stream
                zipInputStream.close();
            } else {
                // Unsupported
                continue;
            }
        }

        // Add explicitly specified transformers
        for (File transformerFile : getTransformers().get()) {
            InputStream inputStream = new FileInputStream(transformerFile);
            processor.addTransformer(ChasmLangTransformer.parse(CharStreams.fromStream(inputStream)));
        }

        // Process the classes
        List<ClassData> classes = processor.process();

        // Write the resulting classes
        for (ClassData classData : classes) {
            TargetZipMetadata targetMetadata = classData.getMetadataProvider().get(TargetZipMetadata.class);
            ClassReader classReader = new ClassReader(classData.getClassBytes());
            String path = classReader.getClassName() + ".class";

            if (targetMetadata == null) {
                Path targetPath = otherRoot.resolve(path);
                Files.createDirectories(targetPath.getParent());
                Files.write(targetPath, classData.getClassBytes());
            } else {
                targetMetadata.outputStream().putNextEntry(new ZipEntry(path));
                targetMetadata.outputStream().write(classData.getClassBytes());
            }
        }

        // Close everything
        for (Closeable closeable : toClose) {
            closeable.close();
        }
    }

    record TargetZipMetadata(ZipOutputStream outputStream) implements Metadata {
        @Override
        public Metadata copy() {
            return new TargetZipMetadata(outputStream);
        }
    }
}
