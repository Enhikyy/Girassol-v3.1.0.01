# Ponte JNI: nomes dos métodos nativos precisam ser preservados.
-keep class com.enhikyy.girassol.nativebridge.GeaNativeBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
# kotlinx.serialization (snapshots CBOR)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-keep class com.enhikyy.girassol.core.** { *; }

# ---- v3.2: JNI, Filament e motor llama.cpp ----
# Filament: classes/membros acessados a partir do código nativo (ponteiros nativos em campos long).
-keep class com.google.android.filament.** { *; }
-keepclassmembers class com.google.android.filament.** { long mNativeObject; native <methods>; }
-dontwarn com.google.android.filament.**
# Pontes nativas do Girassol (nomes JNI Java_com_enhikyy_..., handles e callbacks).
-keep class com.enhikyy.girassol.nativebridge.** { *; }
# v4.1: Orçamento Sináptico — métodos registrados por RegisterNatives (nome + assinatura exatos).
-keep class com.enhikyy.girassol.nativebridge.SynapticBudgetNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.enhikyy.girassol.nativebridge.SynapticBudgetNative { native <methods>; }
-keep class com.enhikyy.girassol.llm.** { *; }
-keep class com.enhikyy.girassol.audio.** { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }

# ---- v3.8: Sherpa-ONNX (SenseVoice, Whisper, Piper VITS, Pocket-TTS) ----
# A AAR do Sherpa-ONNX 2.0.2 não traz consumer-rules (proguard.txt vazio). O código nativo
# (libsherpa-onnx-jni.so) lê os objetos de configuração por GetFieldID/GetMethodID com os nomes
# originais (ex.: OfflineTtsVitsModelConfig.model, OfflineSenseVoiceModelConfig.language) e
# instancia OfflineRecognizerResult/GeneratedAudio por FindClass. No v3.7 o R8 removeu/renomeou
# essas classes no APK Release e qualquer ASR/TTS abortava com NoSuchFieldError dentro do JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
# Callbacks de áudio (lambdas Kotlin chamadas pelo nativo via invoke).
-keep class kotlin.jvm.functions.Function1 { *; }
# Ingestão/transcrição da Biblioteca (chama o Sherpa) e mapa de exceções nativas.
-keep class com.enhikyy.girassol.knowledge.** { *; }
