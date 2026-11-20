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
