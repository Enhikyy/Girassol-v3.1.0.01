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
        // v4.1: todos os materiais para Vulkan + OpenGL ES 3.0. girassol_world.mat é a fonte ÚNICA
        // do mundo: gera o PBR lit e, com templates/define, o fallback unlit de GL por software.
        val litWorld = listOf("-TNAME=girassol_world", "-TSHADING=lit", "-TREFLECTIONS=screenspace",
            "-TSPECULAR_AO=simple", "-TMULTI_BOUNCE=true")
        val fallbackWorld = listOf("-TNAME=girassol_world_fallback", "-TSHADING=unlit", "-TREFLECTIONS=default",
            "-TSPECULAR_AO=none", "-TMULTI_BOUNCE=false", "-DGIRASSOL_SOFTWARE_GL")
        fun run(mat: File, outName: String, extra: List<String>) {
            exec.exec {
                commandLine(listOf(tool.absolutePath, "-a", "opengl", "-a", "vulkan", "-p", "mobile") + extra +
                    listOf("-o", out.resolve("$outName.filamat").absolutePath, mat.absolutePath))
            }
        }
        sourceDir.get().asFile.listFiles { f -> f.extension == "mat" }!!.sortedBy { it.name }.forEach { mat ->
            if (mat.nameWithoutExtension == "girassol_world") {
                run(mat, "girassol_world", litWorld)
                run(mat, "girassol_world_fallback", fallbackWorld)
            } else {
                run(mat, mat.nameWithoutExtension, emptyList())
            }
        }
    }
}

val llamaDirOverride = providers.gradleProperty("girassol.llamaDir").orNull
val llamaDisabled = providers.gradleProperty("girassol.noLlama").map { it.toBoolean() }.getOrElse(false)
val llamaDir: File = llamaDirOverride?.let { file(it) } ?: thirdParty.get().dir("llama/llama.cpp-$llamaVersion").asFile

val downloadLlama by tasks.registering(DownloadVerified::class) {
    enabled = llamaDirOverride == null && !llamaDisabled
    url.set("https://github.com/ggml-org/llama.cpp/archive/refs/tags/v$llamaVersion.tar.gz")
    sha256.set(llamaSha256)
    dest.set(thirdParty.map { it.file("downloads/llama.cpp-$llamaVersion.tar.gz") })
}
val prepareLlama by tasks.registering(Copy::class) {
    enabled = llamaDirOverride == null && !llamaDisabled
    dependsOn(downloadLlama)
    from(downloadLlama.flatMap { it.dest }.map { tarTree(resources.gzip(it.asFile)) })
    into(thirdParty.map { it.dir("llama") })
}

// KleidiAI (micro-kernels Arm para ggml-cpu nas variantes dotprod/i8mm). Mesma versão e
// hash MD5 fixados pelo llama.cpp 1.0.0; aqui verificamos por SHA-256 e passamos o
// diretório local ao CMake (o build nativo nunca acessa a rede).
val kleidiaiVersion = "1.32.0"
val kleidiaiSha256 = "a48d373e7c5d721a0aecbf5ddaf0bdf245bc8064e6b84086f13db0e102b553a6"
val kleidiaiDirOverride = providers.gradleProperty("girassol.kleidiaiDir").orNull
val kleidiaiDir: File = kleidiaiDirOverride?.let { file(it) } ?: thirdParty.get().dir("kleidiai/kleidiai-v$kleidiaiVersion").asFile
val downloadKleidiai by tasks.registering(DownloadVerified::class) {
    enabled = kleidiaiDirOverride == null && !llamaDisabled
    url.set("https://github.com/ARM-software/kleidiai/releases/download/v$kleidiaiVersion/kleidiai-v$kleidiaiVersion-src.tar.gz")
    sha256.set(kleidiaiSha256)
    dest.set(thirdParty.map { it.file("downloads/kleidiai-v$kleidiaiVersion-src.tar.gz") })
}
val prepareKleidiai by tasks.registering(Copy::class) {
    enabled = kleidiaiDirOverride == null && !llamaDisabled
    dependsOn(downloadKleidiai)
    from(downloadKleidiai.flatMap { it.dest }.map { tarTree(resources.gzip(it.asFile)) })
    into(thirdParty.map { it.dir("kleidiai") })
}

val matcOverride = providers.gradleProperty("girassol.matc").orNull
val downloadFilamentTools by tasks.registering(DownloadVerified::class) {
    enabled = matcOverride == null
    url.set("https://github.com/google/filament/releases/download/v$filamentVersion/filament-v$filamentVersion-linux.tgz")
    sha256.set(filamentToolsSha256)
    dest.set(thirdParty.map { it.file("downloads/filament-v$filamentVersion-linux.tgz") })
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

// ----------------------------------------------------------------------------
// v3.5 — llguidance (Rust) opcional: -Pgirassol.llguidance=auto|on|off (padrão auto).
// auto: compila o crate para aarch64-linux-android se `cargo` + o target do rustup existirem;
//       se não existirem ou o cargo falhar, o build segue com a GBNF estrita (fallback).
// on:   falha o build se o crate não compilar.  off: nunca tenta.
// ----------------------------------------------------------------------------
val llguidanceMode = providers.gradleProperty("girassol.llguidance").getOrElse("auto").lowercase()
val llguidanceCommit = "d795912fedc7d393de740177ea9ea761e7905774" // v1.0.1 (mesmo do llama.cpp 1.0.0)
val llguidanceSha256 = "a37b4bdc1992ba8fb316753199f86095178fa2e11df2ccb4e6540007cbf99fd0"
val llguidanceOut = thirdParty.map { it.dir("llguidance/out-aarch64-linux-android") }
val downloadLlguidance by tasks.registering(DownloadVerified::class) {
    enabled = llguidanceMode != "off" && !llamaDisabled
    url.set("https://github.com/guidance-ai/llguidance/archive/$llguidanceCommit.tar.gz")
    sha256.set(llguidanceSha256)
    dest.set(thirdParty.map { it.file("downloads/llguidance-$llguidanceCommit.tar.gz") })
}
val prepareLlguidance by tasks.registering(Copy::class) {
    enabled = llguidanceMode != "off" && !llamaDisabled
    dependsOn(downloadLlguidance)
    from(downloadLlguidance.flatMap { it.dest }.map { tarTree(resources.gzip(it.asFile)) })
    into(thirdParty.map { it.dir("llguidance/src") })
}

/** cargo build --target aarch64-linux-android do crate llguidance com o clang do NDK. */
abstract class CargoBuildLlguidance : DefaultTask() {
    @get:Input abstract val mode: Property<String>
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:Internal abstract val sourceDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty
    @get:javax.inject.Inject abstract val exec: org.gradle.process.ExecOperations

    @TaskAction
    fun run() {
        val out = outDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val strict = mode.get() == "on"
        fun skip(msg: String) {
            check(!strict) { "llguidance obrigatório (girassol.llguidance=on): $msg" }
            logger.warn("Girassol: llguidance desativado ($msg) — fallback GBNF estrita")
        }
        val home = System.getProperty("user.home")
        val cargo = listOf(System.getenv("CARGO_HOME")?.let { "$it/bin/cargo" }, "$home/.cargo/bin/cargo", "/usr/local/bin/cargo", "/usr/bin/cargo")
            .filterNotNull().map(::File).firstOrNull { it.canExecute() }
        if (cargo == null) return skip("cargo não encontrado (instale o rustup)")
        val src = sourceDir.get().asFile.listFiles { f -> f.isDirectory && f.name.startsWith("llguidance-") }?.firstOrNull()
            ?: return skip("código-fonte ausente")
        val host = when {
            System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
            System.getProperty("os.name").lowercase().contains("win") -> "windows-x86_64"
            else -> "linux-x86_64"
        }
        val bin = File(ndkDir.get(), "toolchains/llvm/prebuilt/$host/bin")
        val cc = File(bin, "aarch64-linux-android${apiLevel.get()}-clang")
        if (!cc.exists()) return skip("clang do NDK ausente em $bin")
        val res = exec.exec {
            workingDir = src
            isIgnoreExitValue = true
            environment("CC_aarch64_linux_android", cc.absolutePath)
            environment("AR_aarch64_linux_android", File(bin, "llvm-ar").absolutePath)
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", cc.absolutePath)
            environment("CARGO_PROFILE_RELEASE_PANIC", "abort")
            commandLine(cargo.absolutePath, "build", "--release", "--locked", "--target", "aarch64-linux-android", "--package", "llguidance")
        }
        if (res.exitValue != 0) return skip("cargo build saiu com ${res.exitValue}")
        val lib = File(src, "target/aarch64-linux-android/release/libllguidance.a")
        if (!lib.exists()) return skip("libllguidance.a não gerada")
        lib.copyTo(File(out, "libllguidance.a"), overwrite = true)
        File(src, "parser/llguidance.h").copyTo(File(out, "llguidance.h"), overwrite = true)
    }
}
val buildLlguidance by tasks.registering(CargoBuildLlguidance::class) {
    enabled = llguidanceMode != "off" && !llamaDisabled
    dependsOn(prepareLlguidance)
    mode.set(llguidanceMode)
    ndkDir.set(providers.provider { android.ndkDirectory.absolutePath })
    apiLevel.set(33)
    sourceDir.set(thirdParty.map { it.dir("llguidance/src") })
    outDir.set(llguidanceOut)
}

// ----------------------------------------------------------------------------
// v3.6 — "Adão e Eva": NENHUM modelo GGUF é embutido. O mundo nasce vazio (0 cidadãos) e o
// usuário o povoa importando os próprios .gguf (1 modelo = 1 cidadão fundador).
//   -Pgirassol.embedModels=true|false (padrão false): embute só o Sherpa-ONNX (SenseVoice +
//   Pocket-TTS), verificado por SHA-256; texturas KTX2 e o runtime llama.cpp vão sempre.
//   -Pgirassol.distribution=direto|play (padrão direto)
// direto: Sherpa nos assets do módulo base do APK arm64-v8a.
// play:   Sherpa no asset pack install-time pack_rapido do .aab.
// ----------------------------------------------------------------------------
val embedModels = providers.gradleProperty("girassol.embedModels").map { it.toBoolean() }.getOrElse(false)
val distribution = providers.gradleProperty("girassol.distribution").getOrElse("direto").lowercase()
check(distribution == "direto" || distribution == "play") { "girassol.distribution deve ser direto ou play" }

data class VerifiedAsset(val name: String, val url: String, val sha256: String, val bytes: Long)
val fastModel = VerifiedAsset("girassol-rapido-qwen2.5-0.5b-instruct-q4_0.gguf",
    "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_0.gguf",
    "7671c0c304e6ce5a7fc577bcb12aba01e2c155cc2efd29b2213c95b18edaf6ed", 428_730_208L)
val deepModel = VerifiedAsset("girassol-profundo-phi-3.5-mini-instruct-q4_0.gguf",
    "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_0.gguf",
    "6e0a4c93fe5db9408348cd2cbd15eee6f448efe6f91255a208c2d28701259b1d", 2_182_468_896L)
val senseVoice = VerifiedAsset("sense-voice.tar.bz2",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
    "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63", 165_783_878L)
val pocketTts = VerifiedAsset("pocket-tts.tar.bz2",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2",
    "f4da7c7bd465f7b91e89191487fe90504396a51d6f0bcf94d0787633e2657293", 98_337_865L)

fun downloadTask(id: String, a: VerifiedAsset) = tasks.register<DownloadVerified>("download$id") {
    enabled = embedModels
    url.set(a.url); sha256.set(a.sha256)
    dest.set(thirdParty.map { it.file("models/${a.name}") })
}
// modelos GGUF de fábrica desativados na v3.6 (tarefas mantidas só para builds experimentais)
val downloadFastModel = downloadTask("FastModel", fastModel).also { t -> t.configure { enabled = false } }
val downloadDeepModel = downloadTask("DeepModel", deepModel).also { t -> t.configure { enabled = false } }
val downloadSenseVoice = downloadTask("SenseVoice", senseVoice)
val downloadPocketTts = downloadTask("PocketTts", pocketTts)

/** Monta a árvore de assets de modelos (models/ + sherpa/) com manifesto JSON verificável. */
abstract class AssembleModelAssets : DefaultTask() {
    @get:InputFiles abstract val sourceArchives: ConfigurableFileCollection
    @get:Input abstract val fastName: Property<String>
    @get:Input abstract val fastSha: Property<String>
    @get:Input abstract val includeFast: Property<Boolean>
    @get:Input abstract val includeSherpa: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val fs: org.gradle.api.file.FileSystemOperations
    @get:javax.inject.Inject abstract val archives: org.gradle.api.file.ArchiveOperations

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        val files = sourceArchives.files.associateBy { it.name }
        if (includeFast.get()) {
            val m = File(out, "models").apply { mkdirs() }
            files.getValue(fastName.get()).copyTo(File(m, fastName.get()))
            File(m, "rapido.json").writeText("""{"file":"${fastName.get()}","sha256":"${fastSha.get()}","parts":["${fastName.get()}"]}""")
        }
        if (includeSherpa.get()) {
            fs.copy {
                from(archives.tarTree(archives.bzip2(files.getValue("sense-voice.tar.bz2")))) { include("**/model.int8.onnx", "**/tokens.txt") }
                eachFile { path = "sherpa/sense-voice/" + name }
                includeEmptyDirs = false
                into(out)
            }
            fs.copy {
                from(archives.tarTree(archives.bzip2(files.getValue("pocket-tts.tar.bz2")))) {
                    include("**/*.onnx", "**/vocab.json", "**/token_scores.json", "**/test_wavs/*.wav")
                }
                eachFile { path = "sherpa/pocket-tts/" + name }
                includeEmptyDirs = false
                into(out)
            }
        }
    }
}

/** Divide o modelo profundo em N partes (uma por asset pack) + manifesto com SHA-256. */
abstract class SplitDeepModel : DefaultTask() {
    @get:InputFile abstract val model: RegularFileProperty
    @get:Input abstract val modelName: Property<String>
    @get:Input abstract val sha: Property<String>
    @get:Input abstract val packDirs: ListProperty<String>
    @get:OutputDirectories abstract val partDirs: ConfigurableFileCollection

    @TaskAction
    fun run() {
        val src = model.get().asFile
        val dirs = packDirs.get().map { File(it, "models") }
        dirs.forEach { it.deleteRecursively(); it.mkdirs() }
        val partSize = (src.length() + dirs.size - 1) / dirs.size
        val names = dirs.indices.map { "${modelName.get()}.part$it" }
        src.inputStream().buffered(1 shl 20).use { ins ->
            val buf = ByteArray(1 shl 20)
            dirs.forEachIndexed { i, d ->
                File(d, names[i]).outputStream().buffered(1 shl 20).use { o ->
                    var left = partSize
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        o.write(buf, 0, n); left -= n
                    }
                }
            }
        }
        File(dirs.first(), "profundo.json").writeText(
            """{"file":"${modelName.get()}","sha256":"${sha.get()}","bytes":${src.length()},"parts":[${names.joinToString(",") { "\"$it\"" }}]}""")
    }
}

val packProfundoDirs = listOf("pack_profundo_a", "pack_profundo_b").map { rootProject.layout.projectDirectory.dir("$it/src/main/assets").asFile.absolutePath }

val assembleModelAssets by tasks.registering(AssembleModelAssets::class) {
    enabled = embedModels
    sourceArchives.from(downloadSenseVoice.flatMap { it.dest }, downloadPocketTts.flatMap { it.dest })
    fastName.set(fastModel.name); fastSha.set(fastModel.sha256)
    includeFast.set(false); includeSherpa.set(true)
    outputDir.set(if (distribution == "play") rootProject.layout.projectDirectory.dir("pack_rapido/src/main/assets")
                  else layout.buildDirectory.dir("generated/girassolModels").get())
}
val splitDeepModel by tasks.registering(SplitDeepModel::class) {
    enabled = false
    model.set(downloadDeepModel.flatMap { it.dest })
    modelName.set(deepModel.name); sha.set(deepModel.sha256)
    packDirs.set(packProfundoDirs)
    partDirs.from(packProfundoDirs.map { File(it, "models") })
}
val prepareAssetPacks by tasks.registering {
    enabled = embedModels && distribution == "play"
    dependsOn(assembleModelAssets)
}

val sherpaVersion = "2.0.2"
val downloadSherpaAar by tasks.registering(DownloadVerified::class) {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
    sha256.set("6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e")
    dest.set(thirdParty.map { it.file("downloads/sherpa-onnx-$sherpaVersion.aar") })
}

// Backends ggml-cpu dinâmicos (dlopen): o CMake os copia para cá após o build e cada build
// type os empacota como jniLibs (uma cópia por ABI; nunca duplicados estaticamente).
fun ggmlBackendsDir(buildType: String): String =
    layout.buildDirectory.dir("generated/ggmlBackends/$buildType").get().asFile.absolutePath

// ABIs (splits): padrão arm64-v8a; o CI acrescenta x86_64 no debug para o emulador.
val abiOverride = providers.gradleProperty("girassol.abis").orNull?.split(",")?.map { it.trim() }

android {
    namespace = "com.enhikyy.girassol"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.enhikyy.girassol"
        minSdk = 33
        targetSdk = 35
        versionCode = 3800000
        versionName = "3.8.0.00"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FILAMENT_VERSION", "\"$filamentVersion\"")
        buildConfigField("String", "DISTRIBUTION", "\"$distribution\"")
        buildConfigField("boolean", "EMBEDDED_MODELS", embedModels.toString())
        buildConfigField("String", "FAST_MODEL_SHA256", "\"${fastModel.sha256}\"")
        buildConfigField("String", "DEEP_MODEL_SHA256", "\"${deepModel.sha256}\"")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += listOf("-DANDROID_STL=c++_shared")
                if (!llamaDisabled) {
                    arguments += "-DGIRASSOL_LLAMA_DIR=${llamaDir.absolutePath}"
                    arguments += "-DGIRASSOL_KLEIDIAI_DIR=${kleidiaiDir.absolutePath}"
                    if (llguidanceMode != "off") arguments += "-DGIRASSOL_LLGUIDANCE_DIR=${llguidanceOut.get().asFile.absolutePath}"
                }
                // Núcleo determinístico + motor LLM único. As dependências (libllama.so,
                // libggml*.so e os backends de CPU dinâmicos) são geradas pelo CMake junto.
                targets += listOf("girassol_core", "girassol_llm")
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
            externalNativeBuild { cmake { arguments += "-DGIRASSOL_BACKEND_OUT=${ggmlBackendsDir("release")}" } }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            externalNativeBuild { cmake { arguments += "-DGIRASSOL_BACKEND_OUT=${ggmlBackendsDir("debug")}" } }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Um APK por ABI (sem APK universal). Padrão: só arm64-v8a (alvo real). O CI passa
    // -Pgirassol.abis=arm64-v8a,x86_64 nas tarefas debug para gerar também o APK do emulador.
    splits {
        abi {
            isEnable = true
            reset()
            include(*((abiOverride ?: listOf("arm64-v8a")).toTypedArray()))
            isUniversalApk = false
        }
    }

    // .aab (distribuição play): modelos em asset packs install-time.
    if (embedModels && distribution == "play") assetPacks += listOf(":pack_rapido")
    // GGUF/ONNX nunca comprimidos (cópia direta do AssetManager, sem inflar 2 GB na instalação)
    androidResources { noCompress += listOf("gguf", "onnx", "part", "ktx2") }

    sourceSets {
        getByName("debug").jniLibs.srcDir(ggmlBackendsDir("debug"))
        getByName("release").jniLibs.srcDir(ggmlBackendsDir("release"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        // v3.8: Robolectric (perfis 4/8/12 GB com ActivityManager sombreado)
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(compileFilamentMaterials, CompileFilamentMaterials::outputDir)
        if (embedModels && distribution == "direto") {
            variant.sources.assets?.addGeneratedSourceDirectory(assembleModelAssets, AssembleModelAssets::outputDir)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("generateJsonModel")) dependsOn(prepareLlama, prepareKleidiai, buildLlguidance)
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
    // Sherpa-ONNX (SenseVoice ASR + Pocket-TTS): AAR oficial do GitHub, verificado por SHA-256.
    implementation(files(downloadSherpaAar.flatMap { it.dest }))
    // v3.7: extração .tar.bz2 dos pacotes de voz opcionais (Piper VITS / Whisper) nos perfis 8/12 GB
    implementation(libs.commons.compress)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // v3.9: testes de UI Compose na JVM (Robolectric) — validados no CI antes de gerar APK/AAB
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

// Os jniLibs gerados pelo CMake precisam existir antes da fusão das pastas de bibliotecas.
listOf("Debug", "Release").forEach { bt ->
    tasks.matching { it.name == "merge${bt}JniLibFolders" }.configureEach { dependsOn("externalNativeBuild$bt") }
}

// v3.7: testes unitários do app executam o WorldTick C++20 compilado para o host.
evaluationDependsOn(":core")
tasks.withType<Test>().configureEach {
    dependsOn(":core:buildWorldTickHost")
    systemProperty("girassol.worldtick.lib", project(":core").extra["worldTickHostLib"] as String)
}
