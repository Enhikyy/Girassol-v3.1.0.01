import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ----------------------------------------------------------------------------
// Dependências nativas de terceiros, baixadas e verificadas por SHA-256.
// ----------------------------------------------------------------------------
val llamaVersion = "1.0.0"
val llamaSha256 = "3f5f750e36326aea4d65c7623e7d597a33e395d65f4d76e5e0532171f252532b"
val filamentVersion = libs.versions.filament.get()
val filamentToolsSha256 = "cff34ebbeddcef0df903c4318c0540b0da843e01262ff2e896692a6ee2b5c925"
val thirdParty = rootProject.layout.buildDirectory.dir("third_party")

abstract class DownloadVerified : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val dest: RegularFileProperty

    @TaskAction
    fun run() {
        val out = dest.get().asFile
        fun digest(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins -> val buf = ByteArray(1 shl 16); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (out.exists() && digest(out) == sha256.get()) return
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, out.name + ".part")
        URI(url.get()).toURL().openStream().use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        val got = digest(tmp)
        check(got == sha256.get()) { "SHA-256 inesperado para ${url.get()}: $got" }
        tmp.renameTo(out)
    }
}

/** Compila os materiais Filament (.mat → .filamat) com o matc do SDK do Filament. */
abstract class CompileFilamentMaterials : DefaultTask() {
    @get:InputDirectory abstract val sourceDir: DirectoryProperty
    @get:InputFile abstract val matc: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val exec: org.gradle.process.ExecOperations

    @TaskAction
    fun compile() {
        val out = outputDir.get().asFile.resolve("materials")
        out.deleteRecursively(); out.mkdirs()
        val tool = matc.get().asFile
        tool.setExecutable(true)
        sourceDir.get().asFile.listFiles { f -> f.extension == "mat" }!!.sortedBy { it.name }.forEach { mat ->
            exec.exec {
                commandLine(tool.absolutePath, "-a", "opengl", "-p", "mobile", "-o",
                    out.resolve(mat.nameWithoutExtension + ".filamat").absolutePath, mat.absolutePath)
            }
        }
    }
}

val llamaDirOverride = providers.gradleProperty("girassol.llamaDir").orNull
val llamaDisabled = providers.gradleProperty("girassol.noLlama").map { it.toBoolean() }.getOrElse(false)
val llamaDir: File = llamaDirOverride?.let { file(it) } ?: thirdParty.get().dir("llama.cpp-$llamaVersion").asFile

val downloadLlama by tasks.registering(DownloadVerified::class) {
    enabled = llamaDirOverride == null && !llamaDisabled
    url.set("https://github.com/ggml-org/llama.cpp/archive/refs/tags/v$llamaVersion.tar.gz")
    sha256.set(llamaSha256)
    dest.set(thirdParty.map { it.file("llama.cpp-$llamaVersion.tar.gz") })
}
val prepareLlama by tasks.registering(Copy::class) {
    enabled = llamaDirOverride == null && !llamaDisabled
    dependsOn(downloadLlama)
    from(downloadLlama.flatMap { it.dest }.map { tarTree(resources.gzip(it.asFile)) })
    into(thirdParty)
}

val matcOverride = providers.gradleProperty("girassol.matc").orNull
val downloadFilamentTools by tasks.registering(DownloadVerified::class) {
    enabled = matcOverride == null
    url.set("https://github.com/google/filament/releases/download/v$filamentVersion/filament-v$filamentVersion-linux.tgz")
    sha256.set(filamentToolsSha256)
    dest.set(thirdParty.map { it.file("filament-v$filamentVersion-linux.tgz") })
}
val prepareMatc by tasks.registering(Copy::class) {
    enabled = matcOverride == null
    dependsOn(downloadFilamentTools)
    from(downloadFilamentTools.flatMap { it.dest }.map { tarTree(resources.gzip(it.asFile)) }) {
        include("filament/bin/matc")
    }
    into(thirdParty.map { it.dir("filament-$filamentVersion") })
}
val compileFilamentMaterials by tasks.registering(CompileFilamentMaterials::class) {
    dependsOn(prepareMatc)
    sourceDir.set(layout.projectDirectory.dir("src/main/materials"))
    matc.set(matcOverride?.let { layout.projectDirectory.file(it) }
        ?: thirdParty.get().file("filament-$filamentVersion/filament/bin/matc"))
    outputDir.set(layout.buildDirectory.dir("generated/filamat"))
}

// ABIs: release = arm64-v8a (alvo real); debug inclui x86_64 para o emulador do CI.
val abiOverride = providers.gradleProperty("girassol.abis").orNull?.split(",")?.map { it.trim() }

android {
    namespace = "com.enhikyy.girassol"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.enhikyy.girassol"
        minSdk = 33
        targetSdk = 35
        versionCode = 3200000
        versionName = "3.2.0.00"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += listOf("-DANDROID_STL=c++_shared")
                if (!llamaDisabled) arguments += "-DGIRASSOL_LLAMA_DIR=${llamaDir.absolutePath}"
                targets += "girassol_core"
            }
        }
    }

    // Assinatura de release: usa os segredos do CI quando existirem; caso contrário
    // o release é assinado com a chave de debug (APENAS para testes, não para a Play Store).
    val releaseStore = System.getenv("GIRASSOL_KEYSTORE_PATH")
    signingConfigs {
        if (releaseStore != null && file(releaseStore).exists()) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("GIRASSOL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GIRASSOL_KEY_ALIAS")
                keyPassword = System.getenv("GIRASSOL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            ndk { abiFilters += abiOverride ?: listOf("arm64-v8a") }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            ndk { abiFilters += abiOverride ?: listOf("arm64-v8a", "x86_64") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(compileFilamentMaterials, CompileFilamentMaterials::outputDir)
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("generateJsonModel")) dependsOn(prepareLlama)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.filament.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
