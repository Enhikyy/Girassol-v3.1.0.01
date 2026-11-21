plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.cbor)
    testImplementation(libs.junit)
}

// v3.7: o WorldTick roda em C++20 (app/src/main/cpp/world). Os testes JVM carregam a mesma
// fonte compilada para o host (CMake + JNI do JDK 17) — não existe caminho Kotlin alternativo.
val hostNativeDir = layout.buildDirectory.dir("native-host")
val buildWorldTickHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Compila libgirassol_worldtick_host (WorldTick C++20) para os testes JVM"
    val src = file("native")
    val cpp = rootProject.file("app/src/main/cpp")
    inputs.dir(src)
    inputs.dir(cpp.resolve("world"))
    inputs.dir(cpp.resolve("common"))
    inputs.file(cpp.resolve("jni/world_tick_jni.cpp"))
    outputs.dir(hostNativeDir)
    val out = hostNativeDir.get().asFile
    val javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
        .get().metadata.installationPath.asFile.absolutePath
    environment("JAVA_HOME", javaHome)
    commandLine("bash", "-c",
        "cmake -S '${src.absolutePath}' -B '${out.absolutePath}' -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME='$javaHome' >/dev/null && " +
            "cmake --build '${out.absolutePath}' -j 4 >/dev/null")
}

val worldTickHostLib: String = hostNativeDir.get().asFile.resolve(
    if (System.getProperty("os.name").lowercase().contains("mac")) "libgirassol_worldtick_host.dylib" else "libgirassol_worldtick_host.so"
).absolutePath
extra["worldTickHostLib"] = worldTickHostLib

tasks.test {
    dependsOn(buildWorldTickHost)
    systemProperty("girassol.worldtick.lib", worldTickHostLib)
    maxHeapSize = "1g"
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
