# Chasm Gradle Plugin

This is a work-in-progress Gradle plugin for [Chasm](https://github.com/QuiltMC/chasm).
It transforms compile and runtime class paths given transformer files in dependencies and resources.

## Using the plugin

At the top of your `build.gradle`:
```groovy
plugins {
    id 'java'
    id 'org.quiltmc.chasm' version '0.0.1-SNAPSHOT'
}
```

At the very top of your `settings.gradle`:
```groovy
pluginManagement {
    repositories {
        mavenCentral()
        maven {
            name = "Quilt Snapshot Maven"
            url = "https://maven.quiltmc.org/repository/snapshot"
        }
    }
}
```

## Adding transformers
Transformers must be located in your resources or the classpath in `org/quiltmc/chasm/transformers/`.
They must have the file extension `.chasm`.
You should fully qualify the transformer path, i.e. `org/quiltmc/chasm/org/example/mod/modify_class.chasm`.
This prevents name conflicts with other transformers.