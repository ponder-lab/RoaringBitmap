plugins {
    id("me.champeau.gradle.jmh") version "0.5.0"
    id("com.github.johnrengelman.shadow") version "6.0.0"
}

buildscript {
  repositories {
    maven {
      url = uri("https://plugins.gradle.org/m2/")
    }
  }
  dependencies {
    classpath("org.javamodularity:moduleplugin:1.8.12")
  }
}

// Compiles solely module-info.java with Java 9 compatibility and all other
// files with Java 8 compatibility
apply(plugin = "org.javamodularity.moduleplugin")

// Work around
// https://github.com/java9-modularity/gradle-modules-plugin/issues/220
// by excluding module-info.class from non-executable JARs.
tasks.named<Jar>("javadocJar") {
    exclude("module-info.class")
}

tasks.named<Jar>("sourcesJar") {
    exclude("module-info.class")
}

// Unset Java 8 release applied from root project to allow modularity plugin to
// control the class file versions.
tasks.named<JavaCompile>("compileJava") {
    options.release.set(null as Int?)
}

configure<org.javamodularity.moduleplugin.extensions.ModularityExtension> {
    mixedJavaRelease(8)
}

// Unset Java 8 release applied from root project to allow modularity plugin to
// control the class file versions.
tasks.named<JavaCompile>("compileModuleInfoJava") {
    options.release.set(null as Int?)
}

tasks.test {
    extensions.configure(org.javamodularity.moduleplugin.extensions.TestModuleOptions::class) {
        // Avoid modules in tests so we can test against Java/JDK 8.
        setRunOnClasspath(true)
    }
}

tasks.compileTestJava {
    extensions.configure(org.javamodularity.moduleplugin.extensions.CompileTestModuleOptions::class) {
        // Avoid modules in tests so we can test against Java/JDK 8.
        setCompileOnClasspath(true)
    }
}

val deps: Map<String, String> by extra

dependencies {
    implementation("org.junit.jupiter:junit-jupiter-api:${deps["jupiter"]}")
    implementation("org.junit.jupiter:junit-jupiter-params:${deps["jupiter"]}")
    runtimeOnly("org.junit.jupiter:junit-jupiter-engine:${deps["jupiter"]}")
    implementation("org.openjdk.jmh:jmh-core:1.35")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.35")
    implementation("com.google.guava:guava:${deps["guava"]}")
    implementation("org.apache.commons:commons-lang3:${deps["commons-lang"]}")
    implementation("com.esotericsoftware:kryo:5.0.0-RC6")
    implementation("com.fasterxml.jackson.core", "jackson-databind", "2.10.3")
    implementation("org.assertj:assertj-core:3.23.1")
    implementation("org.openjdk.jol:jol-core:0.16")
    jmhImplementation("org.junit.jupiter:junit-jupiter-api:${deps["jupiter"]}")
    jmhRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${deps["jupiter"]}")
}

sourceSets {
    create("java11") {
        java {
            srcDir("src/java11/main")
        }
    }
}

tasks.named<JavaCompile>("compileJava11Java") {
    // Arrays.equals exists since JDK9, but we make it available for 11+ so we can test the shim by using Java 11
    // and the old way by using Java 10, which will compile the new code but not use it..
    options.release.set(9)
}

tasks.named<Jar>("jar") {
    into("META-INF/versions/11") {
        from(sourceSets.named("java11").get().output)
    }
    manifest.attributes(
            Pair("Multi-Release", "true")
    )

    // normally jar is just main classes but we also have another sourceset
    dependsOn(tasks.named("compileJava11Java"))
}

tasks.test {
    systemProperty("kryo.unsafe", "false")
    mustRunAfter(tasks.checkstyleMain)
    useJUnitPlatform()
    failFast = true

    // Define the memory requirements of tests, to prevent issues in CI while OK locally
    minHeapSize = "2G"
    maxHeapSize = "2G"

    testLogging {
        // We exclude 'passed' events
        events( "skipped", "failed")
        showStackTraces = true
        showExceptions = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // Helps investigating OOM. But too verbose to be activated by default
        // showStandardStreams = true
    }
}

jmh {
    sourceSets {
        getByName("main") {
            java.srcDir("src/test/java")
        }
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("all")

    from(sourceSets["main"].output)
    from(sourceSets["test"].output)

    manifest {
        attributes(
            "Main-Class" to "org.openjdk.jmh.Main"
        )
    }

    configurations = listOf(project.configurations.getByName("runtimeClasspath"))
}

tasks.register<JavaExec>("runJmhShadow") {
    group = "benchmark"
    description = "Runs JMH benchmarks from the shadow JAR."

    classpath = files(tasks.named("shadowJar"))

    mainClass.set("org.openjdk.jmh.Main")
    jvmArgs = listOf("-Xms2G", "-Xmx2G")
}
