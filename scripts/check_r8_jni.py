#!/usr/bin/env python3
"""v3.8 — verifica no APK Release (pós-R8) as classes e campos que o código nativo acessa por nome.

O JNI do Sherpa-ONNX lê as configurações com GetFieldID(nome) e o do Girassol registra
métodos Java_com_enhikyy_...; se o R8 remover ou renomear qualquer um deles, o app aborta em
execução real (foi o caso do v3.7: OfflineTtsVitsModelConfig e OfflineModelConfig sumiram).

Uso: check_r8_jni.py <apk> [dexdump]
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

# classe → campos/métodos que precisam existir com o nome original
REQUIRED = {
    "com/k2fsa/sherpa/onnx/OfflineModelConfig": ["senseVoice", "whisper", "tokens", "numThreads", "provider", "modelType"],
    "com/k2fsa/sherpa/onnx/OfflineSenseVoiceModelConfig": ["model", "language", "useInverseTextNormalization"],
    "com/k2fsa/sherpa/onnx/OfflineWhisperModelConfig": ["encoder", "decoder", "language", "task"],
    "com/k2fsa/sherpa/onnx/OfflineRecognizerConfig": ["modelConfig", "featConfig"],
    "com/k2fsa/sherpa/onnx/OfflineRecognizer": ["decode", "createStream", "getResult"],
    "com/k2fsa/sherpa/onnx/OfflineRecognizerResult": ["text"],
    "com/k2fsa/sherpa/onnx/OfflineStream": ["acceptWaveform"],
    "com/k2fsa/sherpa/onnx/OfflineTts": ["generateWithCallback", "generateWithConfig", "generateWithConfigAndCallback", "sampleRate"],
    "com/k2fsa/sherpa/onnx/OfflineTtsConfig": ["model"],
    "com/k2fsa/sherpa/onnx/OfflineTtsModelConfig": ["vits", "pocket", "numThreads", "provider"],
    "com/k2fsa/sherpa/onnx/OfflineTtsVitsModelConfig": ["model", "tokens", "dataDir"],
    "com/k2fsa/sherpa/onnx/OfflineTtsPocketModelConfig": [],
    "com/k2fsa/sherpa/onnx/GeneratedAudio": ["samples", "sampleRate"],
    "com/k2fsa/sherpa/onnx/GenerationConfig": ["phonemeCodepoints", "sid", "speed"],
    "com/enhikyy/girassol/nativebridge/GeaNativeBridge": [],
    "com/enhikyy/girassol/nativebridge/LlmNativeBridge": ["nativeLlmLoad", "nativeLlmGenerate", "nativeLlmEvictParked", "nativeLlmPoolStatus"],
    "com/enhikyy/girassol/core/world/NativeWorldTick": ["nativeStreamSeed", "nativeStepNeeds", "nativeStepDaily"],
    "com/enhikyy/girassol/core/world/NativeCognition": ["nativePerceive", "nativeCognitionBatch", "nativeVerifyChain", "nativeLedgerZeroSum"],
}


def find_dexdump(arg):
    if arg:
        return arg
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    bt = os.path.join(sdk, "build-tools")
    if os.path.isdir(bt):
        for v in sorted(os.listdir(bt), reverse=True):
            p = os.path.join(bt, v, "dexdump")
            if os.path.exists(p):
                return p
    return "dexdump"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    dexdump = find_dexdump(sys.argv[2] if len(sys.argv) > 2 else None)
    classes = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            path = z.extract(name, tmp)
            out = subprocess.run([dexdump, path], capture_output=True, text=True, check=True).stdout
            current = None
            for line in out.splitlines():
                m = re.match(r"\s*Class descriptor\s*:\s*'L([^;]+);'", line)
                if m:
                    current = m.group(1)
                    classes.setdefault(current, set())
                    continue
                m = re.match(r"\s*name\s*:\s*'([^']+)'", line)
                if m and current:
                    classes[current].add(m.group(1))
    errors = []
    for cls, members in REQUIRED.items():
        if cls not in classes:
            errors.append(f"classe ausente/renomeada pelo R8: {cls}")
            continue
        for mbr in members:
            if mbr not in classes[cls]:
                errors.append(f"membro ausente/renomeado pelo R8: {cls}.{mbr}")
    print(f"check_r8_jni: {len(classes)} classes no dex; {len(REQUIRED)} exigidas")
    if errors:
        for e in errors:
            print("ERRO:", e)
        return 1
    print("check_r8_jni: OK — todas as classes/membros acessados pelo JNI preservados")
    return 0


if __name__ == "__main__":
    sys.exit(main())
