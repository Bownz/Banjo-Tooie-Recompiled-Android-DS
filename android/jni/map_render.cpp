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
//    fading out into the (transparent) background, with a soft shadow under the play area's
//    edge; the bottom screen draws a slate grey behind it.
//
// Runs on a background thread (GameState.buildMap), once per area.

#ifndef BT_MAP_TEST
#include <jni.h>
#endif

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
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
        float f32(uint32_t a) const { const uint32_t v = u32(a); float f; std::memcpy(&f, &v, 4); return f; }
    };

    struct Rgba { float r, g, b, a; };

    struct Texture {
        int w = 0, h = 0;
        std::vector<Rgba> px;
        bool bluish = false;   // average colour clearly blue: water
        float avgR = 0, avgG = 0, avgB = 0;   // average colour (opaque texels)
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
        int w = 0, h = 0;   // tile size (G_SETTILESIZE), for clamping
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
        int group = -1;   // a selector child of the model's geometry layout, else -1
    };

    // The model's geometry layout (BK format): a tree of nodes { u32 cmd, u32 next sibling
    // offset, ... } whose draw nodes (cmd 3: u16 first command) each start a segment of the
    // display list that runs to the next end marker (checked: all 366 draw nodes in Spiral
    // Mountain start right after one). Selector nodes (cmd 0x0C: u16 child count, u16 selector id, u32 child
    // offsets) hold alternative versions that the game switches while playing, e.g. Spiral
    // Mountain's moat area with the bridge whole (selector 1) or broken (selector 2). Labels
    // each display-list command with the selector child it's under (-1: always drawn).
    void layoutGroups(const Ram& ram, uint32_t base, uint32_t gfx, uint32_t commands, std::vector<int>& group) {
        group.assign(commands, -1);
        const uint32_t geo = ram.u32(base + 0x04);
        if (geo == 0 || !ram.ok(base + geo, 8)) return;
        int nextGroup = 0;
        int budget = 20000;
        auto walk = [&](auto&& self, uint32_t node, int label, int depth) -> void {
            while (node != 0 && budget-- > 0 && depth < 32 && ram.ok(node, 0x18)) {
                const uint32_t cmd = ram.u32(node), next = ram.u32(node + 4);
                if (cmd > 0x20) return;   // not a node: stop
                switch (cmd) {
                    case 0x03: {
                        for (uint32_t i = ram.u16(node + 8); i < commands && (ram.u32(gfx + 8 + 8 * i) >> 24) != 0xDF; i++) group[i] = label;
                        break;
                    }
                    case 0x0C: {
                        const int children = ram.u16(node + 8);
                        for (int c = 0; c < children && c < 16; c++) {
                            const uint32_t offset = ram.u32(node + 12 + 4u * c);
                            if (offset >= 0x10 && offset < 0x100000) self(self, node + offset, nextGroup++, depth + 1);
                        }
                        break;
                    }
                    case 0x0D: {   // bounding box / draw distance: child at the given offset
                        const uint32_t child = ram.u16(node + 0x14);
                        if (child >= 0x18) self(self, node + child, label, depth + 1);
                        break;
                    }
                    case 0x01: {   // sort: two children (offsets at 0x22 and 0x24)
                        const uint32_t a = ram.u16(node + 0x22), b = ram.u32(node + 0x24);
                        if (a >= 0x10 && a < 0x100000) self(self, node + a, label, depth + 1);
                        if (b >= 0x10 && b < 0x100000) self(self, node + b, label, depth + 1);
                        break;
                    }
                    default: break;
                }
                if (next == 0) break;
                node += next;
            }
        };
        walk(walk, base + geo, -1, 0);
    }

    // N64 texture addressing: clamp (cm bit 2, or no mask) to the tile's size first, then
    // wrap or mirror (bit 1) by the mask. Jinjo Village's path overlay clamps across its width
    // (coordinates 0-127 on a 64-texel tile); wrapping it drew every path twice.
    int wrapCoord(float c, int size, int cm, int mask, int tileSize) {
        long v = static_cast<long>(std::floor(c));
        if (((cm & 2) || mask == 0) && tileSize > 0) v = std::clamp<long>(v, 0, tileSize - 1);
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
        // Lists whose entries are texture ids (Isle o' Hags, 12 Nov) rather than offsets: the
        // textures are packed in list order, a mipmapped one (type bit 0x8000) followed by its
        // smaller levels (half its size again). Checked: the packed total matches the list's
        // header exactly (Jinjo Village 0x33FC0); the offsets read as ids put the 32x32 path
        // texture at a 64x64 entry's place, so paths were drawn twice across.
        bool packed = ram.u32(texList + 8) != 0;
        {
            uint32_t total = 0;
            for (int k = 0; k < texCount; k++) {
                const uint32_t word = ram.u32(texList + 12 + 8u * k);
                const int type = (word >> 16) & 0xFF, w = (word >> 8) & 0xFF, h = word & 0xFF;
                const int bits = type == 0x01 ? 4 : type == 0x02 ? 8 : type == 0x04 ? 16 : type == 0x08 ? 32 : type == 0x10 ? 8 : 0;
                const uint32_t pixels = uint32_t(w * h * bits / 8);
                total += (type == 0x01 ? 0x20u : type == 0x02 ? 0x200u : 0u) + pixels + ((word & 0x80000000u) ? pixels / 2 : 0u);
            }
            if (packed && total != ram.u32(texList)) packed = false;
        }
        uint32_t packedAt = 0;
        for (int k = 0; k < texCount; k++) {
            const uint32_t entry = texList + 8 + 8u * k;
            const uint32_t offset = ram.u32(entry);
            const uint32_t word = ram.u32(entry + 4);
            const int type = (word >> 16) & 0x7FFF;
            const int w = (word >> 8) & 0xFF, h = word & 0xFF;
            int fmt = -1, siz = 0, bits = 0;
            uint32_t palette = 0;
            switch (type & 0xFF) {
                case 0x01: fmt = 2; siz = 0; bits = 4; palette = 0x20; break;    // CI4
                case 0x02: fmt = 2; siz = 1; bits = 8; palette = 0x200; break;   // CI8
                case 0x04: fmt = 0; siz = 2; bits = 16; break;   // RGBA16
                case 0x08: fmt = 0; siz = 3; bits = 32; break;   // RGBA32
                case 0x10: fmt = 3; siz = 1; bits = 8; break;    // IA8
                default: break;
            }
            const uint32_t at = texData + (packed ? packedAt : offset);
            const uint32_t pixels = uint32_t(w * h * bits / 8);
            packedAt += palette + pixels + ((word & 0x80000000u) ? pixels / 2 : 0u);
            const Info info{ w, h, fmt, siz };
            sizes.try_emplace(at + palette, info);   // the texels (after a palette)
        }

        auto segment = [&](uint32_t a) -> uint32_t {
            const uint32_t s = (a >> 24) & 0xF, o = a & 0xFFFFFF;
            if (s == 1) return vtxData + o;
            if (s == 2) return texData + o;
            return 0;
        };

        std::vector<int> groups;
        layoutGroups(ram, base, gfx, commands, groups);
        int currentGroup = -1;
        const int groupBase = water ? 1000 : 0;   // keeps the two models' groups apart

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
                                 int((w1 >> 4) & 15), int((w1 >> 14) & 15), int(w1 & 15), int((w1 >> 10) & 15), tw, th };
                        dirty = true;
                    }
                    break;
                case 0xF2:
                    if (((w1 >> 24) & 7) == 0) {
                        uls = ((w0 >> 12) & 0xFFF) / 4.0f;
                        ult = (w0 & 0xFFF) / 4.0f;
                        tw = int(((w1 >> 12) & 0xFFF) / 4.0f - uls) + 1;
                        th = int((w1 & 0xFFF) / 4.0f - ult) + 1;
                        tile.w = tw; tile.h = th;
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
                                double ar = 0, ag = 0, ab = 0, n = 0;
                                for (const Rgba& c : t.px) if (c.a > 128) { ar += c.r; ag += c.g; ab += c.b; n++; }
                                if (n > 0) { t.avgR = float(ar / n); t.avgG = float(ag / n); t.avgB = float(ab / n); }
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
                        tri.group = currentGroup;
                        tri.water = water;   // checked against the texture and height later
                        tris.push_back(tri);
                    }
                    break;
                }
                default: break;
            }
        };

        for (uint32_t i = 0; i < commands; i++) {
            currentGroup = groups[i] < 0 ? -1 : groupBase + groups[i];
            const uint32_t a = gfx + 8 + 8 * i;
            const uint32_t w0 = ram.u32(a), w1 = ram.u32(a + 4);
            if ((w0 >> 24) == 0xDE && (w1 >> 24) == 3) {
                const uint32_t sub = kSegment3 + (w1 & 0xFFFFFF);
                for (int k = 0; k < 64 && ram.ok(sub + 8u * k, 8); k++) {
                    const uint32_t x0 = ram.u32(sub + 8u * k), x1 = ram.u32(sub + 8u * k + 4);
                    if ((x0 >> 24) == 0xDF) break;
                    // Not its texture scale: the game rewrites these shared lists while it runs,
                    // so what's in memory isn't what applied to this level's passes. Taking the
                    // 1.0 found there drew Isle o' Hags' path overlays at double scale, so only a
                    // quarter of each crossing showed and the path pieces didn't join (12 Nov).
                    if ((x0 >> 24) == 0xD7) continue;
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
        // The vertex list's count (s16 at +0x14) covers the drawn vertices only: collision can
        // use vertices after them (Bottles' house: indices up to 5357 with a count of 4617, and
        // its middle room's floors were all past the count, so the room was missing). Any index
        // whose vertex lies in RAM is used.
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
                const int i = ram.u16(a + 2 * k);
                if (i >= 0x8000 || !ram.ok(vtxList + 0x18 + 16u * uint32_t(i), 16)) { ok = false; break; }
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
    // The model's up-facing triangles from above (step 1 of the map), into a W x H raster
    // whose pixel (0, 0) is world (minX, minZ): textures x vertex colour x a little slope
    // light; the highest surface wins; see-through texels over the same surface blend in.
    void rasterTop(const std::vector<Tri>& tris, float minX, float minZ, float scale, int W, int H,
                   std::vector<float>& R, std::vector<float>& G, std::vector<float>& B, std::vector<float>& top,
                   std::vector<uint8_t>& drawn, std::vector<uint8_t>& water, const std::vector<float>* limit = nullptr) {
        constexpr float lx = -0.47f, ly = 0.71f, lz = -0.52f;
        for (const Tri& t : tris) {
            float X[3], Z[3];
            for (int k = 0; k < 3; k++) { X[k] = (t.v[k].x - minX) * scale; Z[k] = (t.v[k].z - minZ) * scale; }
            const float ax = t.v[1].x - t.v[0].x, ay = t.v[1].y - t.v[0].y, az = t.v[1].z - t.v[0].z;
            const float bx = t.v[2].x - t.v[0].x, by = t.v[2].y - t.v[0].y, bz = t.v[2].z - t.v[0].z;
            const float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            const float len = std::sqrt(nx * nx + ny * ny + nz * nz);
            if (len <= 0 || ny / len < 0.2f) continue;
            // Steep faces seen from above stretch their texture into streaks: draw them in the
            // texture's average colour instead, darker the steeper they are (a cliff look).
            const float upness = ny / len;
            const bool steep = upness < 0.6f && t.tex != nullptr && !t.water;
            const float steepShade = steep ? 0.55f + 0.45f * (upness - 0.2f) / 0.4f : 1.0f;
            // A little light by each triangle's slope (gentle: the hill shading does the rest, and
            // a strong per-triangle light shows the facets in close-ups).
            const float light = 0.86f + 0.18f * std::max(0.0f, (nx * lx + ny * ly + nz * lz) / len);
            raster(X, Z, W, H, [&](int i, float l0, float l1, float l2) {
                const float y = l0 * t.v[0].y + l1 * t.v[1].y + l2 * t.v[2].y;
                if (y < top[i] || (limit != nullptr && y > (*limit)[i])) return;
                float r = 200, g = 200, b = 200, alpha = 1.0f;
                if (t.tex != nullptr) {
                    const float s = l0 * t.v[0].s + l1 * t.v[1].s + l2 * t.v[2].s;
                    const float tt = l0 * t.v[0].t + l1 * t.v[1].t + l2 * t.v[2].t;
                    // Bilinear between the four nearest texels (as the N64 filters), so
                    // magnified textures in close-ups are smooth rather than blocky.
                    const float fs = s - 0.5f, ft = tt - 0.5f;
                    const float s0 = std::floor(fs), t0 = std::floor(ft);
                    const float ws = fs - s0, wt = ft - t0;
                    // A tile bigger than its texture is a leftover size (mipmapped textures: the
                    // texture repeats at its own size), so its clamp doesn't apply either.
                    // Clamping there stretched one edge column into stripes (Spiral Mountain's
                    // hill and slopes: 32x32 textures with a 64x64 clamped tile).
                    const int tws = std::min(t.tile.w, t.tex->w), twt = std::min(t.tile.h, t.tex->h);
                    const int cms = t.tile.w > t.tex->w ? (t.tile.cms & 1) : t.tile.cms;
                    const int cmt = t.tile.h > t.tex->h ? (t.tile.cmt & 1) : t.tile.cmt;
                    const int sa = wrapCoord(s0, t.tex->w, cms, t.tile.ms, tws), sb = wrapCoord(s0 + 1, t.tex->w, cms, t.tile.ms, tws);
                    const int ta = wrapCoord(t0, t.tex->h, cmt, t.tile.mt, twt), tb = wrapCoord(t0 + 1, t.tex->h, cmt, t.tile.mt, twt);
                    const Rgba& c00 = t.tex->px[size_t(ta) * t.tex->w + sa];
                    const Rgba& c10 = t.tex->px[size_t(ta) * t.tex->w + sb];
                    const Rgba& c01 = t.tex->px[size_t(tb) * t.tex->w + sa];
                    const Rgba& c11 = t.tex->px[size_t(tb) * t.tex->w + sb];
                    const float w00 = (1 - ws) * (1 - wt), w10 = ws * (1 - wt), w01 = (1 - ws) * wt, w11 = ws * wt;
                    const float ca = c00.a * w00 + c10.a * w10 + c01.a * w01 + c11.a * w11;
                    if (ca <= 8) return;
                    if (steep) {
                        r = t.tex->avgR * steepShade; g = t.tex->avgG * steepShade; b = t.tex->avgB * steepShade;
                    } else {
                        r = c00.r * w00 + c10.r * w10 + c01.r * w01 + c11.r * w11;
                        g = c00.g * w00 + c10.g * w10 + c01.g * w01 + c11.g * w11;
                        b = c00.b * w00 + c10.b * w10 + c01.b * w01 + c11.b * w11;
                    }
                    alpha = ca / 255.0f;
                }
                // The level's baked vertex shading, at 60% strength: in full it shows as light and
                // dark patches across open ground (Jinjo Village's grass) once the map is sharp.
                const float sr = 0.4f + 0.6f * (l0 * t.v[0].r + l1 * t.v[1].r + l2 * t.v[2].r);
                const float sg = 0.4f + 0.6f * (l0 * t.v[0].g + l1 * t.v[1].g + l2 * t.v[2].g);
                const float sb = 0.4f + 0.6f * (l0 * t.v[0].b + l1 * t.v[1].b + l2 * t.v[2].b);
                // A see-through texel over the same surface (Isle o' Hags' soft-edged path overlays,
                // drawn in several passes over the grass) blends in by its alpha, so where two path
                // pieces meet in their faded ends they still join up.
                const bool over = drawn[i] && alpha < 0.98f && y - top[i] < 30.0f;
                if (!over && alpha <= 0.125f) return;
                // Stronger for paths so they stay readable in the whole-area view; dark overlays
                // (the soft shadows under rocks and posts) keep the gentler blend, or they turn
                // into solid black blobs.
                const bool dark = 0.3f * r + 0.59f * g + 0.11f * b < 70.0f;
                const float k = over ? std::min(1.0f, alpha * (dark ? 1.6f : 2.4f)) : 1.0f;
                top[i] = std::max(top[i], y);
                R[i] = R[i] * (1 - k) + r * sr * light * k;
                G[i] = G[i] * (1 - k) + g * sg * light * k;
                B[i] = B[i] * (1 - k) + b * sb * light * k;
                drawn[i] = 1;
                water[i] = t.water ? 1 : 0;
            });
        }
    }

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
namespace {
    // How each kind of pixel is coloured from its texture colour (shared by the whole-area map
    // and the close-up detail tiles).
    constexpr float kWarmR = 235, kWarmG = 190, kWarmB = 120;
    constexpr float kWarm = 0.12f;         // a light warm tint keeps colours distinct
    constexpr float kSaturation = 1.22f;

    void waterColour(float tr, float tg, float tb, bool shore, float& r, float& g, float& b) {
        // Water: its own texture pulled towards a clear blue, no warm tint, a light shoreline.
        r = tr * 0.5f + 60 * 0.5f;
        g = tg * 0.5f + 135 * 0.5f;
        b = tb * 0.5f + 225 * 0.5f;
        if (shore) {
            r = r * 0.3f + 205 * 0.7f; g = g * 0.3f + 238 * 0.7f; b = b * 0.3f + 255 * 0.7f;
        }
    }

    // Ground and raised scenery: warm tint, more saturation, times the light (height, foot
    // lines, hill shading); the outline where the map ends.
    void groundColour(float tr, float tg, float tb, float light, bool outline, float& r, float& g, float& b) {
        r = tr * (1 - kWarm) + kWarmR * kWarm;
        g = tg * (1 - kWarm) + kWarmG * kWarm;
        b = tb * (1 - kWarm) + kWarmB * kWarm;
        const float grey = (r + g + b) / 3.0f;
        r = (grey + (r - grey) * kSaturation) * light;
        g = (grey + (g - grey) * kSaturation) * light;
        b = (grey + (b - grey) * kSaturation) * light;
        if (outline) {
            r = r * 0.1f + 255 * 0.9f; g = g * 0.1f + 236 * 0.9f; b = b * 0.1f + 190 * 0.9f;
        }
    }

    // Beyond the area's edge: its own colours, a little greyer, times the light (which
    // includes the darkening).
    void beyondColour(float tr, float tg, float tb, float light, float& r, float& g, float& b) {
        const float wr = tr * (1 - kWarm) + kWarmR * kWarm, wg = tg * (1 - kWarm) + kWarmG * kWarm, wb = tb * (1 - kWarm) + kWarmB * kWarm;
        const float grey = (wr + wg + wb) / 3.0f;
        r = (grey * 0.15f + wr * 0.85f) * light;
        g = (grey * 0.15f + wg * 0.85f) * light;
        b = (grey * 0.15f + wb * 0.85f) * light;
    }

    int32_t packPixel(float r, float g, float b, float a, float k) {
        const int ri = std::clamp(int(r * k), 0, 255), gi = std::clamp(int(g * k), 0, 255), bi = std::clamp(int(b * k), 0, 255);
        const uint32_t alpha = uint32_t(std::clamp(a, 0.0f, 1.0f) * 255.0f);
        return int32_t((alpha << 24) | (uint32_t(ri) << 16) | (uint32_t(gi) << 8) | uint32_t(bi));
    }

    // The last whole-area map, kept so close-up detail tiles can be drawn from it: the
    // triangles and textures, and per map pixel what kind it is and how it's lit. Detail
    // tiles redraw only the textures, sharper, and take the rest from here.
    enum : uint8_t { kNone = 0, kWater = 1, kMain = 2, kSolid = 3, kBeyond = 4 };
    struct MapCache {
        bool valid = false;
        uint32_t key = 0;
        std::vector<Tri> tris;
        std::map<std::tuple<uint32_t, int, int, int, int, uint32_t, bool>, Texture> textures;
        int W = 0, H = 0;
        float minX = 0, minZ = 0, scale = 1;
        std::vector<uint8_t> kind, edge, light, alpha, colourK;   // light x 100; alpha, k x 255
        std::vector<int32_t> backdrop;   // the glow behind the map (bt_backdrop)
        int bdW = 0, bdH = 0;
        float bdMinX = 0, bdMinZ = 0, bdScale = 1;
        std::vector<int16_t> groundY;    // walkable ground height per pixel; kNoGround where none
        std::vector<int16_t> roofCap;    // under a roof: draw nothing above this; kNoGround elsewhere
        uint16_t mapId = 0;
    };
    constexpr int16_t kNoGround = INT16_MIN;
    MapCache g_cache;
    std::mutex g_cacheMutex;
    // Places Banjo has stood on that the play area rule left out (ground well above the main
    // level: the top tier of Mayahem Temple). Per map id, for this run of the app; each map
    // built adds the ground around them back (see bt_map_wants_seed).
    std::map<uint16_t, std::vector<std::array<float, 3>>> g_seeds;
}

bool bt_build_map_keyed(const uint8_t* rdram, int maxSize, float capY, uint32_t key, std::vector<int32_t>& out, float meta[5]) {
    const Ram ram{ rdram };

    std::vector<Tri> tris;
    std::map<std::tuple<uint32_t, int, int, int, int, uint32_t, bool>, Texture> textures;
    std::vector<Floor> floors;
    if (!collect(ram, kModelPtr, kTextureBlockPtr, false, tris, textures) || !collectFloors(ram, floors)) return false;
    // Alternative versions in the model (selector children): keep the ones the level's
    // collision agrees with. A version's ground that has no collision floor at its height
    // under it isn't there right now (Spiral Mountain's whole bridge, when it's broken).
    {
        std::map<int, std::pair<float, float>> score;   // group -> matched area, total area
        for (const Tri& t : tris) {
            if (t.group < 0) continue;
            const float ax = t.v[1].x - t.v[0].x, ay = t.v[1].y - t.v[0].y, az = t.v[1].z - t.v[0].z;
            const float bx = t.v[2].x - t.v[0].x, by = t.v[2].y - t.v[0].y, bz = t.v[2].z - t.v[0].z;
            const float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            const float len = std::sqrt(nx * nx + ny * ny + nz * nz);
            if (len <= 0 || ny / len < 0.6f) continue;
            const float area = len / 2;
            const float cx = (t.v[0].x + t.v[1].x + t.v[2].x) / 3, cy = (t.v[0].y + t.v[1].y + t.v[2].y) / 3, cz = (t.v[0].z + t.v[1].z + t.v[2].z) / 3;
            bool matched = false;
            for (const Floor& f : floors) {
                const float* a = f.p[0]; const float* b = f.p[1]; const float* c = f.p[2];
                if (cx < std::min({ a[0], b[0], c[0] }) || cx > std::max({ a[0], b[0], c[0] }) ||
                    cz < std::min({ a[2], b[2], c[2] }) || cz > std::max({ a[2], b[2], c[2] })) continue;
                const float d = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2]);
                if (std::fabs(d) < 1e-6f) continue;
                const float l0 = ((b[2] - c[2]) * (cx - c[0]) + (c[0] - b[0]) * (cz - c[2])) / d;
                const float l1 = ((c[2] - a[2]) * (cx - c[0]) + (a[0] - c[0]) * (cz - c[2])) / d;
                const float l2 = 1.0f - l0 - l1;
                if (l0 < -0.01f || l1 < -0.01f || l2 < -0.01f) continue;
                if (std::fabs(l0 * a[1] + l1 * b[1] + l2 * c[1] - cy) < 40.0f) { matched = true; break; }
            }
            auto& sc = score[t.group];
            sc.second += area;
            if (matched) sc.first += area;
        }
        std::set<int> drop;
        for (const auto& [g, sc] : score) {
            const bool keep = sc.second <= 0 || sc.first >= 0.5f * sc.second;
#ifdef BT_MAP_TEST
            std::fprintf(stderr, "group %d: %.0f of %.0f matched -> %s\n", g, sc.first, sc.second, keep ? "kept" : "left out");
#endif
            if (!keep) drop.insert(g);
        }
        if (!drop.empty()) {
            tris.erase(std::remove_if(tris.begin(), tris.end(), [&](const Tri& t) { return drop.count(t.group) != 0; }), tris.end());
        }
    }

    // A floor layer (Banjo under another floor, capY < 1e8): handled per pixel below, where
    // the floors are rastered. 0.9.12 left out everything above capY across the whole area, so
    // in Spiral Mountain a small nook under the mountain's slope took the lake island's top,
    // the ruined lair and the high ledges with it (a hole in the middle of the map, 13 Nov).
    const bool layer = capY < 1e8f;
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
    rasterTop(tris, minX, minZ, scale, W, H, R, G, B, top, drawn, water);

    // 2. Walkable ground: the highest collision floor per pixel.
    std::vector<float> ground(N, -1e9f), lowest(N, 1e9f);   // highest and lowest floor per pixel
    std::vector<float> groundLow(layer ? N : 0, -1e9f);     // a floor layer: highest floor <= capY
    for (const Floor& f : floors) {
        float X[3], Z[3];
        for (int k = 0; k < 3; k++) { X[k] = (f.p[k][0] - minX) * scale; Z[k] = (f.p[k][2] - minZ) * scale; }
        raster(X, Z, W, H, [&](int i, float l0, float l1, float l2) {
            const float y = l0 * f.p[0][1] + l1 * f.p[1][1] + l2 * f.p[2][1];
            if (y > ground[i]) ground[i] = y;
            if (y < lowest[i]) lowest[i] = y;
            if (layer && y <= capY && y > groundLow[i]) groundLow[i] = y;
        });
    }
    // A floor layer: only where a floor at or below capY lies under something well above it
    // (the floor or roof over Banjo) is the map drawn at that floor; everywhere else it's the
    // whole-area map.
    std::vector<float> layerLimit;
    if (layer) {
        layerLimit.assign(N, 1e9f);
        size_t n = 0;
        for (size_t i = 0; i < N; i++)
            if (groundLow[i] > -1e8f && !water[i] && (ground[i] - groundLow[i] > 300.0f || (drawn[i] && top[i] - groundLow[i] > 300.0f))) {
                ground[i] = groundLow[i];
                layerLimit[i] = capY;
                n++;
            }
        if (n == 0) return false;
        std::vector<float> R2(N, 0), G2(N, 0), B2(N, 0), top2(N, -1e9f);
        std::vector<uint8_t> drawn2(N, 0), water2(N, 0);
        rasterTop(tris, minX, minZ, scale, W, H, R2, G2, B2, top2, drawn2, water2, &layerLimit);
        for (size_t i = 0; i < N; i++)
            if (layerLimit[i] < 1e8f) {
                R[i] = R2[i]; G[i] = G2[i]; B[i] = B2[i]; top[i] = top2[i]; water[i] = water2[i]; drawn[i] = drawn2[i];
            }
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
    // Ground under a roof: where the surface seen from above is far above the walkable floor
    // with nothing walkable on top (a tunnel through a hill, a path under a rock arch: the
    // passage under Jinjo Village), draw what's at the floor's level instead of the roof.
    std::vector<int16_t> roofCap(N, kNoGround);
    if (layer)
        for (size_t i = 0; i < N; i++)
            if (layerLimit[i] < 1e8f) roofCap[i] = int16_t(std::clamp(layerLimit[i], -32000.0f, 32000.0f));
    {
        std::vector<float> limit(N, 1e9f);
        size_t roofed = 0;
        for (size_t i = 0; i < N; i++)
            if (walk[i] && !water[i] && ground[i] > -1e8f && top[i] - ground[i] > 600.0f) {
                limit[i] = ground[i] + 250.0f;
                roofCap[i] = int16_t(std::clamp(limit[i], -32000.0f, 32000.0f));
                roofed++;
            }
        if (roofed > 0) {
            std::vector<float> R2(N, 0), G2(N, 0), B2(N, 0), top2(N, -1e9f);
            std::vector<uint8_t> drawn2(N, 0), water2(N, 0);
            rasterTop(tris, minX, minZ, scale, W, H, R2, G2, B2, top2, drawn2, water2, &limit);
            for (size_t i = 0; i < N; i++)
                if (limit[i] < 1e8f && drawn2[i]) {
                    R[i] = R2[i]; G[i] = G2[i]; B[i] = B2[i]; top[i] = top2[i]; water[i] = water2[i];
                }
        }
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
    // Raised ground cut off from the floor you walk on (a sharp step all round) and standing at
    // the area's edge: the tops of hedges and trees around Jinjo Village have walkable floors,
    // so they counted as play area and showed as jagged green spikes. Pieces of the play area
    // split by steps of more than a gentle slope are found; every piece but the biggest that
    // borders empty space and is clearly higher than the floor next to it leaves the play area
    // (drawn as surroundings). Raised pieces inside the area (tables, ledges) stay, or become
    // raised scenery below.
    {
        const float tol = std::max(60.0f, 2.5f / scale);   // a step bigger than a 2:1 slope over a pixel or two
        std::vector<int> part(N, -1), sizes, stack;
        for (size_t start = 0; start < N; start++) {
            if (!main[start] || part[start] >= 0) continue;
            const int id = int(sizes.size());
            int count = 0;
            stack.assign(1, int(start));
            part[start] = id;
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                count++;
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (main[j] && part[j] < 0 && std::fabs(ground[j] - ground[i]) < tol) { part[j] = id; stack.push_back(int(j)); }
                }
            }
            sizes.push_back(count);
        }
        if (!sizes.empty()) {
            const int floorPart = int(std::max_element(sizes.begin(), sizes.end()) - sizes.begin());
            // Per piece: does it touch empty space, and how much higher is it than the floor beside it?
            std::vector<uint8_t> touchesEmpty(sizes.size(), 0);
            std::vector<double> rise(sizes.size(), 0.0);
            std::vector<int> riseN(sizes.size(), 0), edgeN(sizes.size(), 0);
            for (int z = 0; z < H; z++)
                for (int x = 0; x < W; x++) {
                    const size_t i = size_t(z) * W + x;
                    if (part[i] < 0 || part[i] == floorPart) continue;
                    const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                    for (const auto& n : next) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) { touchesEmpty[size_t(part[i])] = 1; continue; }
                        const size_t j = size_t(n[1]) * W + n[0];
                        if (!drawn[j] && !walk[j] && !water[j]) touchesEmpty[size_t(part[i])] = 1;
                        if (part[j] != part[i]) edgeN[size_t(part[i])]++;
                        if (part[j] == floorPart) { rise[size_t(part[i])] += ground[i] - ground[j]; riseN[size_t(part[i])]++; }
                    }
                }
            for (size_t i = 0; i < N; i++) {
                const int k = part[i];
                if (k < 0 || k == floorPart) continue;
                const bool higher = riseN[size_t(k)] == 0 || rise[size_t(k)] / riseN[size_t(k)] > 150.0;
                // A boulder at the edge stands mostly in the floor; hedge tops mostly not.
                const bool ringed = riseN[size_t(k)] * 2 > edgeN[size_t(k)];
                // Only pieces small next to the floor (Jinjo Village's were at most 9%): a whole
                // raised level is part of the area (Mayahem Temple's plateau by Jamjars, 47%,
                // vanished in 0.8.9-0.9.4).
                const bool small = sizes[size_t(k)] * 100 < sizes[size_t(floorPart)] * 15;
                if (touchesEmpty[size_t(k)] && higher && !ringed && small) main[i] = 0;
            }
        }
    }
    // Steep ground at the area's edge: the walkable foot of a cliff or hillside rising out of
    // the area (left of Jinjo Village) showed as a floating shard of leaves. Near the edge,
    // ground steeper than about 30 degrees leaves the play area; slopes further in stay.
    {
        // Outside: off the play area and reachable from the map's border (holes inside the area,
        // where rocks and posts stand, don't count).
        std::vector<uint8_t> outside(N, 0);
        {
            std::vector<int> stack;
            auto open = [&](int x, int z) {
                const size_t j = size_t(z) * W + x;
                if (!main[j] && !water[j] && !outside[j]) { outside[j] = 1; stack.push_back(int(j)); }
            };
            for (int x = 0; x < W; x++) { open(x, 0); open(x, H - 1); }
            for (int z = 0; z < H; z++) { open(0, z); open(W - 1, z); }
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                const int x = i % W, z = i / W;
                if (x > 0) open(x - 1, z);
                if (x + 1 < W) open(x + 1, z);
                if (z > 0) open(x, z - 1);
                if (z + 1 < H) open(x, z + 1);
            }
        }
        const std::vector<uint8_t> nearEdge = morph(outside, W, H, 12 * px, true);
        const float limit = 0.6f * 2.0f / scale;   // rise over two pixels at a 0.6 gradient
        std::vector<uint8_t> steep(N, 0);
        for (int z = 1; z + 1 < H; z++)
            for (int x = 1; x + 1 < W; x++) {
                const size_t i = size_t(z) * W + x;
                if (!main[i] || !nearEdge[i]) continue;
                const size_t l = i - 1, r = i + 1, u = i - W, d = i + W;
                const bool hx = main[l] && main[r], hz = main[u] && main[d];
                if ((hx && std::fabs(ground[r] - ground[l]) > limit) || (hz && std::fabs(ground[d] - ground[u]) > limit)) steep[i] = 1;
            }
        for (size_t i = 0; i < N; i++) if (steep[i]) main[i] = 0;
        // Steep ground that's enclosed by the play area (the sides of a rock on Jinjo Village's
        // beach) goes back: only slopes opening onto the outside leave.
        {
            std::vector<uint8_t> reach(N, 0);
            std::vector<int> stack;
            for (size_t i = 0; i < N; i++) if (outside[i]) { reach[i] = 1; stack.push_back(int(i)); }
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (steep[j] && !reach[j]) { reach[j] = 1; stack.push_back(int(j)); }
                }
            }
            for (size_t i = 0; i < N; i++) if (steep[i] && !reach[i]) main[i] = 1;
        }
        main = morph(morph(main, W, H, px, false), W, H, px, true);   // no stubs left behind
        for (size_t i = 0; i < N; i++) main[i] = main[i] && walk[i];
    }
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
    // Ground Banjo stands on (now, or earlier in this area) is always part of the area: from
    // each such point, the walkable ground joined to it by gentle steps joins the play area.
    {
        const uint16_t mapId = ram.u16(0x80132DC2u);
        std::vector<std::array<float, 3>> seeds;
        {
            std::lock_guard lock{ g_cacheMutex };
            auto it = g_seeds.find(mapId);
            if (it != g_seeds.end()) seeds = it->second;
        }
        seeds.push_back({ ram.f32(0x80136F50u), ram.f32(0x80136F54u), ram.f32(0x80136F58u) });
        const float tol = std::max(60.0f, 2.5f / scale);
        std::vector<int> stack;
        for (const auto& p : seeds) {
            const int x0 = int((p[0] - minX) * scale), z0 = int((p[2] - minZ) * scale);
            if (x0 < 0 || z0 < 0 || x0 >= W || z0 >= H) continue;
            const size_t s0 = size_t(z0) * W + x0;
            if (main[s0] || water[s0] || !walk[s0] || std::fabs(ground[s0] - p[1]) > 150.0f) continue;
            main[s0] = 1;
            stack.assign(1, int(s0));
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (!main[j] && walk[j] && !water[j] && std::fabs(ground[j] - ground[i]) < tol) { main[j] = 1; stack.push_back(int(j)); }
                }
            }
        }
    }
    // Raised ground inside the area is part of it: walkable pieces left out by the height limit
    // that don't reach the area's outside (nothing drawn, reachable from the map's border) are
    // enclosed by it, like Mayahem Temple's central temple and the ground around it (checked
    // against a printed guide map, 13 Nov: the middle of the area came out black). Pieces at
    // the edge (hedge and tree tops, outer hills) touch the outside and stay out.
    {
        std::vector<uint8_t> outside(N, 0);
        std::vector<int> stack;
        auto open = [&](int x, int z) {
            const size_t j = size_t(z) * W + x;
            if (!drawn[j] && !walk[j] && !water[j] && !outside[j]) { outside[j] = 1; stack.push_back(int(j)); }
        };
        for (int x = 0; x < W; x++) { open(x, 0); open(x, H - 1); }
        for (int z = 0; z < H; z++) { open(0, z); open(W - 1, z); }
        while (!stack.empty()) {
            const int i = stack.back();
            stack.pop_back();
            const int x = i % W, z = i / W;
            if (x > 0) open(x - 1, z);
            if (x + 1 < W) open(x + 1, z);
            if (z > 0) open(x, z - 1);
            if (z + 1 < H) open(x, z + 1);
        }
        size_t mainCount = 0;
        for (size_t i = 0; i < N; i++) mainCount += main[i];
        const float tol = std::max(60.0f, 2.5f / scale);
        std::vector<int> part, seen(N, 0);
        for (size_t start = 0; start < N; start++) {
            if (main[start] || !walk[start] || water[start] || seen[start]) continue;
            part.clear();
            bool touches = false;
            stack.assign(1, int(start));
            seen[start] = 1;
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                part.push_back(i);
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) { touches = true; continue; }
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (outside[j]) touches = true;
                    if (!main[j] && walk[j] && !water[j] && !seen[j] && std::fabs(ground[j] - ground[i]) < tol) { seen[j] = 1; stack.push_back(int(j)); }
                }
            }
            if (!touches && part.size() * 200 >= mainCount)   // at least 0.5% of the area
                for (int i : part) main[i] = 1;
        }
    }
    const std::vector<uint8_t> inner = morph(main, W, H, px, false);
    const std::vector<uint8_t> waterInner = morph(water, W, H, px, false);
    // Raised scenery standing in the play area (Spiral Mountain's lair roof, above the play
    // area's height): drawn in full colour like the ground, not faded out like the
    // surroundings. A piece counts when at least 3/4 of its edge borders the play area
    // (the lair roof: all of it; Jinjo Village's cliff walls: about half, still faded).
    std::vector<uint8_t> solid(N, 0);
    {
        std::vector<int> label(N, -1), stack, part;
        for (size_t start = 0; start < N; start++) {
            if (label[start] >= 0 || main[start] || water[start] || !drawn[start]) continue;
            part.clear();
            stack.assign(1, int(start));
            label[start] = 1;
            int inside = 0, outside = 0;
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                part.push_back(i);
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) { outside++; continue; }
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (main[j] || water[j]) { inside++; continue; }
                    if (!drawn[j]) { outside++; continue; }
                    if (label[j] < 0) { label[j] = 1; stack.push_back(int(j)); }
                }
            }
#ifdef BT_MAP_TEST
            if (part.size() > 200) std::fprintf(stderr, "piece %zu px at %d,%d: inside %d outside %d\n", part.size(), part[0] % W, part[0] / W, inside, outside);
#endif
            if (inside >= 3 * outside) for (int i : part) solid[i] = 1;
        }
    }
    // The outline goes only where the play area meets empty space (the map's edge), not
    // along water or raised scenery inside it (Spiral Mountain's lair roof).
    std::vector<uint8_t> nearEmpty(N);
    for (size_t i = 0; i < N; i++) nearEmpty[i] = !(main[i] || water[i] || drawn[i] || walk[i]);
    nearEmpty = morph(nearEmpty, W, H, 2 * px, true);

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
                if (up > jump) f *= 1.0f - 0.35f * std::min(1.0f, (up - jump) / (full - jump) + 0.35f);     // foot: dark line
                else if (down > jump) f *= 1.0f + 0.18f * std::min(1.0f, (down - jump) / (full - jump) + 0.3f);   // top: light edge
                const float around = weight[i] > 0.01f ? avg[i] / weight[i] : h;
                f *= 1.0f - std::clamp((around - h) / 400.0f, 0.0f, 0.14f);   // hollows
                footShade[i] = f;
            }
    }

    // Hill shading: light the surface by its slope, as if the sun were low in the north-west
    // (top left), so hills, ramps and hollows read as 3D. Slopes come from widely blurred
    // surface heights; sharp jumps (walls, ledges) are left to the foot lines above. Gentle
    // (0.84-1.12): 0.8.6's stronger, sharper version showed every flat triangle as a patch.
    std::vector<float> hillShade(N, 1.0f);
    {
        std::vector<float> hs(N), hw(N);
        for (size_t i = 0; i < N; i++) { const bool has = surface[i] > -1e8f; hs[i] = has ? surface[i] : 0; hw[i] = has ? 1.0f : 0.0f; }
        // Heights blurred wide (two passes, ~12 panel pixels), so the shading rolls smoothly
        // instead of changing in steps at each flat triangle's edge.
        boxBlur(hs, W, H, 6 * px); boxBlur(hw, W, H, 6 * px);
        boxBlur(hs, W, H, 6 * px); boxBlur(hw, W, H, 6 * px);
        for (size_t i = 0; i < N; i++) hs[i] = hw[i] > 0.01f ? hs[i] / hw[i] : -1e9f;
        const int d = 4 * px;
        const float dist = 2.0f * d / scale;   // world units between the two samples
        constexpr float sx = -0.55f, sy = 0.62f, sz = -0.55f;   // towards the sun (normalised)
        for (int z = d; z < H - d; z++)
            for (int x = d; x < W - d; x++) {
                const size_t i = size_t(z) * W + x;
                const float h0 = hs[i - d], h1 = hs[i + d], v0 = hs[i - size_t(d) * W], v1 = hs[i + size_t(d) * W];
                if (surface[i] <= -1e8f || h0 <= -1e8f || h1 <= -1e8f || v0 <= -1e8f || v1 <= -1e8f) continue;
                const float gx = std::clamp((h1 - h0) / dist, -1.5f, 1.5f), gz = std::clamp((v1 - v0) / dist, -1.5f, 1.5f);
                const float len = std::sqrt(gx * gx + 1.0f + gz * gz);
                const float lit = (-gx * sx + sy - gz * sz) / len;   // normal (-gx, 1, -gz)
                hillShade[i] = std::clamp(1.0f + 0.5f * (lit - sy), 0.84f, 1.12f);   // gentle
            }
    }

    // How far beyond the area's edge a pixel is: 1 at the edge, fading to 0 about 12 panel
    // pixels out (two box blurs of the walkable/water mask).
    std::vector<float> fade(N);
    for (size_t i = 0; i < N; i++) fade[i] = (main[i] || water[i]) ? 1.0f : 0.0f;
    boxBlur(fade, W, H, 8 * px);
    boxBlur(fade, W, H, 8 * px);
    // A soft shadow under the play area's edge on the (slate grey) background, so the play
    // area looks raised: a wider blur of the same mask.
    std::vector<float> shadow(N);
    for (size_t i = 0; i < N; i++) shadow[i] = fade[i];
    boxBlur(shadow, W, H, 5 * px);
    boxBlur(shadow, W, H, 5 * px);
    for (float& f : fade) f = std::clamp(f * 2.2f, 0.0f, 1.0f) * 0.85f;
    // Surroundings only where they're attached to the play area (connected through drawn
    // pixels): detached bits within the fade distance (a piece of Jinjo Village's cliff wall
    // floating left of the village) are left out.
    {
        std::vector<uint8_t> keep(N, 0);
        std::vector<int> stack;
        auto inside = [&](size_t j) { return main[j] || water[j] || solid[j]; };
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                const size_t i = size_t(z) * W + x;
                if (inside(i) || !(drawn[i] || walk[i]) || fade[i] <= 0.02f) continue;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                    if (inside(size_t(n[1]) * W + n[0])) { keep[i] = 1; stack.push_back(int(i)); break; }
                }
            }
        while (!stack.empty()) {
            const int i = stack.back();
            stack.pop_back();
            const int x = i % W, z = i / W;
            const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
            for (const auto& n : next) {
                if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                const size_t j = size_t(n[1]) * W + n[0];
                if (!keep[j] && !inside(j) && (drawn[j] || walk[j]) && fade[j] > 0.02f) { keep[j] = 1; stack.push_back(int(j)); }
            }
        }
        for (size_t i = 0; i < N; i++)
            if (!inside(i) && !keep[i]) fade[i] = 0.0f;
    }

    // The backdrop behind the map (drawn under it on the bottom screen): the whole level, play
    // area and surroundings, gathered into a coarse grid with a margin all round, then blurred
    // into a soft glow in the area's own colours that fades to black away from it. The
    // surroundings stay out of the map itself (sharp, they looked unfinished); blurred, they
    // fill the black around it.
    const int S = std::max(W, H);
    const int cell = std::max(1, S / 160);
    const int pad = (S * 6 / 10) / cell;
    const int BW = (W + cell - 1) / cell + 2 * pad, BH = (H + cell - 1) / cell + 2 * pad;
    std::vector<float> bdR(size_t(BW) * BH, 0), bdG(bdR.size(), 0), bdB(bdR.size(), 0), bdA(bdR.size(), 0);
    auto gather = [&](size_t i, float r, float g, float b) {
        const int x = int(i % W) / cell + pad, z = int(i / W) / cell + pad;
        const size_t j = size_t(z) * BW + x;
        bdR[j] += r; bdG[j] += g; bdB[j] += b; bdA[j] += 1.0f;
    };

    // Lower levels under the gaps: where the map shows nothing (ground left out, or not drawn)
    // but there's a walkable floor well below what's on top (a path or cave under a hill or a
    // ledge), that floor is drawn instead, darker (user, 13 Nov). Pieces under 0.2% of the area
    // are left out (specks under scenery).
    std::vector<uint8_t> lower(N, 0);
    std::vector<float> lR, lG, lB;
    {
        size_t mainCount = 0;
        for (size_t i = 0; i < N; i++) mainCount += main[i];
        std::vector<uint8_t> cand(N, 0);
        for (size_t i = 0; i < N; i++)
            cand[i] = !main[i] && !water[i] && !solid[i] && lowest[i] < 1e8f && lowest[i] <= ceiling &&
                      (!drawn[i] || top[i] - lowest[i] > 300.0f) && (ground[i] - lowest[i] > 300.0f || !drawn[i]);
        std::vector<int> part, stack;
        std::vector<uint8_t> seen(N, 0);
        for (size_t start = 0; start < N; start++) {
            if (!cand[start] || seen[start]) continue;
            part.clear();
            stack.assign(1, int(start));
            seen[start] = 1;
            while (!stack.empty()) {
                const int i = stack.back();
                stack.pop_back();
                part.push_back(i);
                const int x = i % W, z = i / W;
                const int next[4][2] = { { x - 1, z }, { x + 1, z }, { x, z - 1 }, { x, z + 1 } };
                for (const auto& n : next) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= H) continue;
                    const size_t j = size_t(n[1]) * W + n[0];
                    if (cand[j] && !seen[j]) { seen[j] = 1; stack.push_back(int(j)); }
                }
            }
            if (part.size() * 500 >= mainCount)
                for (int i : part) lower[i] = 1;
        }
        std::vector<float> limit(N, 1e9f);
        bool any = false;
        for (size_t i = 0; i < N; i++)
            if (lower[i]) { limit[i] = lowest[i] + 250.0f; any = true; }
        if (any) {
            lR.assign(N, 0); lG.assign(N, 0); lB.assign(N, 0);
            std::vector<float> lTop(N, -1e9f);
            std::vector<uint8_t> lDrawn(N, 0), lWater(N, 0);
            rasterTop(tris, minX, minZ, scale, W, H, lR, lG, lB, lTop, lDrawn, lWater, &limit);
            for (size_t i = 0; i < N; i++) {
                if (!lower[i]) continue;
                if (!lDrawn[i]) { lower[i] = 0; continue; }
                roofCap[i] = int16_t(std::clamp(limit[i], -32000.0f, 32000.0f));   // close-up tiles too
            }
        }
    }

    // 4. Compose (and keep what a detail tile needs).
    out.assign(N, 0);
    std::vector<uint8_t> kind(N, kNone), edgeOut(N, 0), lightOut(N, 0), alphaOut(N, 0), kOut(N, 0);
    for (size_t i = 0; i < N; i++) {
        float r, g, b, light = 1.0f;
        if (water[i]) {
            kind[i] = kWater;
            waterColour(sR[i], sG[i], sB[i], !waterInner[i], r, g, b);
        } else if (lower[i]) {
            // A lower level under a gap: like the play area, darker, no outline.
            kind[i] = kMain;
            light = 0.62f;
            groundColour(lR[i], lG[i], lB[i], light, false, r, g, b);
        } else if (main[i] || solid[i]) {
            kind[i] = main[i] ? kMain : kSolid;
            const float k = std::clamp((ground[i] - lo) / std::max(1.0f, hi - lo), 0.0f, 1.0f);
            // Height: higher ground a little lighter (it was darker: Spiral Mountain's hill, the
            // tallest thing, came out the darkest); raised scenery keeps its own colours.
            const float f = main[i] ? 0.97f + 0.10f * k : 1.0f;
            light = f * footShade[i] * hillShade[i];
            groundColour(sR[i], sG[i], sB[i], light, main[i] && !inner[i] && nearEmpty[i], r, g, b);
        } else if ((drawn[i] || walk[i]) && fade[i] > 0.02f) {
            kind[i] = kBeyond;
            light = 0.5f * footShade[i] * hillShade[i];
            beyondColour(sR[i], sG[i], sB[i], light, r, g, b);
        } else {
            r = g = b = 0;
            fade[i] = 0;
        }
        // Over the shadow (black): a = content alpha, s = shadow alpha.
        const bool inside = water[i] || main[i] || solid[i] || lower[i];
        if (inside || kind[i] == kBeyond) {
            gather(i, r, g, b);
        } else if (drawn[i] || walk[i]) {
            float br, bg, bb;
            beyondColour(sR[i], sG[i], sB[i], 0.5f * footShade[i] * hillShade[i], br, bg, bb);
            gather(i, br, bg, bb);
        }
        const float a = inside ? 1.0f : std::clamp(fade[i], 0.0f, 1.0f);
        const float sh = inside ? 0.0f : std::clamp(shadow[i] * 0.6f, 0.0f, 0.6f);
        const float total = a + sh * (1.0f - a);
        edgeOut[i] = nearEmpty[i];
        lightOut[i] = uint8_t(std::clamp(light * 100.0f, 0.0f, 255.0f));
        if (total < 1.0f / 255.0f) continue;   // transparent
        alphaOut[i] = uint8_t(std::clamp(total, 0.0f, 1.0f) * 255.0f);
        kOut[i] = uint8_t(std::clamp(a / total, 0.0f, 1.0f) * 255.0f);
        out[i] = packPixel(r, g, b, total, a / total);
    }

    std::vector<int32_t> backdrop(size_t(BW) * BH, 0);
    {
        const float full = float(cell) * float(cell);
        for (size_t j = 0; j < bdA.size(); j++) {   // coverage 0..1 per cell
            bdA[j] /= full; bdR[j] /= full; bdG[j] /= full; bdB[j] /= full;
        }
        // Gaussian-ish blurs (two box passes) at three widths, in cells: colour close in,
        // colour further out (normalised by coverage, so it spreads outwards), and the far glow.
        const float cells = float(S) / float(cell);   // the map's size in cells
        auto blurred = [&](const std::vector<float>& a, float sigma) {
            std::vector<float> b = a;
            const int rr = std::max(1, int(sigma * 1.2f));
            boxBlur(b, BW, BH, rr); boxBlur(b, BW, BH, rr);
            return b;
        };
        const float s1 = 0.011f * cells, s2 = 0.07f * cells, s3 = 0.17f * cells;
        const std::vector<float> a1 = blurred(bdA, s1), r1 = blurred(bdR, s1), g1 = blurred(bdG, s1), b1 = blurred(bdB, s1);
        const std::vector<float> a2 = blurred(bdA, s2), r2 = blurred(bdR, s2), g2 = blurred(bdG, s2), b2 = blurred(bdB, s2);
        const std::vector<float> a3 = blurred(bdA, s3);
        for (size_t j = 0; j < backdrop.size(); j++) {
            const float n1 = std::max(a1[j], 1e-3f), n2 = std::max(a2[j], 1e-3f);
            const float c1 = std::clamp(a1[j] * 1.5f, 0.0f, 1.0f);
            float r = (r1[j] / n1) * c1 + (r2[j] / n2) * (1 - c1);
            float g = (g1[j] / n1) * c1 + (g2[j] / n2) * (1 - c1);
            float b = (b1[j] / n1) * c1 + (b2[j] / n2) * (1 - c1);
            const float grey = (r + g + b) / 3.0f;
            r = r * 0.75f + grey * 0.25f; g = g * 0.75f + grey * 0.25f; b = b * 0.75f + grey * 0.25f;
            // Brighter close to the area (a soft glow at its edge), fading to black further out.
            const float near = std::clamp(a2[j] * 2.0f, 0.0f, 1.0f);
            const float far = std::clamp(a3[j] * 3.0f, 0.0f, 1.0f);
            const float k = (0.5f + 0.3f * near) * far;
            backdrop[j] = packPixel(r, g, b, 1.0f, k);
        }
    }

    {
        std::lock_guard lock{ g_cacheMutex };
        g_cache.backdrop = std::move(backdrop);
        g_cache.bdW = BW; g_cache.bdH = BH;
        g_cache.bdScale = scale / float(cell);
        g_cache.bdMinX = minX - float(pad) / g_cache.bdScale;
        g_cache.bdMinZ = minZ - float(pad) / g_cache.bdScale;
        g_cache.valid = true;
        g_cache.key = key;
        g_cache.tris = std::move(tris);
        g_cache.textures = std::move(textures);   // std::map nodes stay put: the triangles' texture pointers stay valid
        g_cache.W = W; g_cache.H = H; g_cache.minX = minX; g_cache.minZ = minZ; g_cache.scale = scale;
        g_cache.kind = std::move(kind); g_cache.edge = std::move(edgeOut); g_cache.light = std::move(lightOut);
        g_cache.alpha = std::move(alphaOut); g_cache.colourK = std::move(kOut);
        g_cache.groundY.assign(N, kNoGround);
        for (size_t i = 0; i < N; i++) {
            const float g = lower[i] ? lowest[i] : ground[i];   // the level the map shows there
            if (walk[i] && g > -30000.0f && g < 30000.0f) g_cache.groundY[i] = int16_t(std::lround(g));
        }
        g_cache.mapId = ram.u16(0x80132DC2u);
        g_cache.roofCap = std::move(roofCap);
    }

    meta[0] = float(W); meta[1] = float(H); meta[2] = minX; meta[3] = minZ; meta[4] = scale;
    return true;
}

// Whether the last map (with this key) shows, where Banjo is, a level well above him: he's
// under it (at the foot of a cliff under its overhang, in a passage under a hill) and the map
// should switch to the level he's on. Catches what bt_floor_above's 7-of-9 test misses at the
// edge of what's above (user, 13 Nov: by Bottles' house under the cliff of Jiggywiggy's temple).
bool bt_map_shows_level_above(uint32_t key, float x, float y, float z) {
    std::lock_guard lock{ g_cacheMutex };
    const MapCache& c = g_cache;
    if (!c.valid || c.key != key || c.groundY.empty()) return false;
    const int px = int((x - c.minX) * c.scale), pz = int((z - c.minZ) * c.scale);
    if (px < 0 || pz < 0 || px >= c.W || pz >= c.H) return false;
    const size_t i = size_t(pz) * c.W + px;
    if (c.kind[i] != kMain && c.kind[i] != kSolid) return false;
    return c.groundY[i] != kNoGround && float(c.groundY[i]) - y > 300.0f;
}

// Whether Banjo, at (x, y, z), stands on walkable ground the last map (with this key) left out
// of the play area. If so the point is remembered for this area and true is returned: the
// caller builds the map again, and the ground he's on joins the area.
bool bt_map_wants_seed(uint32_t key, float x, float y, float z) {
    std::lock_guard lock{ g_cacheMutex };
    const MapCache& c = g_cache;
    if (!c.valid || c.key != key || c.groundY.empty()) return false;
    const int px = int((x - c.minX) * c.scale), pz = int((z - c.minZ) * c.scale);
    if (px < 0 || pz < 0 || px >= c.W || pz >= c.H) return false;
    const size_t i = size_t(pz) * c.W + px;
    if (c.kind[i] == kMain || c.kind[i] == kWater || c.kind[i] == kSolid) return false;
    if (c.groundY[i] == kNoGround || std::fabs(float(c.groundY[i]) - y) > 60.0f) return false;   // on it, not jumping over it
    auto& seeds = g_seeds[c.mapId];
    for (const auto& p : seeds)
        if (std::fabs(p[0] - x) < 300.0f && std::fabs(p[2] - z) < 300.0f && std::fabs(p[1] - y) < 150.0f) return false;
    seeds.push_back({ x, y, z });
    return true;
}

bool bt_build_map(const uint8_t* rdram, int maxSize, float capY, std::vector<int32_t>& out, float meta[5]) {
    return bt_build_map_keyed(rdram, maxSize, capY, 0, out, meta);
}

// A close-up tile of the last whole-area map (built with the same key): the world rectangle
// (x0, z0)-(x1, z1) at up to maxSize pixels across. The textures are drawn again at this
// detail; what kind each pixel is, its light, fade and outline edges come from the whole-area
// map (bilinear), so the tile matches it and only gets sharper. False when the last map
// doesn't match the key.
bool bt_build_detail(uint32_t key, float x0, float z0, float x1, float z1, int maxSize, std::vector<int32_t>& out, float meta[5]) {
    std::lock_guard lock{ g_cacheMutex };
    const MapCache& c = g_cache;
    if (!c.valid || c.key != key || x1 <= x0 || z1 <= z0) return false;
    const float scale = float(std::clamp(maxSize, 64, 3072)) / std::max(x1 - x0, z1 - z0);
    const int W = std::max(1, int((x1 - x0) * scale)), H = std::max(1, int((z1 - z0) * scale));
    const size_t N = size_t(W) * H;
    std::vector<float> R(N, 0), G(N, 0), B(N, 0), top(N, -1e9f);
    std::vector<uint8_t> drawn(N, 0), water(N, 0);
    // Under a roof (see the whole-area build): the same cap, from the nearest base pixel.
    std::vector<float> limit;
    if (!c.roofCap.empty()) {
        bool any = false;
        limit.assign(N, 1e9f);
        for (int z = 0; z < H; z++)
            for (int x = 0; x < W; x++) {
                const float wx = x0 + (x + 0.5f) / scale, wz = z0 + (z + 0.5f) / scale;
                const int bx = std::clamp(int((wx - c.minX) * c.scale), 0, c.W - 1), bz = std::clamp(int((wz - c.minZ) * c.scale), 0, c.H - 1);
                const int16_t cap = c.roofCap[size_t(bz) * c.W + bx];
                if (cap != kNoGround) { limit[size_t(z) * W + x] = float(cap); any = true; }
            }
        if (!any) limit.clear();
    }
    rasterTop(c.tris, x0, z0, scale, W, H, R, G, B, top, drawn, water, limit.empty() ? nullptr : &limit);

    // Bilinear sample of a cached per-pixel byte array at world (x, z).
    auto sample = [&](const std::vector<uint8_t>& a, float fx, float fz) {
        const int ix = int(std::floor(fx)), iz = int(std::floor(fz));
        const float tx = fx - ix, tz = fz - iz;
        auto at = [&](int x, int z) { x = std::clamp(x, 0, c.W - 1); z = std::clamp(z, 0, c.H - 1); return float(a[size_t(z) * c.W + x]); };
        return (at(ix, iz) * (1 - tx) + at(ix + 1, iz) * tx) * (1 - tz) + (at(ix, iz + 1) * (1 - tx) + at(ix + 1, iz + 1) * tx) * tz;
    };
    auto nearest = [&](const std::vector<uint8_t>& a, float fx, float fz) {
        const int x = std::clamp(int(fx + 0.5f), 0, c.W - 1), z = std::clamp(int(fz + 0.5f), 0, c.H - 1);
        return a[size_t(z) * c.W + x];
    };
    // How "inside" (water or play area) each base pixel is, and how "main", for smooth edges.
    std::vector<uint8_t> inside(c.kind.size()), isMain(c.kind.size()), isWater(c.kind.size());
    for (size_t i = 0; i < c.kind.size(); i++) {
        inside[i] = (c.kind[i] == kWater || c.kind[i] == kMain || c.kind[i] == kSolid) ? 255 : 0;
        isMain[i] = c.kind[i] == kMain ? 255 : 0;
        isWater[i] = c.kind[i] == kWater ? 255 : 0;
    }

    std::vector<uint8_t> kind(N, kNone), mainMask(N, 0), waterMask(N, 0);
    std::vector<float> light(N, 1.0f), alpha(N, 0.0f), colourK(N, 0.0f), edge(N, 0.0f);
    for (int z = 0; z < H; z++)
        for (int x = 0; x < W; x++) {
            const size_t i = size_t(z) * W + x;
            const float wx = x0 + (x + 0.5f) / scale, wz = z0 + (z + 0.5f) / scale;
            const float fx = (wx - c.minX) * c.scale - 0.5f, fz = (wz - c.minZ) * c.scale - 0.5f;
            const float in = sample(inside, fx, fz) / 255.0f;
            const uint8_t near = nearest(c.kind, fx, fz);
            uint8_t k = kNone;
            if (water[i] && drawn[i] && (in > 0.3f || near == kWater)) k = kWater;
            else if (sample(isMain, fx, fz) >= 127.5f) k = kMain;
            else if (near == kSolid) k = kSolid;
            else if (near == kWater && in >= 0.5f) k = sample(isWater, fx, fz) >= 127.5f && !drawn[i] ? kWater : kMain;
            else if (sample(c.alpha, fx, fz) > 2.0f) k = kBeyond;
            kind[i] = k;
            mainMask[i] = k == kMain;
            waterMask[i] = k == kWater;
            light[i] = sample(c.light, fx, fz) / 100.0f;
            alpha[i] = sample(c.alpha, fx, fz) / 255.0f;
            colourK[i] = sample(c.colourK, fx, fz) / 255.0f;
            edge[i] = sample(c.edge, fx, fz);
        }
    // Edges a pixel or two wide at this detail: the outline where the map ends, the shoreline.
    const int e = std::max(1, int(std::lround(W / 900.0f)));
    const std::vector<uint8_t> mainInner = morph(mainMask, W, H, e, false);
    const std::vector<uint8_t> waterInner = morph(waterMask, W, H, e, false);

    out.assign(N, 0);
    for (size_t i = 0; i < N; i++) {
        const float tr = drawn[i] ? R[i] : 120.0f, tg = drawn[i] ? G[i] : 120.0f, tb = drawn[i] ? B[i] : 120.0f;
        float r, g, b, a = 1.0f, k = 1.0f;
        switch (kind[i]) {
            case kWater: waterColour(tr, tg, tb, !waterInner[i], r, g, b); break;
            case kMain: groundColour(tr, tg, tb, light[i], !mainInner[i] && edge[i] >= 0.5f, r, g, b); break;
            case kSolid: groundColour(tr, tg, tb, light[i], false, r, g, b); break;
            case kBeyond:
                beyondColour(tr, tg, tb, light[i], r, g, b);
                a = alpha[i]; k = colourK[i];
                break;
            default:
                if (alpha[i] < 1.0f / 255.0f) continue;
                r = g = b = 0; a = alpha[i]; k = 0;   // the shadow only
                break;
        }
        out[i] = packPixel(r, g, b, a, k);
    }
    meta[0] = float(W); meta[1] = float(H); meta[2] = x0; meta[3] = z0; meta[4] = scale;
    return true;
}

// The last whole-area map's water (1 per map pixel), for the bottom screen's shimmer; empty
// when it doesn't match the key.
std::vector<uint8_t> bt_water_mask(uint32_t key, int& W, int& H) {
    std::lock_guard lock{ g_cacheMutex };
    W = H = 0;
    if (!g_cache.valid || g_cache.key != key) return {};
    W = g_cache.W; H = g_cache.H;
    std::vector<uint8_t> m(g_cache.kind.size());
    for (size_t i = 0; i < m.size(); i++) m[i] = g_cache.kind[i] == kWater ? 255 : 0;
    return m;
}

// The last whole-area map's backdrop (opaque ARGB, coarse; built with the map) and
// where it sits: meta = width, height, world x and z of its top-left, pixels per world unit.
// Empty when the last map doesn't match the key.
std::vector<int32_t> bt_backdrop(uint32_t key, float meta[5]) {
    std::lock_guard lock{ g_cacheMutex };
    if (!g_cache.valid || g_cache.key != key || g_cache.backdrop.empty()) return {};
    meta[0] = float(g_cache.bdW); meta[1] = float(g_cache.bdH);
    meta[2] = g_cache.bdMinX; meta[3] = g_cache.bdMinZ; meta[4] = g_cache.bdScale;
    return g_cache.backdrop;
}

// Whether some walkable floor lies directly above (x, z), more than 200 units over y: Banjo is
// under another floor (a lower storey), so the map should show his layer.
// Whether Banjo (at x, y, z) is under another storey: a floor between 200 and 1500 units
// above him there and all around him (at least 7 of 9 points 400 units apart, the middle
// one included). The upper limit leaves out cliffs far above (Spiral Mountain's rock over
// the lair entrance is about 2,800 up); needing the area around him leaves out arches,
// bridges and ledges he walks under (the island's spiral path under its top).
bool bt_floor_above(const uint8_t* rdram, float x, float y, float z) {
    const Ram ram{ rdram };
    std::vector<Floor> floors;
    if (!collectFloors(ram, floors)) return false;
    auto above = [&](float px, float pz) {
        for (const Floor& f : floors) {
            const float* a = f.p[0]; const float* b = f.p[1]; const float* c = f.p[2];
            if (px < std::min({ a[0], b[0], c[0] }) || px > std::max({ a[0], b[0], c[0] }) ||
                pz < std::min({ a[2], b[2], c[2] }) || pz > std::max({ a[2], b[2], c[2] })) continue;
            const float d = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2]);
            if (std::fabs(d) < 1e-6f) continue;
            const float l0 = ((b[2] - c[2]) * (px - c[0]) + (c[0] - b[0]) * (pz - c[2])) / d;
            const float l1 = ((c[2] - a[2]) * (px - c[0]) + (a[0] - c[0]) * (pz - c[2])) / d;
            const float l2 = 1.0f - l0 - l1;
            if (l0 < 0 || l1 < 0 || l2 < 0) continue;
            const float h = l0 * a[1] + l1 * b[1] + l2 * c[1];
            // Up to 6000 above: a tunnel under a hill (the path below Jinjo Village runs ~3800
            // under the ground above it). The 7-of-9 test below keeps out overhangs.
            if (h > y + 200.0f && h < y + 6000.0f) return true;
        }
        return false;
    };
    if (!above(x, z)) return false;
    int count = 1;
    for (int dz = -1; dz <= 1; dz++)
        for (int dx = -1; dx <= 1; dx++)
            if ((dx != 0 || dz != 0) && above(x + 400.0f * dx, z + 400.0f * dz)) count++;
    return count >= 7;
}

#ifndef BT_MAP_TEST
// GameState.buildMap: runs on a background thread. capY: leave out what's entirely above it
// (a lower storey's map), or >= 1e8 for the whole area.
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeBuildMap(JNIEnv* env, jclass, jint maxSize, jfloat capY, jint key, jfloatArray info) {
    uint8_t* rdram = bt_android_running_rdram();
    if (rdram == nullptr || info == nullptr || env->GetArrayLength(info) < 5) return nullptr;
    std::vector<int32_t> out;
    float meta[5];
    if (!bt_build_map_keyed(rdram, maxSize, capY, uint32_t(key), out, meta)) return nullptr;
    jintArray pixels = env->NewIntArray(jsize(out.size()));
    if (pixels == nullptr) return nullptr;
    env->SetIntArrayRegion(pixels, 0, jsize(out.size()), reinterpret_cast<const jint*>(out.data()));
    env->SetFloatArrayRegion(info, 0, 5, meta);
    return pixels;
}

#endif   // BT_MAP_TEST (the reader below is also used by the test harness)

// The current area's collectibles and landmarks, from its object list (setup data; pointer at
// 0x80132DB0, found 13 Nov in a live session, see bt-android-ram-probe.md): up to max entries
// of x, y, z, kind into out (4 floats each). Returns how many. Kinds: 1 Jiggy, 2 note nest,
// 3 treble clef, 4 Jinjo, 5 Glowbo, 6 honeycomb, 7 egg refill, 8 red feather refill,
// 9 Mumbo pad, 10 Jamjars. The list doesn't change when something is collected: where the
// game keeps that isn't found yet, so collected things still show.
int bt_area_objects(const uint8_t* rdram, float* out, int max) {
    const Ram ram{ rdram };
    const uint32_t base = ram.u32(0x80132DB0u);
    if (!ram.ok(base, 0x18)) return 0;
    const int size = ram.u16(base), count = ram.u16(base + 2);
    if (size != 0x14 || count <= 0 || count > 4000 || !ram.ok(base + 0x18, uint32_t(count) * size)) return 0;
    int n = 0;
    for (int k = 0; k < count && n < max; k++) {
        const uint32_t a = base + 0x18 + uint32_t(k) * size;
        const int x = ram.s16(a), y = ram.s16(a + 2), z = ram.s16(a + 4);
        const uint16_t word = ram.u16(a + 6), id = ram.u16(a + 8), zero = ram.u16(a + 10), scale = ram.u16(a + 14);
        if ((word & 0x7F) != 0x0C || zero != 0 || scale == 0 || scale > 1000) continue;
        if (std::abs(x) > 20000 || std::abs(y) > 20000 || std::abs(z) > 20000) continue;
        int kind = 0;
        switch (id) {
            case 0x01F6: kind = 1; break;
            case 0x01D7: kind = 2; break;
            case 0x01D8: kind = 3; break;
            case 0x01F5: kind = 4; break;
            case 0x01F8: kind = 5; break;
            case 0x0229: kind = 6; break;
            case 0x01E9: kind = 7; break;
            case 0x01CE: kind = 8; break;
            case 0x04A6: kind = 8; break;   // feather nest switching between red and gold
            case 0x01CF: kind = 13; break;   // gold feather refill (Spiral Mountain, 13 Nov)
            case 0x02B0: kind = 9; break;
            case 0x0309: case 0x030B: kind = 10; break;
            case 0x0234: kind = 11; break;   // flight pad (Spiral Mountain's island top, 13 Nov)
            case 0x01F7: kind = 14; break;   // empty honeycomb (Mayahem Temple, 13 Nov)
            case 0x0210: kind = 12; break;   // shock jump pad (Spiral Mountain, 13 Nov)
            default: break;
        }
        if (kind == 0) continue;
        out[4 * n] = float(x); out[4 * n + 1] = float(y); out[4 * n + 2] = float(z); out[4 * n + 3] = float(kind);
        n++;
    }
    return n;
}

#ifndef BT_MAP_TEST
extern "C" JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeAreaObjects(JNIEnv* env, jclass, jfloatArray out) {
    uint8_t* rdram = bt_android_running_rdram();
    if (rdram == nullptr || out == nullptr) return 0;
    const jsize len = env->GetArrayLength(out);
    std::vector<float> buf(static_cast<size_t>(len));
    const int n = bt_area_objects(rdram, buf.data(), int(len / 4));
    if (n > 0) env->SetFloatArrayRegion(out, 0, n * 4, buf.data());
    return n;
}

// See bt_map_shows_level_above.
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeMapShowsLevelAbove(JNIEnv*, jclass, jint key, jfloat x, jfloat y, jfloat z) {
    return bt_map_shows_level_above(uint32_t(key), x, y, z) ? JNI_TRUE : JNI_FALSE;
}

// See bt_map_wants_seed.
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeMapWantsSeed(JNIEnv*, jclass, jint key, jfloat x, jfloat y, jfloat z) {
    return bt_map_wants_seed(uint32_t(key), x, y, z) ? JNI_TRUE : JNI_FALSE;
}

// A sharper close-up tile of the last map built with this key (see bt_build_detail).
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeBuildDetail(JNIEnv* env, jclass, jint key, jfloat x0, jfloat z0, jfloat x1, jfloat z1, jint maxSize, jfloatArray info) {
    if (info == nullptr || env->GetArrayLength(info) < 5) return nullptr;
    std::vector<int32_t> out;
    float meta[5];
    if (!bt_build_detail(uint32_t(key), x0, z0, x1, z1, maxSize, out, meta)) return nullptr;
    jintArray pixels = env->NewIntArray(jsize(out.size()));
    if (pixels == nullptr) return nullptr;
    env->SetIntArrayRegion(pixels, 0, jsize(out.size()), reinterpret_cast<const jint*>(out.data()));
    env->SetFloatArrayRegion(info, 0, 5, meta);
    return pixels;
}

// The last map's water mask (one byte per map pixel, 255 = water), or null.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeWaterMask(JNIEnv* env, jclass, jint key) {
    int W = 0, H = 0;
    std::vector<uint8_t> m = bt_water_mask(uint32_t(key), W, H);
    if (m.empty()) return nullptr;
    jbyteArray a = env->NewByteArray(jsize(m.size()));
    if (a == nullptr) return nullptr;
    env->SetByteArrayRegion(a, 0, jsize(m.size()), reinterpret_cast<const jbyte*>(m.data()));
    return a;
}

// The last map's backdrop (see bt_backdrop), or null.
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeBackdrop(JNIEnv* env, jclass, jint key, jfloatArray info) {
    if (info == nullptr || env->GetArrayLength(info) < 5) return nullptr;
    float meta[5];
    std::vector<int32_t> px = bt_backdrop(uint32_t(key), meta);
    if (px.empty()) return nullptr;
    jintArray a = env->NewIntArray(jsize(px.size()));
    if (a == nullptr) return nullptr;
    env->SetIntArrayRegion(a, 0, jsize(px.size()), reinterpret_cast<const jint*>(px.data()));
    env->SetFloatArrayRegion(info, 0, 5, meta);
    return a;
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
