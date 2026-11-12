// The bottom screen's map: the current area drawn from above, from the level the game has
// loaded (read-only; nothing from the game ships with the app). See the project doc
// bt-android-map.md for how the addresses and formats were found.
//
// 1. The area's map model is drawn from above with its own textures: every triangle facing
//    up, texture x vertex colour x a little slope light; the highest surface wins per pixel.
// 2. The area's collision floors give the ground you can walk on.
// 3. Walkable ground is shown bright, shaded by height (low light, high dark; the outer hills
//    dimmer), with a light outline around the area's edge only; spots inside it you can't
//    stand on are drawn as ground; things just beyond the edge in their own colours, darker,
//    fading out into the (transparent) background.
//
// Runs on a background thread (GameState.buildMap), once per area.

#ifndef BT_MAP_TEST
#include <jni.h>
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <map>
#include <tuple>
#include <vector>

#ifndef BT_MAP_TEST
extern "C" uint8_t* bt_android_running_rdram();   // game_state.cpp
#endif

namespace {
    // The current area (see bt-android-map.md).
    constexpr uint32_t kCollisionPtr = 0x801285B0u;   // collision list
    constexpr uint32_t kModelPtr = 0x801285BCu;       // map model (header magic 0x0B)
    constexpr uint32_t kTextureBlockPtr = 0x801285D4u;  // assembled textures, when referenced by id
    // The area's second model: its see-through layer (Spiral Mountain: the water; Jinjo Village:
    // grey mist/scenery). Pointer next to the map model's in another table; its assembled textures.
    constexpr uint32_t kSecondModelPtr = 0x8012B458u;
    constexpr uint32_t kSecondTextureBlockPtr = 0x80128788u;
    constexpr uint32_t kSegment3 = 0x8011A650u;       // shared render-mode sub-lists
    constexpr uint32_t kInvisible = 0x01000000u;      // collision flag: the boundary shell

    struct Ram {
        const uint8_t* rdram;
        bool ok(uint32_t a, uint32_t n) const {
            return a >= 0x80000000u && a < 0x80800000u && n <= 0x80800000u - a;
        }
        uint8_t u8(uint32_t a) const { return rdram[(a - 0x80000000u) ^ 3]; }
        uint16_t u16(uint32_t a) const { return static_cast<uint16_t>((u8(a) << 8) | u8(a + 1)); }
        int16_t s16(uint32_t a) const { return static_cast<int16_t>(u16(a)); }
        uint32_t u32(uint32_t a) const { return *reinterpret_cast<const uint32_t*>(rdram + (a - 0x80000000u)); }
    };

    struct Rgba { float r, g, b, a; };

    struct Texture {
        int w = 0, h = 0;
        std::vector<Rgba> px;
        bool bluish = false;   // average colour clearly blue: water
    };

    Rgba from5551(uint16_t v) {
        return { ((v >> 11) & 31) * 255.0f / 31.0f, ((v >> 6) & 31) * 255.0f / 31.0f,
                 ((v >> 1) & 31) * 255.0f / 31.0f, (v & 1) ? 255.0f : 0.0f };
    }

    // N64 texture formats: fmt 0 RGBA, 2 CI, 3 IA, 4 I; siz 0 4-bit, 1 8-bit, 2 16-bit, 3 32-bit.
    bool decode(const Ram& ram, uint32_t addr, int fmt, int siz, int w, int h, uint32_t pal, bool palIA, Texture& out) {
        const int n = w * h;
        const int bits = 4 << siz;
        if (n <= 0 || n > 16384 || !ram.ok(addr, static_cast<uint32_t>(n * bits / 8 + 4))) {
            return false;
        }
        out.w = w;
        out.h = h;
        out.px.resize(static_cast<size_t>(n));
        Rgba palette[256];
        if (fmt == 2) {
            const int count = siz == 0 ? 16 : 256;
            if (pal == 0 || !ram.ok(pal, static_cast<uint32_t>(count * 2))) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                const uint16_t v = ram.u16(pal + 2u * i);
                if (palIA) {
                    const float c = static_cast<float>(v >> 8);
                    palette[i] = { c, c, c, static_cast<float>(v & 0xFF) };
                } else {
                    palette[i] = from5551(v);
                }
            }
        }
        for (int i = 0; i < n; i++) {
            uint32_t value;
            if (siz == 0) {
                const uint8_t b = ram.u8(addr + i / 2);
                value = (i & 1) ? (b & 15) : (b >> 4);
            } else if (siz == 1) {
                value = ram.u8(addr + i);
            } else if (siz == 2) {
                value = ram.u16(addr + 2u * i);
            } else {
                const uint32_t a = addr + 4u * i;
                out.px[i] = { float(ram.u8(a)), float(ram.u8(a + 1)), float(ram.u8(a + 2)), float(ram.u8(a + 3)) };
                continue;
            }
            Rgba c;
            if (fmt == 2) {
                c = palette[std::min<uint32_t>(value, siz == 0 ? 15u : 255u)];
            } else if (fmt == 0 && siz == 2) {
                c = from5551(static_cast<uint16_t>(value));
            } else if (fmt == 3) {
                float i8, a8;
                if (siz == 0) { i8 = (value >> 1) * 255.0f / 7.0f; a8 = (value & 1) ? 255.0f : 0.0f; }
                else if (siz == 1) { i8 = (value >> 4) * 17.0f; a8 = (value & 15) * 17.0f; }
                else { i8 = float(value >> 8); a8 = float(value & 0xFF); }
                c = { i8, i8, i8, a8 };
            } else if (fmt == 4) {
                const float i8 = siz == 0 ? value * 17.0f : float(value);
                c = { i8, i8, i8, 255.0f };
            } else {
                return false;
            }
            out.px[i] = c;
        }
        return true;
    }

    struct Tile {
        int fmt = 0, siz = 0, cms = 0, cmt = 0, ms = 0, mt = 0, ss = 0, st = 0;
    };

    struct Vertex {
        float x, y, z, s, t, r, g, b;
        bool valid = false;
    };

    struct Tri {
        Vertex v[3];
        const Texture* tex;
        bool water;
        Tile tile;
    };

    int wrapCoord(float c, int size, int cm, int mask) {
        long v = static_cast<long>(std::floor(c));
        if (mask) {
            const long period = std::min<long>(1L << mask, size);
            if (cm & 1) {
                v = ((v % (2 * period)) + 2 * period) % (2 * period);
                if (v >= period) v = 2 * period - 1 - v;
            } else {
                v = ((v % period) + period) % period;
            }
            return static_cast<int>(std::clamp<long>(v, 0, size - 1));
        }
        if (cm & 2) return static_cast<int>(std::clamp<long>(v, 0, size - 1));
        return static_cast<int>(((v % size) + size) % size);
    }

    // Walks the map model's display list (F3DEX2) and collects the triangles with their
    // texture state. Textures are decoded once each (kept in `textures`).
    bool collect(const Ram& ram, uint32_t modelPtr, uint32_t blockPtr, bool water, std::vector<Tri>& tris,
                 std::map<std::tuple<uint32_t, int, int, int, int, uint32_t, bool>, Texture>& textures) {
        const uint32_t base = ram.u32(modelPtr);
        if (!ram.ok(base, 0x60) || ram.u32(base) != 0x0Bu) {
            return false;
        }
        const uint32_t gfx = base + ram.u32(base + 0x0C);
        const uint32_t vtxList = base + ram.u32(base + 0x10);
        const uint32_t ownTextures = base + (ram.u32(base + 0x08) >> 16);
        // Textures referenced by id live in an assembled copy of the texture list (same header).
        const uint32_t assembled = ram.u32(blockPtr);
        const bool useAssembled = ram.ok(assembled, 8) && ram.ok(ownTextures, 8) &&
            ram.u32(assembled) == ram.u32(ownTextures) && ram.u32(assembled + 4) == ram.u32(ownTextures + 4);
        const uint32_t texList = useAssembled ? assembled : ownTextures;
        if (!ram.ok(gfx, 8) || !ram.ok(vtxList, 0x18) || !ram.ok(texList, 8)) {
            return false;
        }
        const uint32_t commands = ram.u32(gfx);
        const int vertexCount = ram.s16(vtxList + 0x14);
        const uint32_t vtxData = vtxList + 0x18;
        const int texCount = ram.u16(texList + 4);
        const uint32_t texData = texList + 8 + static_cast<uint32_t>(texCount) * 8;
        if (commands == 0 || commands > 200000 || !ram.ok(gfx + 8, commands * 8) || vertexCount <= 0 || texCount > 1024) {
            return false;
        }

        // Each texture's real size and format, from the texture list (the tile size in the
        // display list can be stale for mipmapped textures).
        struct Info { int w, h, fmt, siz; };
        std::map<uint32_t, Info> sizes;
        for (int k = 0; k < texCount; k++) {
            const uint32_t entry = texList + 8 + 8u * k;
            const uint32_t offset = ram.u32(entry);
            const uint32_t word = ram.u32(entry + 4);
            const int type = (word >> 16) & 0x7FFF;
            const int w = (word >> 8) & 0xFF, h = word & 0xFF;
            int fmt = -1, siz = 0;
            switch (type & 0xFF) {
                case 0x01: fmt = 2; siz = 0; break;   // CI4
                case 0x02: fmt = 2; siz = 1; break;   // CI8
                case 0x04: fmt = 0; siz = 2; break;   // RGBA16
                case 0x08: fmt = 0; siz = 3; break;   // RGBA32
                case 0x10: fmt = 3; siz = 1; break;   // IA8
                default: break;
            }
            const uint32_t at = texData + offset;
            const Info info{ w, h, fmt, siz };
            sizes[at] = info;
            sizes[at + 0x20] = info;    // after a CI4 palette
            sizes[at + 0x200] = info;   // after a CI8 palette
        }

        auto segment = [&](uint32_t a) -> uint32_t {
            const uint32_t s = (a >> 24) & 0xF, o = a & 0xFFFFFF;
            if (s == 1) return vtxData + o;
            if (s == 2) return texData + o;
            return 0;
        };

        std::array<Vertex, 64> vbuf{};
        uint32_t timg = 0, tlut = 0, image = 0;
        bool tlutIA = false;
        Tile tile;
        float uls = 0, ult = 0;
        int tw = 0, th = 0;
        float scaleS = 0.5f, scaleT = 0.5f;
        const Texture* current = nullptr;
        bool dirty = true;

        auto run = [&](uint32_t w0, uint32_t w1) {
            const uint32_t op = w0 >> 24;
            switch (op) {
                case 0xFD: timg = segment(w1); break;
                case 0xF0: tlut = timg; dirty = true; break;
                case 0xE3: {
                    const int length = (w0 & 0xFF) + 1;
                    const int shift = 32 - static_cast<int>((w0 >> 8) & 0xFF) - length;
                    if (shift == 14) { tlutIA = ((w1 >> 14) & 3) == 3; dirty = true; }
                    break;
                }
                case 0xF3: case 0xF4: image = timg; dirty = true; break;
                case 0xF5:
                    if (((w1 >> 24) & 7) == 0) {
                        tile = { int((w0 >> 21) & 7), int((w0 >> 19) & 3), int((w1 >> 8) & 3), int((w1 >> 18) & 3),
                                 int((w1 >> 4) & 15), int((w1 >> 14) & 15), int(w1 & 15), int((w1 >> 10) & 15) };
                        dirty = true;
                    }
                    break;
                case 0xF2:
                    if (((w1 >> 24) & 7) == 0) {
                        uls = ((w0 >> 12) & 0xFFF) / 4.0f;
                        ult = (w0 & 0xFFF) / 4.0f;
                        tw = int(((w1 >> 12) & 0xFFF) / 4.0f - uls) + 1;
                        th = int((w1 & 0xFFF) / 4.0f - ult) + 1;
                        dirty = true;
                    }
                    break;
                case 0xD7:
                    scaleS = ((w1 >> 16) & 0xFFFF) / 65536.0f;
                    scaleT = (w1 & 0xFFFF) / 65536.0f;
                    break;
                case 0x01: {
                    const int n = (w0 >> 12) & 0xFF;
                    const int end = (w0 >> 1) & 0x7F;
                    const uint32_t a = segment(w1);
                    if (a == 0 || end - n < 0 || end > 64 || !ram.ok(a, n * 16u)) break;
                    for (int k = 0; k < n; k++) {
                        const uint32_t v = a + 16u * k;
                        Vertex& out = vbuf[end - n + k];
                        out.x = ram.s16(v); out.y = ram.s16(v + 2); out.z = ram.s16(v + 4);
                        out.s = ram.s16(v + 8) / 32.0f; out.t = ram.s16(v + 10) / 32.0f;
                        out.r = ram.u8(v + 12) / 255.0f; out.g = ram.u8(v + 13) / 255.0f; out.b = ram.u8(v + 14) / 255.0f;
                        out.valid = true;
                    }
                    break;
                }
                case 0x05: case 0x06: {
                    if (dirty) {
                        current = nullptr;
                        Info info{ tw, th, tile.fmt, tile.siz };
                        auto found = sizes.find(image);
                        if (found != sizes.end()) {
                            info.w = found->second.w;
                            info.h = found->second.h;
                            if (found->second.fmt >= 0) { info.fmt = found->second.fmt; info.siz = found->second.siz; }
                        }
                        const auto key = std::make_tuple(image, info.fmt, info.siz, info.w, info.h, tlut, tlutIA);
                        auto cached = textures.find(key);
                        if (cached == textures.end()) {
                            Texture t;
                            if (image == 0 || !decode(ram, image, info.fmt, info.siz, info.w, info.h, tlut, tlutIA, t)) {
                                t = Texture{};
                            } else {
                                double r = 0, g = 0, b = 0;
                                for (const Rgba& c : t.px) { r += c.r; g += c.g; b += c.b; }
                                t.bluish = b > r * 1.25 && b > g * 1.1;
                            }
                            cached = textures.emplace(key, std::move(t)).first;
                        }
                        current = cached->second.px.empty() ? nullptr : &cached->second;
                        dirty = false;
                    }
                    int idx[6] = { int((w0 >> 16) & 0xFF) / 2, int((w0 >> 8) & 0xFF) / 2, int(w0 & 0xFF) / 2,
                                   int((w1 >> 16) & 0xFF) / 2, int((w1 >> 8) & 0xFF) / 2, int(w1 & 0xFF) / 2 };
                    const int count = op == 0x06 ? 2 : 1;
                    for (int t = 0; t < count; t++) {
                        Tri tri;
                        bool ok = true;
                        for (int k = 0; k < 3; k++) {
                            const int i = idx[t * 3 + k];
                            if (i < 0 || i >= 64 || !vbuf[i].valid) { ok = false; break; }
                            tri.v[k] = vbuf[i];
                            auto shift = [](float c, int s) { return s <= 10 ? c / float(1 << s) : c * float(1 << (16 - s)); };
                            tri.v[k].s = shift(tri.v[k].s * scaleS, tile.ss) - uls;
                            tri.v[k].t = shift(tri.v[k].t * scaleT, tile.st) - ult;
                        }
                        if (!ok) continue;
                        tri.tex = current;
                        tri.tile = tile;
                        tri.water = water;   // checked against the texture and height later
                        tris.push_back(tri);
                    }
                    break;
                }
                default: break;
            }
        };

        for (uint32_t i = 0; i < commands; i++) {
            const uint32_t a = gfx + 8 + 8 * i;
            const uint32_t w0 = ram.u32(a), w1 = ram.u32(a + 4);
            if ((w0 >> 24) == 0xDE && (w1 >> 24) == 3) {
                const uint32_t sub = kSegment3 + (w1 & 0xFFFFFF);
                for (int k = 0; k < 64 && ram.ok(sub + 8u * k, 8); k++) {
                    const uint32_t x0 = ram.u32(sub + 8u * k), x1 = ram.u32(sub + 8u * k + 4);
                    if ((x0 >> 24) == 0xDF) break;
                    run(x0, x1);
                }
                continue;
            }
            if ((w0 >> 24) == 0xDF) continue;
            run(w0, w1);
        }
        return !tris.empty();
    }

    struct Floor { float p[3][3]; };

    bool collectFloors(const Ram& ram, std::vector<Floor>& floors) {
        const uint32_t base = ram.u32(kModelPtr);
        const uint32_t col = ram.u32(kCollisionPtr);
        if (!ram.ok(base, 0x20) || !ram.ok(col, 0x18)) return false;
        const uint32_t vtxList = base + ram.u32(base + 0x10);
        if (!ram.ok(vtxList, 0x18)) return false;
        const int vertexCount = ram.s16(vtxList + 0x14);
        const int geo = ram.s16(col + 0x10), count = ram.s16(col + 0x14);
        if (geo <= 0 || count <= 0 || vertexCount <= 0) return false;
        const uint32_t tris = col + 0x18 + uint32_t(geo) * 4;
        if (!ram.ok(tris, uint32_t(count) * 12) || !ram.ok(vtxList + 0x18, uint32_t(vertexCount) * 16)) return false;
        for (int t = 0; t < count; t++) {
            const uint32_t a = tris + 12u * t;
            if (ram.u32(a + 8) & kInvisible) continue;
            Floor f;
            bool ok = true;
            for (int k = 0; k < 3; k++) {
                const int i = ram.s16(a + 2 * k);
                if (i < 0 || i >= vertexCount) { ok = false; break; }
                const uint32_t v = vtxList + 0x18 + 16u * i;
                f.p[k][0] = ram.s16(v); f.p[k][1] = ram.s16(v + 2); f.p[k][2] = ram.s16(v + 4);
            }
            if (!ok) continue;
            const float ax = f.p[1][0] - f.p[0][0], ay = f.p[1][1] - f.p[0][1], az = f.p[1][2] - f.p[0][2];
            const float bx = f.p[2][0] - f.p[0][0], by = f.p[2][1] - f.p[0][1], bz = f.p[2][2] - f.p[0][2];
            const float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            const float len = std::sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0 && ny / len > 0.6f) floors.push_back(f);
        }
        return !floors.empty();
    }

    // Rasterises (x, z) triangles; calls fn(px, l0, l1, l2) for each covered pixel.
    template <typename Fn>
    void raster(const float X[3], const float Z[3], int W, int H, Fn fn) {
        const int x0 = std::max(0, int(std::floor(std::min({ X[0], X[1], X[2] }))));
        const int x1 = std::min(W - 1, int(std::ceil(std::max({ X[0], X[1], X[2] }))));
        const int z0 = std::max(0, int(std::floor(std::min({ Z[0], Z[1], Z[2] }))));
        const int z1 = std::min(H - 1, int(std::ceil(std::max({ Z[0], Z[1], Z[2] }))));
        const float d = (Z[1] - Z[2]) * (X[0] - X[2]) + (X[2] - X[1]) * (Z[0] - Z[2]);
        if (x0 > x1 || z0 > z1 || std::fabs(d) < 1e-9f) return;
        for (int z = z0; z <= z1; z++) {
            const float gz = z + 0.5f;
            for (int x = x0; x <= x1; x++) {
                const float gx = x + 0.5f;
                const float l0 = ((Z[1] - Z[2]) * (gx - X[2]) + (X[2] - X[1]) * (gz - Z[2])) / d;
                const float l1 = ((Z[2] - Z[0]) * (gx - X[2]) + (X[0] - X[2]) * (gz - Z[2])) / d;
                const float l2 = 1.0f - l0 - l1;
                if (l0 < -1e-3f || l1 < -1e-3f || l2 < -1e-3f) continue;
                fn(z * W + x, l0, l1, l2);
            }
        }
    }

    // Separable box filter on a float plane (radius r), used for smoothing and the flat dim colour.
    void boxBlur(std::vector<float>& a, int W, int H, int r) {
        if (r <= 0) return;
        std::vector<float> tmp(a.size());
        for (int z = 0; z < H; z++) {
            float sum = 0; int n = 0;
            for (int x = -r; x <= r; x++) if (x >= 0 && x < W) { sum += a[z * W + x]; n++; }
            for (int x = 0; x < W; x++) {
                tmp[z * W + x] = sum / std::max(1, n);
                const int out = x - r, in = x + r + 1;
                if (out >= 0) { sum -= a[z * W + out]; n--; }
                if (in < W) { sum += a[z * W + in]; n++; }
            }
        }
        for (int x = 0; x < W; x++) {
            float sum = 0; int n = 0;
            for (int z = -r; z <= r; z++) if (z >= 0 && z < H) { sum += tmp[z * W + x]; n++; }
            for (int z = 0; z < H; z++) {
                a[z * W + x] = sum / std::max(1, n);
                const int out = z - r, in = z + r + 1;
                if (out >= 0) { sum -= tmp[out * W + x]; n--; }
                if (in < H) { sum += tmp[in * W + x]; n++; }
            }
        }
    }

    // Max (grow) or min (shrink) of a mask over a square of radius r.
    std::vector<uint8_t> morph(const std::vector<uint8_t>& m, int W, int H, int r, bool grow) {
        std::vector<uint8_t> tmp(m.size()), out(m.size());
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                uint8_t v = grow ? 0 : 1;
                for (int k = std::max(0, x - r); k <= std::min(W - 1, x + r); k++)
                    v = grow ? std::max(v, m[z * W + k]) : std::min(v, m[z * W + k]);
                tmp[z * W + x] = v;
            }
        for (int x = 0; x < W; x++)
            for (int z = 0; z < H; z++) {
                uint8_t v = grow ? 0 : 1;
                for (int k = std::max(0, z - r); k <= std::min(H - 1, z + r); k++)
                    v = grow ? std::max(v, tmp[k * W + x]) : std::min(v, tmp[k * W + x]);
                out[z * W + x] = v;
            }
        return out;
    }
}

// Builds the current area's map picture: ARGB pixels (0 = transparent) and meta =
// { width, height, world x at pixel 0, world z at pixel 0, pixels per world unit }.
bool bt_build_map(const uint8_t* rdram, int maxSize, float capY, std::vector<int32_t>& out, float meta[5]) {
    const Ram ram{ rdram };

    std::vector<Tri> tris;
    std::map<std::tuple<uint32_t, int, int, int, int, uint32_t, bool>, Texture> textures;
    std::vector<Floor> floors;
    if (!collect(ram, kModelPtr, kTextureBlockPtr, false, tris, textures) || !collectFloors(ram, floors)) return false;
    // A floor layer (Banjo under another floor): leave out everything entirely above capY.
    if (capY < 1e8f) {
        tris.erase(std::remove_if(tris.begin(), tris.end(), [&](const Tri& t) {
            return std::min({ t.v[0].y, t.v[1].y, t.v[2].y }) > capY;
        }), tris.end());
        floors.erase(std::remove_if(floors.begin(), floors.end(), [&](const Floor& f) {
            return std::min({ f.p[0][1], f.p[1][1], f.p[2][1] }) > capY;
        }), floors.end());
        if (floors.empty()) return false;
    }
    // The second (see-through) model: only its water is drawn: bluish textures at about the
    // ground's height. In Spiral Mountain that's the moat, river and pool; Jinjo Village's is
    // grey mist/scenery from far below the island to far above it (not drawn).
    {
        std::vector<Tri> second;
        if (collect(ram, kSecondModelPtr, kSecondTextureBlockPtr, true, second, textures)) {
            std::vector<float> fy;
            for (const Floor& f : floors) fy.push_back((f.p[0][1] + f.p[1][1] + f.p[2][1]) / 3.0f);
            std::sort(fy.begin(), fy.end());
            const float low = fy[fy.size() / 100] - 300.0f, high = fy[fy.size() / 2] + 1500.0f;
            for (const Tri& t : second) {
                const float y = (t.v[0].y + t.v[1].y + t.v[2].y) / 3.0f;
                if (t.tex != nullptr && t.tex->bluish && y >= low && y <= high) tris.push_back(t);
            }
        }
    }

    // Fit the walkable floors (with a margin) into at most maxSize pixels.
    float minX = 1e9f, maxX = -1e9f, minZ = 1e9f, maxZ = -1e9f;
    for (const Floor& f : floors)
        for (const auto& p : f.p) {
            minX = std::min(minX, p[0]); maxX = std::max(maxX, p[0]);
            minZ = std::min(minZ, p[2]); maxZ = std::max(maxZ, p[2]);
        }
    const float margin = 0.04f * std::max(maxX - minX, maxZ - minZ) + 100.0f;
    minX -= margin; maxX += margin; minZ -= margin; maxZ += margin;
    const float scale = float(std::clamp<int>(maxSize, 256, 4096)) / std::max(maxX - minX, maxZ - minZ);
    const int W = std::max(1, int((maxX - minX) * scale)), H = std::max(1, int((maxZ - minZ) * scale));
    const size_t N = size_t(W) * H;

    // 1. The model from above, textured.
    std::vector<float> R(N, 0), G(N, 0), B(N, 0), top(N, -1e9f);
    std::vector<uint8_t> drawn(N, 0), water(N, 0);
    constexpr float lx = -0.47f, ly = 0.71f, lz = -0.52f;
    for (const Tri& t : tris) {
        float X[3], Z[3];
        for (int k = 0; k < 3; k++) { X[k] = (t.v[k].x - minX) * scale; Z[k] = (t.v[k].z - minZ) * scale; }
        const float ax = t.v[1].x - t.v[0].x, ay = t.v[1].y - t.v[0].y, az = t.v[1].z - t.v[0].z;
        const float bx = t.v[2].x - t.v[0].x, by = t.v[2].y - t.v[0].y, bz = t.v[2].z - t.v[0].z;
        const float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        const float len = std::sqrt(nx * nx + ny * ny + nz * nz);
        if (len <= 0 || ny / len < 0.2f) continue;
        const float light = 0.75f + 0.35f * std::max(0.0f, (nx * lx + ny * ly + nz * lz) / len);
        raster(X, Z, W, H, [&](int i, float l0, float l1, float l2) {
            const float y = l0 * t.v[0].y + l1 * t.v[1].y + l2 * t.v[2].y;
            if (y < top[i]) return;
            float r = 200, g = 200, b = 200;
            if (t.tex != nullptr) {
                const float s = l0 * t.v[0].s + l1 * t.v[1].s + l2 * t.v[2].s;
                const float tt = l0 * t.v[0].t + l1 * t.v[1].t + l2 * t.v[2].t;
                const int si = wrapCoord(s, t.tex->w, t.tile.cms, t.tile.ms);
                const int ti = wrapCoord(tt, t.tex->h, t.tile.cmt, t.tile.mt);
                const Rgba& c = t.tex->px[size_t(ti) * t.tex->w + si];
                if (c.a <= 32) return;
                r = c.r; g = c.g; b = c.b;
            }
            const float sr = l0 * t.v[0].r + l1 * t.v[1].r + l2 * t.v[2].r;
            const float sg = l0 * t.v[0].g + l1 * t.v[1].g + l2 * t.v[2].g;
            const float sb = l0 * t.v[0].b + l1 * t.v[1].b + l2 * t.v[2].b;
            top[i] = y;
            R[i] = r * sr * light; G[i] = g * sg * light; B[i] = b * sb * light;
            drawn[i] = 1;
            water[i] = t.water ? 1 : 0;
        });
    }

    // 2. Walkable ground: the highest collision floor per pixel.
    std::vector<float> ground(N, -1e9f);
    for (const Floor& f : floors) {
        float X[3], Z[3];
        for (int k = 0; k < 3; k++) { X[k] = (f.p[k][0] - minX) * scale; Z[k] = (f.p[k][2] - minZ) * scale; }
        raster(X, Z, W, H, [&](int i, float l0, float l1, float l2) {
            const float y = l0 * f.p[0][1] + l1 * f.p[1][1] + l2 * f.p[2][1];
            if (y > ground[i]) ground[i] = y;
        });
    }
    std::vector<uint8_t> walk(N);
    for (size_t i = 0; i < N; i++) walk[i] = ground[i] > -1e8f;
    const int px = std::max(1, int(std::lround(W / 900.0f)));   // ~1 pixel at the panel's size
    walk = morph(morph(walk, W, H, 2 * px, true), W, H, 2 * px, false);   // close pinholes
    // Spots you can't stand on that walkable ground surrounds (cliff faces, slopes, fences,
    // rocks) are drawn as ground too, at the height the model shows there: the textures show
    // them clearly enough, and outlining each one cluttered the map. Holes with nothing drawn
    // (real drops) stay empty. Only the area's outer edge keeps the outline.
    {
        std::vector<uint8_t> outside(N, 0);
        std::vector<int> stack;
        auto seed = [&](int x, int z) {
            const size_t i = size_t(z) * W + x;
            if (!walk[i] && !outside[i]) { outside[i] = 1; stack.push_back(int(i)); }
        };
        for (int x = 0; x < W; x++) { seed(x, 0); seed(x, H - 1); }
        for (int z = 0; z < H; z++) { seed(0, z); seed(W - 1, z); }
        while (!stack.empty()) {
            const int i = stack.back();
            stack.pop_back();
            const int x = i % W, z = i / W;
            if (x > 0) seed(x - 1, z);
            if (x < W - 1) seed(x + 1, z);
            if (z > 0) seed(x, z - 1);
            if (z < H - 1) seed(x, z + 1);
        }
        for (size_t i = 0; i < N; i++) {
            if (!walk[i] && !outside[i] && drawn[i]) {
                walk[i] = 1;
                ground[i] = top[i];
            }
        }
        // Small enclosed holes with nothing drawn (specks between triangles): ground too, at
        // the height of the ground around them.
        std::vector<uint8_t> seen(N, 0);
        std::vector<int> part;
        for (size_t start = 0; start < N; start++) {
            if (walk[start] || outside[start] || seen[start]) continue;
            part.clear();
            stack.assign(1, int(start));
            seen[start] = 1;
            float edge = -1e9f;
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                part.push_back(i);
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (walk[j]) { edge = std::max(edge, ground[j]); continue; }
                    if (!seen[j] && !outside[j]) { seen[j] = 1; stack.push_back(int(j)); }
                }
            }
            if (part.size() < N / 1500 && edge > -1e8f) {
                for (int i : part) { walk[i] = 1; ground[i] = edge; }
            }
        }
    }
    // Heights for the pixels closing added: the nearest known ground (grow outwards).
    for (int pass = 0; pass < 4 * px; pass++) {
        bool any = false;
        std::vector<float> next = ground;
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                const size_t i = size_t(z) * W + x;
                if (!walk[i] || ground[i] > -1e8f) continue;
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        const int xx = x + dx, zz = z + dz;
                        if (xx < 0 || zz < 0 || xx >= W || zz >= H) continue;
                        const float g = ground[size_t(zz) * W + xx];
                        if (g > -1e8f) { next[i] = std::max(next[i], g); any = true; }
                    }
            }
        ground.swap(next);
        if (!any) break;
    }
    std::vector<float> heights;
    for (size_t i = 0; i < N; i += 7) if (walk[i] && ground[i] > -1e8f) heights.push_back(ground[i]);
    if (heights.empty()) return false;
    std::sort(heights.begin(), heights.end());
    const float lo = heights[heights.size() / 20], hi = heights[heights.size() - 1 - heights.size() / 20];
    const float middle = heights[heights.size() / 2];

    // 3. Smoothing: a light blur of the textures.
    std::vector<float> sR = R, sG = G, sB = B;
    boxBlur(sR, W, H, px); boxBlur(sG, W, H, px); boxBlur(sB, W, H, px);

    // The area you play in: walkable ground up to a little above the main level. Ground far
    // above it (the tops of Jinjo Village's tree walls and cliffs, Spiral Mountain's outer
    // hills) is treated like the surroundings: no outline, faded out.
    const float span = std::max(1.0f, hi - lo);
    const float ceiling = middle + 0.35f * span;
    std::vector<uint8_t> main(N);
    for (size_t i = 0; i < N; i++) main[i] = walk[i] && ground[i] <= ceiling;
    main = morph(morph(main, W, H, 2 * px, true), W, H, 2 * px, false);   // no ragged bits
    for (size_t i = 0; i < N; i++) main[i] = main[i] && walk[i];
    // Keep only sizeable pieces (at least 4% of the biggest): slivers and shards left over from
    // the cut become surroundings too.
    {
        std::vector<int> label(N, -1), sizes, stack;
        for (size_t start = 0; start < N; start++) {
            if (!main[start] || label[start] >= 0) continue;
            const int id = int(sizes.size());
            int count = 0;
            stack.assign(1, int(start));
            label[start] = id;
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                count++;
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (main[j] && label[j] < 0) { label[j] = id; stack.push_back(int(j)); }
                }
            }
            sizes.push_back(count);
        }
        const int biggest = sizes.empty() ? 0 : *std::max_element(sizes.begin(), sizes.end());
        for (size_t i = 0; i < N; i++)
            if (main[i] && sizes[size_t(label[i])] < biggest / 25) main[i] = 0;
    }
    const std::vector<uint8_t> inner = morph(main, W, H, px, false);
    const std::vector<uint8_t> waterInner = morph(water, W, H, px, false);

    // Definition: the surface height everywhere something is drawn; dark lines at the foot of
    // every height jump (rocks, houses, ledges, steps), a light edge on top of it, and soft
    // shading in hollows, so objects stand apart from the ground around them.
    std::vector<float> surface(N, -1e9f);
    for (size_t i = 0; i < N; i++) surface[i] = drawn[i] ? top[i] : (walk[i] ? ground[i] : -1e9f);
    std::vector<float> footShade(N, 1.0f);
    {
        constexpr float jump = 30.0f;    // world units: a step you'd notice
        constexpr float full = 160.0f;   // a jump this big gets the full edge
        std::vector<float> avg(N), weight(N);
        for (size_t i = 0; i < N; i++) { const bool has = surface[i] > -1e8f; avg[i] = has ? surface[i] : 0; weight[i] = has ? 1.0f : 0.0f; }
        boxBlur(avg, W, H, 6 * px); boxBlur(weight, W, H, 6 * px);
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                const size_t i = size_t(z) * W + x;
                const float h = surface[i];
                if (h <= -1e8f) continue;
                float up = 0, down = 0;
                for (int dz = -px; dz <= px; dz += px)
                    for (int dx = -px; dx <= px; dx += px) {
                        const int xx = x + dx, zz = z + dz;
                        if ((dx == 0 && dz == 0) || xx < 0 || zz < 0 || xx >= W || zz >= H) continue;
                        const float n = surface[size_t(zz) * W + xx];
                        if (n <= -1e8f) continue;
                        up = std::max(up, n - h);
                        down = std::max(down, h - n);
                    }
                float f = 1.0f;
                if (up > jump) f *= 1.0f - 0.55f * std::min(1.0f, (up - jump) / (full - jump) + 0.35f);     // foot: dark line
                else if (down > jump) f *= 1.0f + 0.18f * std::min(1.0f, (down - jump) / (full - jump) + 0.3f);   // top: light edge
                const float around = weight[i] > 0.01f ? avg[i] / weight[i] : h;
                f *= 1.0f - std::clamp((around - h) / 400.0f, 0.0f, 0.28f);   // hollows
                footShade[i] = f;
            }
    }

    // How far beyond the area's edge a pixel is: 1 at the edge, fading to 0 about 12 panel
    // pixels out (two box blurs of the walkable/water mask).
    std::vector<float> fade(N);
    for (size_t i = 0; i < N; i++) fade[i] = (main[i] || water[i]) ? 1.0f : 0.0f;
    boxBlur(fade, W, H, 8 * px);
    boxBlur(fade, W, H, 8 * px);
    for (float& f : fade) f = std::clamp(f * 2.2f, 0.0f, 1.0f) * 0.85f;

    // 4. Compose.
    out.assign(N, 0);
    constexpr float warmR = 235, warmG = 190, warmB = 120;
    constexpr float warm = 0.12f;         // a light warm tint keeps colours distinct
    constexpr float saturation = 1.22f;
    for (size_t i = 0; i < N; i++) {
        float r, g, b;
        if (water[i]) {
            // Water: its own texture pulled towards a clear blue, no warm tint, a light shoreline.
            r = sR[i] * 0.5f + 60 * 0.5f;
            g = sG[i] * 0.5f + 135 * 0.5f;
            b = sB[i] * 0.5f + 225 * 0.5f;
            if (!waterInner[i]) {
                r = r * 0.3f + 205 * 0.7f; g = g * 0.3f + 238 * 0.7f; b = b * 0.3f + 255 * 0.7f;
            }
        } else if (main[i]) {
            const float k = std::clamp((ground[i] - lo) / std::max(1.0f, hi - lo), 0.0f, 1.0f);
            float f = 1.08f - 0.55f * k;

            r = sR[i] * (1 - warm) + warmR * warm;
            g = sG[i] * (1 - warm) + warmG * warm;
            b = sB[i] * (1 - warm) + warmB * warm;
            const float grey = (r + g + b) / 3.0f;
            r = (grey + (r - grey) * saturation) * f * footShade[i];
            g = (grey + (g - grey) * saturation) * f * footShade[i];
            b = (grey + (b - grey) * saturation) * f * footShade[i];
            if (!inner[i]) {   // the outline
                r = r * 0.1f + 255 * 0.9f; g = g * 0.1f + 236 * 0.9f; b = b * 0.1f + 190 * 0.9f;
            }
        } else if ((drawn[i] || walk[i]) && fade[i] > 0.02f) {
            // Beyond the area's edge: its own colours, darker, fading out with distance.
            const float wr = sR[i] * (1 - warm) + warmR * warm, wg = sG[i] * (1 - warm) + warmG * warm, wb = sB[i] * (1 - warm) + warmB * warm;
            const float grey = (wr + wg + wb) / 3.0f;
            r = (grey * 0.15f + wr * 0.85f) * 0.5f * footShade[i];
            g = (grey * 0.15f + wg * 0.85f) * 0.5f * footShade[i];
            b = (grey * 0.15f + wb * 0.85f) * 0.5f * footShade[i];
        } else {
            continue;   // transparent
        }
        const int ri = std::clamp(int(r), 0, 255), gi = std::clamp(int(g), 0, 255), bi = std::clamp(int(b), 0, 255);
        const bool inside = water[i] || main[i];
        const uint32_t alpha = inside ? 255u : uint32_t(std::clamp(fade[i], 0.0f, 1.0f) * 255.0f);
        out[i] = int32_t((alpha << 24) | (uint32_t(ri) << 16) | (uint32_t(gi) << 8) | uint32_t(bi));
    }

    meta[0] = float(W); meta[1] = float(H); meta[2] = minX; meta[3] = minZ; meta[4] = scale;
    return true;
}

// Whether some walkable floor lies directly above (x, z), more than 200 units over y: Banjo is
// under another floor (a lower storey), so the map should show his layer.
bool bt_floor_above(const uint8_t* rdram, float x, float y, float z) {
    const Ram ram{ rdram };
    std::vector<Floor> floors;
    if (!collectFloors(ram, floors)) return false;
    for (const Floor& f : floors) {
        const float* a = f.p[0]; const float* b = f.p[1]; const float* c = f.p[2];
        const float d = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2]);
        if (std::fabs(d) < 1e-6f) continue;
        const float l0 = ((b[2] - c[2]) * (x - c[0]) + (c[0] - b[0]) * (z - c[2])) / d;
        const float l1 = ((c[2] - a[2]) * (x - c[0]) + (a[0] - c[0]) * (z - c[2])) / d;
        const float l2 = 1.0f - l0 - l1;
        if (l0 < 0 || l1 < 0 || l2 < 0) continue;
        if (l0 * a[1] + l1 * b[1] + l2 * c[1] > y + 200.0f) return true;
    }
    return false;
}

#ifndef BT_MAP_TEST
// GameState.buildMap: runs on a background thread. capY: leave out what's entirely above it
// (a lower storey's map), or >= 1e8 for the whole area.
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeBuildMap(JNIEnv* env, jclass, jint maxSize, jfloat capY, jfloatArray info) {
    uint8_t* rdram = bt_android_running_rdram();
    if (rdram == nullptr || info == nullptr || env->GetArrayLength(info) < 5) return nullptr;
    std::vector<int32_t> out;
    float meta[5];
    if (!bt_build_map(rdram, maxSize, capY, out, meta)) return nullptr;
    jintArray pixels = env->NewIntArray(jsize(out.size()));
    if (pixels == nullptr) return nullptr;
    env->SetIntArrayRegion(pixels, 0, jsize(out.size()), reinterpret_cast<const jint*>(out.data()));
    env->SetFloatArrayRegion(info, 0, 5, meta);
    return pixels;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeFloorAbove(JNIEnv*, jclass, jfloat x, jfloat y, jfloat z) {
    uint8_t* rdram = bt_android_running_rdram();
    return rdram != nullptr && bt_floor_above(rdram, x, y, z) ? JNI_TRUE : JNI_FALSE;
}

// The area's name as the game writes it on entering (0x80191560, found 12 Nov: "SPIRAL
// MOUNTAIN", "BANJO'S HOUSE", "ISLE O' HAGS ( JINJO VILLAGE )"); null when it isn't there
// (it isn't after loading a save) or doesn't look like a name.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeAreaName(JNIEnv* env, jclass) {
    uint8_t* rdram = bt_android_running_rdram();
    if (rdram == nullptr) return nullptr;
    const Ram ram{ rdram };
    char name[64];
    int n = 0;
    for (; n < 63; n++) {
        const char c = char(ram.u8(0x80191560u + n));
        if (c == 0) break;
        const bool ok = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ' || c == '\'' || c == '(' || c == ')' || c == '-' || c == '.' || c == '&' || c == '!';
        if (!ok) return nullptr;
        name[n] = c;
    }
    if (n < 4 || n >= 63) return nullptr;
    name[n] = 0;
    return env->NewStringUTF(name);
}
#endif
