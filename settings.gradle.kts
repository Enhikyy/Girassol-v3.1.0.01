pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Girassol"
include(":core", ":app")
// v3.5: asset packs install-time do .aab (modelo rápido + Sherpa; modelo profundo em 2 partes)
include(":pack_rapido", ":pack_profundo_a", ":pack_profundo_b")
