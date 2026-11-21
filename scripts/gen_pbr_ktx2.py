#!/usr/bin/env python3
"""Gera as texturas PBR procedurais KTX2 do Girassol (determinístico, sem dependências).

Saída: app/src/main/assets/textures/{concreto_brutalista,carpete_backrooms}.ktx2
Formato: KTX 2.0, VK_FORMAT_R8G8B8A8_UNORM (37), sem supercompressão, cadeia de mipmaps
completa (box filter), DFD básico (KHR_DF_MODEL_RGBSDA, transferência linear).
Canais: RGB = albedo de detalhe centrado em 0.5 (multiplica a cor do vértice),
        A = rugosidade (0 = polido, 1 = fosco). Tile periódico (repete sem costura).

Uso: python3 scripts/gen_pbr_ktx2.py  (o CI só verifica os SHA-256 em textures.sha256)
"""
import math, os, struct, hashlib

N = 256
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "textures")

def h2(x, y, s):
    v = (x * 374761393 + y * 668265263 + s * 2147483647) & 0xFFFFFFFF
    v = ((v ^ (v >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((v ^ (v >> 16)) & 0xFFFFFF) / float(0xFFFFFF)

def vnoise(x, y, period, s):
    xi, yi = int(math.floor(x)), int(math.floor(y)); fx, fy = x - xi, y - yi
    ux, uy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    def g(a, b): return h2(a % period, b % period, s)
    a = g(xi, yi) + (g(xi + 1, yi) - g(xi, yi)) * ux
    b = g(xi, yi + 1) + (g(xi + 1, yi + 1) - g(xi, yi + 1)) * ux
    return a + (b - a) * uy

def fbm(u, v, base, octaves, s):
    t, amp, tot = 0.0, 0.5, 0.0
    for o in range(octaves):
        p = base << o
        t += amp * vnoise(u * p, v * p, p, s + o); tot += amp; amp *= 0.5
    return t / tot

def clamp8(x): return max(0, min(255, int(round(x * 255))))

def concrete(x, y):
    u, v = x / N, y / N
    n = fbm(u, v, 4, 5, 11)
    pores = 1.0 - 0.35 * (h2(x, y, 3) > 0.985)
    plank = 32
    seam = 1.0 - 0.18 * math.exp(-((y % plank) - 0.5) ** 2 / 1.2)       # marcas da fôrma de madeira
    grain = 0.06 * (vnoise(u * 64, v * 4, 64, 19) - 0.5)                # veio da tábua
    tie = 1.0
    for cx in (64, 192):
        for cy in (48, 176):
            d2 = (x - cx) ** 2 + (y - cy) ** 2
            if d2 < 20: tie = 0.55 + 0.02 * d2                         # furos dos tirantes
    a = (0.40 + 0.20 * n + grain) * pores * seam * tie
    a = 0.5 * a / 0.5
    rough = 0.78 + 0.18 * fbm(u, v, 8, 3, 23)
    return (a * 1.00, a * 0.99, a * 0.96, rough)

def carpet(x, y):
    u, v = x / N, y / N
    fib = 0.5 + 0.22 * (h2(x, y, 7) - 0.5) + 0.12 * (h2(x // 2, y // 2, 8) - 0.5)
    loop = 0.04 * math.sin((x + (y % 2) * 1.5) * math.pi / 2.0)          # laçadas do carpete
    stain = fbm(u, v, 2, 4, 31)                                          # manchas de umidade
    damp = 1.0 - 0.28 * max(0.0, stain - 0.58) / 0.42
    a = (fib + loop) * damp
    rough = 0.90 + 0.08 * h2(x, y, 9)
    return (a * 1.02, a * 1.0, a * 0.94, rough)

def build(fn):
    px = bytearray()
    for y in range(N):
        for x in range(N):
            r, g, b, a = fn(x, y)
            px += bytes((clamp8(r), clamp8(g), clamp8(b), clamp8(a)))
    levels = [bytes(px)]
    w = N
    while w > 1:
        src = levels[-1]; nw = w // 2; dst = bytearray(nw * nw * 4)
        for yy in range(nw):
            for xx in range(nw):
                for c in range(4):
                    s = (src[((2 * yy) * w + 2 * xx) * 4 + c] + src[((2 * yy) * w + 2 * xx + 1) * 4 + c] +
                         src[((2 * yy + 1) * w + 2 * xx) * 4 + c] + src[((2 * yy + 1) * w + 2 * xx + 1) * 4 + c])
                    dst[(yy * nw + xx) * 4 + c] = (s + 2) // 4
        levels.append(bytes(dst)); w = nw
    return levels

def ktx2(levels):
    ident = b"\xabKTX 20\xbb\r\n\x1a\n"
    nlev = len(levels)
    header_len = 12 + 9 * 4 + 4 * 4 + 2 * 8
    level_index_len = nlev * 24
    samples = [(0, 0), (8, 1), (16, 2), (24, 15)]
    bdb = struct.pack("<IHHBBBB4B8B", 0, 2, 24 + 16 * len(samples), 1, 1, 1, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0)
    for off, ch in samples:
        bdb += struct.pack("<HBB4BII", off, 7, ch, 0, 0, 0, 0, 0, 255)
    dfd = struct.pack("<I", 4 + len(bdb)) + bdb
    dfd_off = header_len + level_index_len
    data_off = dfd_off + len(dfd)
    data_off = (data_off + 3) & ~3
    # dados: menor nível primeiro (ordem exigida pela especificação)
    offsets = {}
    blob = bytearray()
    cur = data_off
    for i in reversed(range(nlev)):
        pad = (-cur) % 4
        blob += b"\0" * pad; cur += pad
        offsets[i] = cur
        blob += levels[i]; cur += len(levels[i])
    out = bytearray(ident)
    out += struct.pack("<9I", 37, 1, N, N, 0, 0, 1, nlev, 0)
    out += struct.pack("<IIII", dfd_off, len(dfd), 0, 0)
    out += struct.pack("<QQ", 0, 0)
    for i in range(nlev):
        out += struct.pack("<QQQ", offsets[i], len(levels[i]), len(levels[i]))
    out += dfd
    out += b"\0" * (data_off - len(out))
    out += blob
    return bytes(out)

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    lines = []
    for name, fn in (("concreto_brutalista", concrete), ("carpete_backrooms", carpet)):
        data = ktx2(build(fn))
        path = os.path.join(OUT, name + ".ktx2")
        with open(path, "wb") as f: f.write(data)
        lines.append(f"{hashlib.sha256(data).hexdigest()}  app/src/main/assets/textures/{name}.ktx2")
        print(name, len(data), "bytes")
    with open(os.path.join(ROOT, "licenses", "TEXTURES.sha256"), "w") as f: f.write("\n".join(lines) + "\n")
