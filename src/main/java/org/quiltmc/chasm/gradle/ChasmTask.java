package org.quiltmc.chasm.gradle;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.quiltmc.chasm.api.ChasmProcessor;
import org.quiltmc.chasm.api.ClassResult;
import org.quiltmc.chasm.api.Transformer;
import org.quiltmc.chasm.api.util.ClassInfo;
import org.quiltmc.chasm.api.util.Context;
import org.quiltmc.chasm.internal.transformer.ChasmLangTransformer;
import org.quiltmc.chasm.lang.api.ast.Node;
import org.quiltmc.chasm.lang.api.metadata.Metadata;

public abstract class ChasmTask extends DefaultTask {
    private final ConfigurableFileCollection classpath = getProject().files();
    private final ConfigurableFileCollection transformers = getProject().files();

    /**
     * Specifies the classpath to transform.
     *
     * @return The input classpath.
     */
    @InputFiles
    public ConfigurableFileCollection getClasspath() {
        return classpath;
    }

    /**
     * Specifies additional transformers that are not found on the classpath.
     *
     * @return Additional transformers.
     */
    @InputFiles
    public ConfigurableFileCollection getTransformers() {
        return transformers;
    }

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
                .plus(task.getProject().files(task.getOutputDirectory().dir("chasm-added-files")));
    }

    public ChasmTask() {
        getOutputDirectory().convention(getProject().getLayout().getBuildDirectory().dir("chasm/" + getName()));
    }

    public static String prefixName(String name) {
        return "chasm" + name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    @TaskAction
    public void run() throws IOException {
        // TODO: Use target JDK ClassInfo
        ChasmProcessor processor = new ChasmProcessor(new Context() {

            @Override
            public ClassInfo getClassInfo(String className) {
                try {
                    return ClassInfo.fromClass(Class.forName(className.replace('/', '.'), false, getClass().getClassLoader()));
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public byte[] readFile(String path) {
                try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
                    return stream != null ? stream.readAllBytes() : null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });

        // Collect close-ables to close later
        Map<ZipOutputStream, List<ZipWriteJob>> writeJobMap = new HashMap<>();

        // Delete output directory
        Path outDirectory = getOutputDirectory().getAsFile().get().toPath();
        getProject().delete(outDirectory);

        Path otherRoot = outDirectory.resolve("chasm-added-files");

        // Process classpath
        for (File file : classpath) {
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
                        processor.addClass(bytes, new Metadata());
                    } else {
                        if (relative.startsWith("org/quiltmc/chasm/transformers/") && fileName.endsWith(".chasm")) {
                            // Add transformers to the processor
                            Node parsed = Node.parse(sourcePath);
                            Transformer transformer = new ChasmLangTransformer(relative.toString(), parsed, processor.getContext());
                            processor.addTransformer(transformer);
                        }

                        // Copy remaining files directly (including transformers)
                        Path targetPath = otherRoot.resolve(relative);
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath);
                    }
                }
            } else if (file.getName().endsWith(".jar")) {
                // Open jar
                // TODO: Use target JDK
                JarFile jarFile = new JarFile(file, false, ZipFile.OPEN_READ, Runtime.version());

                // Create target jar/zip
                Path targetPath = outDirectory.resolve(file.getName());
                Files.createDirectories(targetPath.getParent());
                ZipOutputStream zipOutputStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(targetPath)));
                writeJobMap.put(zipOutputStream, new ArrayList<>());

                Iterator<JarEntry> jarIterator = jarFile.versionedStream().iterator();
                while (jarIterator.hasNext()) {
                    ZipEntry entry = jarIterator.next();
                    if (entry.isDirectory()) {
                        continue;
                    }

                    String fileName = entry.getName();
                    byte[] bytes = jarFile.getInputStream(entry).readAllBytes();

                    if (fileName.endsWith(".class")) {

                        // Add class files to the processor
                        Metadata metadata = new Metadata();
                        metadata.put(TargetZipMetadata.class, new TargetZipMetadata(zipOutputStream));
                        processor.addClass(bytes, metadata);
                    } else {
                        if (fileName.startsWith("org/quiltmc/chasm/transformers/") && fileName.endsWith(".chasm")) {
                            // Add transformers to the processor
                            Node parsed = Node.parse(new String(bytes));
                            Transformer transformer = new ChasmLangTransformer(fileName, parsed, processor.getContext());
                            processor.addTransformer(transformer);
                        }

                        // Copy remaining files directly (including transformers)
                        writeJobMap.get(zipOutputStream).add(outputStream -> {
                            outputStream.putNextEntry(entry);
                            outputStream.write(bytes);
                        });
                    }
                }
            } else {
                // TODO: Zip and unsupported entries
                // Unsupported
                continue;
            }
        }

        System.out.println("Unnecessary Zip jobs: " + writeJobMap.values().stream().map(List::size).reduce(Integer::sum));

        // Add explicitly specified transformers
        for (File transformerFile : transformers) {
            Node parsed = Node.parse(transformerFile.toPath());
            Transformer transformer = new ChasmLangTransformer(transformerFile.toString(), parsed, processor.getContext());
            processor.addTransformer(transformer);
        }

        // Process the classes
        List<ClassResult> classes = processor.process();

        // Write the resulting classes
        for (ClassResult classData : classes) {
            TargetZipMetadata targetMetadata = classData.getMetadata().get(TargetZipMetadata.class);
            ClassReader classReader = new ClassReader(classData.getClassBytes());
            String path = classReader.getClassName() + ".class";

            if (targetMetadata == null) {
                Path targetPath = otherRoot.resolve(path);
                Files.createDirectories(targetPath.getParent());
                Files.write(targetPath, classData.getClassBytes());
            } else {
                writeJobMap.get(targetMetadata.outputStream()).add(outputStream -> {
                    outputStream.putNextEntry(new ZipEntry(path));
                    outputStream.write(classData.getClassBytes());
                });
            }
        }

        System.out.println("Total Zip jobs: " + writeJobMap.values().stream().map(List::size).reduce(Integer::sum));

        writeJobMap.forEach((key, value) -> {
            try {
                for (ZipWriteJob zipWriteJob : value) {
                    zipWriteJob.write(key);
                }
                key.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    record TargetZipMetadata(ZipOutputStream outputStream) { }

    interface ZipWriteJob {
        void write(ZipOutputStream outputStream) throws IOException;
    }
}
