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
-keep class com.enhikyy.girassol.llm.** { *; }
-keep class com.enhikyy.girassol.audio.** { *; }
-keepclassmembers class * { @androidx.annotation.Keep *; }
