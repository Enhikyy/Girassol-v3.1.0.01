// Asset pack install-time do .aab (distribuição play). Os assets são gerados pela tarefa
// :app:prepareAssetPacks (modelos baixados e verificados por SHA-256) — nunca versionados.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("pack_profundo_a")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

val embed = providers.gradleProperty("girassol.embedModels").map { it.toBoolean() }.getOrElse(false)
val play = providers.gradleProperty("girassol.distribution").getOrElse("direto").lowercase() == "play"
if (embed && play) {
    tasks.configureEach { if (name != "clean") dependsOn(":app:prepareAssetPacks") }
}
