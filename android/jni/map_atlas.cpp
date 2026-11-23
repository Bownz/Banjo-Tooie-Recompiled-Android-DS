// Bottom-screen maps in the "atlas" style, and every level's map drawn straight from the ROM.
//
// Atlas style: our own palette and drawing (height-tinted meadows, tan paths with dotted edges,
// stone, sand, wood, turquoise-to-deep water, hill shading, contour hatching, ink outlines),
// painted from what map_render.cpp works out about each map pixel (kind, ground height). The
// game's own colours are only used to tell grass from path from stone; none of them are drawn.
//
// From the ROM: each level's map model is an asset (header magic 0x0B; found 22 Nov: Spiral
// Mountain 7417, Mayahem Temple 7434, Jinjo Village 7617, Bottles' house 7632 match the copies
// in RAM byte for byte). Textures referenced by id are assets too: id + 7926, packed in list
// order (the game's assembled block, reproduced exactly for Jinjo Village and Bottles' house).
// A level's second (see-through) model is usually the next asset. For each level model the app
// lays these out in a scratch copy of RDRAM where the game would and runs the normal map
// builder on it, so the maps match what playing gives. Storeys come from the collision list:
// where walkable floors overlap, the heights between them. Nothing from the ROM is stored in
// the repository: this runs on the device, from the player's own ROM.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <span>
#include <vector>
#include <zlib.h>

#ifndef BT_MAP_TEST
#include <cstdlib>
#include <mutex>
#include <string>
#include <jni.h>
namespace recomp { std::span<const uint8_t> get_rom(); }
extern "C" uint8_t* bt_android_running_rdram();
#endif

// map_render.cpp: a build that keeps nothing shared (safe on several threads at once).
bool bt_build_map_private(const uint8_t* rdram, int maxSize, float capY, std::vector<int32_t>& out, float meta[5],
                          std::vector<uint8_t>& kind, std::vector<int16_t>& groundY,
                          std::vector<int32_t>& backdrop, float bdMeta[5]);

namespace {

// ---- Small image helpers -----------------------------------------------------------------

// Gaussian-ish blur: three box blurs each way (sliding sums), radius from sigma.
void boxPass(const float* src, float* dst, int W, int H, int r, bool horizontal) {
    const int n = horizontal ? W : H, lines = horizontal ? H : W;
    const int step = horizontal ? 1 : W, lineStep = horizontal ? W : 1;
    const float inv = 1.0f / float(2 * r + 1);
    for (int l = 0; l < lines; l++) {
        const float* s = src + size_t(l) * lineStep;
        float* d = dst + size_t(l) * lineStep;
        float acc = 0;
        for (int i = -r; i <= r; i++) acc += s[size_t(std::clamp(i, 0, n - 1)) * step];
        for (int i = 0; i < n; i++) {
            d[size_t(i) * step] = acc * inv;
            acc += s[size_t(std::min(i + r + 1, n - 1)) * step] - s[size_t(std::max(i - r, 0)) * step];
        }
    }
}

std::vector<float> blur(const std::vector<float>& a, int W, int H, float sigma) {
    std::vector<float> x = a, t(a.size());
    if (sigma < 0.5f) return x;
    // Three boxes of width w approximate a gaussian with sigma^2 = (w^2 - 1) / 4.
    const int r = std::max(1, int(std::lround((std::sqrt(4.0f * sigma * sigma + 1.0f) - 1.0f) / 2.0f)));
    for (int k = 0; k < 3; k++) {
        boxPass(x.data(), t.data(), W, H, r, true);
        boxPass(t.data(), x.data(), W, H, r, false);
    }
    return x;
}

std::vector<uint8_t> dilate(const std::vector<uint8_t>& m, int W, int H, int iterations) {
    std::vector<uint8_t> a = m, b(m.size());
    for (int it = 0; it < iterations; it++) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                const size_t i = size_t(y) * W + x;
                b[i] = a[i] || (x > 0 && a[i - 1]) || (x + 1 < W && a[i + 1]) || (y > 0 && a[i - W]) || (y + 1 < H && a[i + W]);
            }
        }
        std::swap(a, b);
    }
    return a;
}

std::vector<uint8_t> erode(const std::vector<uint8_t>& m, int W, int H, int iterations) {
    std::vector<uint8_t> a = m, b(m.size());
    for (int it = 0; it < iterations; it++) {
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                const size_t i = size_t(y) * W + x;
                b[i] = a[i] && x > 0 && a[i - 1] && x + 1 < W && a[i + 1] && y > 0 && a[i - W] && y + 1 < H && a[i + W];
            }
        }
        std::swap(a, b);
    }
    return a;
}

// Deterministic noise in [-1, 1] per pixel.
inline float hashNoise(uint32_t i, uint32_t seed) {
    uint32_t h = i * 0x9E3779B1u ^ seed * 0x85EBCA77u;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return float(h & 0xFFFFFF) / float(0x7FFFFF) - 1.0f;
}

struct Col { float r, g, b; };
inline Col mix(Col a, Col b, float t) { return { a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t }; }
inline Col mul(Col a, float k) { return { a.r * k, a.g * k, a.b * k }; }

Col ramp(const std::initializer_list<std::pair<float, Col>>& stops, float t) {
    t = std::clamp(t, 0.0f, 1.0f);
    const std::pair<float, Col>* prev = stops.begin();
    for (const auto& s : stops) {
        if (t <= s.first) {
            if (&s == prev) return s.second;
            return mix(prev->second, s.second, (t - prev->first) / std::max(1e-6f, s.first - prev->first));
        }
        prev = &s;
    }
    return prev->second;
}

enum : uint8_t { kNone = 0, kWater = 1, kMain = 2, kSolid = 3, kBeyond = 4 };   // as map_render.cpp
enum : uint8_t { cNone = 0, cGrass, cPath, cSand, cStone, cWood, cDark };

// Value noise in [-1, 1]: smooth at about `cell` pixels.
inline float valueNoise(int x, int y, int cell, uint32_t seed) {
    const int gx = x / cell, gy = y / cell;
    const float fx = float(x % cell) / cell, fy = float(y % cell) / cell;
    const float sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
    const auto at = [&](int i, int j) { return hashNoise(uint32_t(i) * 7919u + uint32_t(j) * 104729u, seed); };
    const float a = at(gx, gy), b = at(gx + 1, gy), c = at(gx, gy + 1), d = at(gx + 1, gy + 1);
    return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy;
}

std::vector<uint8_t> toU8(const std::vector<float>& v) {
    std::vector<uint8_t> o(v.size());
    for (size_t i = 0; i < v.size(); i++) o[i] = uint8_t(std::clamp(v[i], 0.0f, 1.0f) * 255.0f + 0.5f);
    return o;
}

std::vector<float> maskF(const std::vector<uint8_t>& m) {
    std::vector<float> f(m.size());
    for (size_t i = 0; i < m.size(); i++) f[i] = m[i] ? 1.0f : 0.0f;
    return f;
}

}   // namespace

// The atlas painting. classic: the classic map's colours (same size), used only to tell
// materials apart. Returns ARGB pixels. Peak memory is about 25 bytes per pixel (~100 MB for a
// 2048 x 2048 map), freed on return.
void bt_atlas_paint(int W, int H, float minX, float minZ, float s, const uint8_t* kindIn, const int16_t* groundY,
                    const int32_t* classic, std::vector<int32_t>& out) {
    const size_t N = size_t(W) * H;
    std::vector<uint8_t> water(N), main(N), solid(N), inside(N);
    for (size_t i = 0; i < N; i++) water[i] = kindIn[i] == kWater;
    // The water mask can be speckled (dithered water textures): close, then open.
    water = erode(dilate(water, W, H, 4), W, H, 4);
    water = dilate(erode(water, W, H, 2), W, H, 2);
    for (size_t i = 0; i < N; i++) {
        water[i] = water[i] && kindIn[i] != kNone;
        main[i] = kindIn[i] == kMain && !water[i];
        solid[i] = kindIn[i] == kSolid && !water[i];
        inside[i] = main[i] || water[i] || solid[i];
    }

    // Heights, filled in under walls and water from the ground nearby; range for tinting.
    float lo = 0, hi = 1;
    std::vector<float> hs;
    {
        std::vector<float> hv(N), hw(N);
        std::vector<int16_t> sample;
        sample.reserve(N / 16 + 1);
        for (size_t i = 0; i < N; i++) {
            const bool has = main[i] && groundY[i] > -32000;
            hv[i] = has ? float(groundY[i]) : 0.0f;
            hw[i] = has ? 1.0f : 0.0f;
            if (has && (i % 16) == 0) sample.push_back(groundY[i]);
        }
        if (!sample.empty()) {
            std::sort(sample.begin(), sample.end());
            lo = sample[sample.size() * 3 / 100];
            hi = sample[std::min(sample.size() - 1, sample.size() * 97 / 100)];
        }
        const std::vector<float> hsum = blur(hv, W, H, 6), wsum = blur(hw, W, H, 6);
        for (size_t i = 0; i < N; i++) {
            if (hw[i] == 0.0f) hv[i] = wsum[i] > 1e-3f ? hsum[i] / wsum[i] : 0.0f;
        }
        hs = blur(hv, W, H, 1.5f);
    }
    const float range = std::max(1.0f, hi - lo);
    std::vector<uint8_t> ao(N);   // ambient occlusion at the foot of higher ground, 0..255
    {
        const std::vector<float> wide = blur(hs, W, H, 8);
        const float k = 1.0f / std::max(1.0f, range * 0.08f);
        for (size_t i = 0; i < N; i++) ao[i] = uint8_t(std::clamp((wide[i] - hs[i]) * k, 0.0f, 1.0f) * 255.0f);
    }

    // Material classes from the classic colours, smoothed by a majority vote.
    std::vector<uint8_t> cls(N, cNone);
    for (size_t i = 0; i < N; i++) {
        if (!(main[i] || solid[i])) continue;
        const uint32_t c = uint32_t(classic[i]);
        const float r = ((c >> 16) & 255) / 255.0f, g = ((c >> 8) & 255) / 255.0f, b = (c & 255) / 255.0f;
        const float mx = std::max({ r, g, b }), mn = std::min({ r, g, b }), d = std::max(mx - mn, 1e-6f);
        const float sat = (mx - mn) / std::max(mx, 1e-3f);
        float hue = mx == r ? std::fmod((g - b) / d + 6.0f, 6.0f) : mx == g ? (b - r) / d + 2.0f : (r - g) / d + 4.0f;
        hue *= 60.0f;
        uint8_t k = cStone;
        if (mx < 0.16f) k = cDark;
        else if (sat < 0.16f) k = cStone;
        else if (hue >= 62 && hue < 170) k = cGrass;
        else if (hue >= 18 && hue < 62) k = mx < 0.72f ? cPath : cSand;
        else if (hue < 18 || hue >= 300) k = cWood;
        cls[i] = k;
    }
    {
        std::vector<uint8_t> best(N, 0), voted = cls;
        std::vector<float> v(N);
        for (uint8_t k = cGrass; k <= cDark; k++) {
            for (size_t i = 0; i < N; i++) v[i] = cls[i] == k ? 1.0f : 0.0f;
            const std::vector<float> bv = blur(v, W, H, 2.2f);
            for (size_t i = 0; i < N; i++) {
                const uint8_t q = uint8_t(std::clamp(bv[i], 0.0f, 1.0f) * 255.0f);
                if (q > best[i]) { best[i] = q; voted[i] = k; }
            }
        }
        for (size_t i = 0; i < N; i++) cls[i] = (main[i] || solid[i]) ? voted[i] : cNone;
    }

    // Soft fields, kept as bytes.
    const std::vector<uint8_t> wdepth = toU8(blur(maskF(water), W, H, 10));
    const std::vector<uint8_t> wsoft = toU8(blur(maskF(water), W, H, 1.4f));
    const std::vector<uint8_t> soft = toU8(blur(maskF(inside), W, H, 1.6f));
    std::vector<uint8_t> pathMask(N), grassMask(N);
    for (size_t i = 0; i < N; i++) { pathMask[i] = cls[i] == cPath; grassMask[i] = cls[i] == cGrass; }
    const std::vector<uint8_t> pathInner = erode(pathMask, W, H, 2);
    const std::vector<uint8_t> pathSoft = toU8(blur(maskF(pathMask), W, H, 2.5f));
    const std::vector<uint8_t> grassSoft = toU8(blur(maskF(grassMask), W, H, 3.0f));
    pathMask.clear(); pathMask.shrink_to_fit(); grassMask.clear(); grassMask.shrink_to_fit();
    const std::vector<uint8_t> solidInner = erode(solid, W, H, 1);
    const float bandStep = std::max(40.0f, range / 14.0f);

    const Col ink{ 54, 38, 30 };
    out.assign(N, 0);
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            const size_t i = size_t(y) * W + x;
            const float sf = soft[i] / 255.0f;
            const float a = std::clamp(sf * 1.6f - 0.15f, 0.0f, 1.0f);
            const bool edge = sf > 0.2f && sf < 0.8f;
            if (a <= 0.0f && !edge) continue;
            const float t = std::clamp((hs[i] - lo) / range, 0.0f, 1.0f);
            const float gx = (x > 0 && x + 1 < W) ? (hs[i + 1] - hs[i - 1]) * s / 2 : 0.0f;
            const float gz = (y > 0 && y + 1 < H) ? (hs[i + W] - hs[i - W]) * s / 2 : 0.0f;
            const float nl = std::sqrt(gx * gx + gz * gz + 2.2f * 2.2f);
            const float lit = (gx * 0.55f + gz * 0.55f + 2.2f * 0.63f) / nl;
            const float steep = std::clamp((std::sqrt(gx * gx + gz * gz) - 1.2f) / 3.0f, 0.0f, 1.0f);
            const float shade = std::clamp(0.80f + 0.5f * (lit - 0.63f), 0.55f, 1.18f) * (1.0f - 0.22f * (ao[i] / 255.0f));
            uint8_t k = cls[i];
            if (k == cGrass && main[i] && steep > 0.45f) k = cStone;   // cliffs read as rock
            Col c{ 168, 158, 176 };
            switch (k) {
                case cGrass: c = ramp({ { 0.0f, { 70, 156, 62 } }, { 0.45f, { 108, 184, 68 } }, { 0.7f, { 136, 172, 78 } }, { 0.88f, { 148, 150, 88 } }, { 1.0f, { 160, 138, 98 } } }, t); break;
                case cPath: c = ramp({ { 0.0f, { 214, 160, 96 } }, { 1.0f, { 236, 196, 132 } } }, t); break;
                case cSand: c = ramp({ { 0.0f, { 238, 214, 150 } }, { 1.0f, { 250, 234, 186 } } }, t); break;
                case cStone: c = ramp({ { 0.0f, { 132, 128, 150 } }, { 0.6f, { 168, 164, 184 } }, { 1.0f, { 206, 204, 218 } } }, t); break;
                case cWood: c = ramp({ { 0.0f, { 170, 104, 62 } }, { 1.0f, { 206, 142, 88 } } }, t); break;
                case cDark: c = ramp({ { 0.0f, { 70, 62, 80 } }, { 1.0f, { 96, 88, 108 } } }, t); break;
                default: break;
            }
            c = mul(c, shade);
            if (k == cGrass) {
                const float m = valueNoise(x, y, 6, 2) * 20.0f;
                c.r += m * 0.3f; c.g += m; c.b += m * 0.2f;
                const float tuft = hashNoise(uint32_t(i), 3), flower = hashNoise(uint32_t(i), 4);
                if (tuft > 0.993f) c = mul(c, 0.72f);
                else if (flower > 0.9986f && grassSoft[i] > 242) {
                    static const Col fl[4] = { { 250, 240, 120 }, { 250, 150, 170 }, { 255, 255, 255 }, { 170, 190, 255 } };
                    c = fl[uint32_t(i * 2654435761u) >> 30];
                }
            }
            if (k == cPath) {
                const float lift = (pathSoft[i] / 255.0f - 0.5f) * 30.0f;
                c.r += lift; c.g += lift; c.b += lift;
                if (!pathInner[i] && ((x / 3 + y / 3) % 3) != 0) c = mix(c, { 120, 72, 36 }, 0.45f);
            }
            if (k == cStone && hashNoise(uint32_t((y / 3) * 4096 + x / 3), 5) > 0.5f) c = mul(c, 1.06f);
            // Contour hatching on high ground.
            if (main[i] && t > 0.55f && x + 1 < W && y + 1 < H &&
                (std::floor(hs[i] / bandStep) != std::floor(hs[i + 1] / bandStep) || std::floor(hs[i] / bandStep) != std::floor(hs[i + W] / bandStep))) {
                c = mul(c, 0.86f);
            }
            if (water[i]) {
                const float depth = std::clamp((wdepth[i] / 255.0f - 0.5f) * 2.2f, 0.0f, 1.0f);
                c = ramp({ { 0.0f, { 120, 226, 230 } }, { 0.3f, { 56, 182, 214 } }, { 1.0f, { 28, 92, 172 } } }, depth);
                const float wx = x / s + minX, wz = y / s + minZ;
                const float rip = std::sin(wx * 0.012f + std::sin(wz * 0.004f) * 2.0f) * std::sin(wz * 0.009f + std::sin(wx * 0.003f) * 1.5f);
                if (rip > 0.9f) { const float k2 = 0.4f + 0.6f * depth; c.r += 60 * k2; c.g += 60 * k2; c.b += 50 * k2; }
            }
            const float ws = wsoft[i] / 255.0f;
            if (ws > 0.3f && ws < 0.7f && inside[i]) c = mix(c, { 245, 252, 250 }, 0.75f);
            if (solid[i] && !solidInner[i]) c = mix(c, ink, 0.5f);
            if (main[i] && steep > 0.6f) c = mix(c, ink, 0.25f);
            const float gr = valueNoise(x, y, 2, 1) * 6.0f;
            c.r += gr; c.g += gr; c.b += gr;
            float alpha = a;
            if (edge) { c = mix(c, ink, 0.85f); alpha = 1.0f; }
            const auto ch = [](float v) { return uint32_t(std::clamp(v, 0.0f, 255.0f)); };
            out[i] = int32_t((uint32_t(alpha * 255.0f) << 24) | (ch(c.r) << 16) | (ch(c.g) << 8) | ch(c.b));
        }
    }
}

// ---- Level maps from the ROM ---------------------------------------------------------------

namespace {

constexpr uint32_t kTable = 0x5180, kBase = 0x12B24;
constexpr int kTextureIdOffset = 7926;   // texture id -> asset index

uint32_t be32(const uint8_t* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }
uint16_t be16(const uint8_t* p) { return uint16_t((p[0] << 8) | p[1]); }
int16_t bes16(const uint8_t* p) { return int16_t(be16(p)); }

bool loadAnyAsset(std::span<const uint8_t> rom, int index, std::vector<uint8_t>& out) {
    out.clear();
    if (rom.size() < kTable + 8) return false;
    const uint32_t count = be32(&rom[kTable]);
    if (index < 0 || uint32_t(index) + 1 >= count || kTable + 8 + 4 * (uint32_t(index) + 2) > rom.size()) return false;
    const uint8_t* e = &rom[kTable + 8 + 4 * uint32_t(index)];
    const uint32_t start = kBase + ((uint32_t(e[0]) << 16 | uint32_t(e[1]) << 8 | e[2]) * 4);
    const uint32_t end = kBase + ((uint32_t(e[4]) << 16 | uint32_t(e[5]) << 8 | e[6]) * 4);
    const bool compressed = (e[3] >> 4) & 1;
    if (end <= start || end > rom.size() || end - start > 0x400000) return false;
    if (!compressed) {
        out.assign(rom.begin() + start, rom.begin() + end);
        return true;
    }
    if (end - start < 3) return false;
    z_stream z{};
    if (inflateInit2(&z, -MAX_WBITS) != Z_OK) return false;
    z.next_in = const_cast<Bytef*>(&rom[start + 2]);
    z.avail_in = end - start - 2;
    out.resize(0x100000);
    int status = Z_OK;
    while (true) {
        z.next_out = out.data() + z.total_out;
        z.avail_out = uInt(out.size() - z.total_out);
        status = inflate(&z, Z_NO_FLUSH);
        if (status != Z_OK || z.avail_out != 0) break;
        if (out.size() >= 0x800000) break;
        out.resize(out.size() * 2);
    }
    out.resize(z.total_out);
    inflateEnd(&z);
    return (status == Z_STREAM_END || status == Z_BUF_ERROR || status == Z_OK) && !out.empty();
}

struct ModelInfo {
    uint32_t coll = 0, vtx = 0;
    int tris = 0;
    float extent = 0;
    int16_t vmin[3] = {}, vmax[3] = {};
};

bool modelInfo(const std::vector<uint8_t>& d, ModelInfo& m) {
    if (d.size() < 0x60 || be32(&d[0]) != 0x0Bu) return false;
    m.coll = be32(&d[0x1C]);
    m.vtx = be32(&d[0x10]);
    if (m.vtx == 0 || m.vtx + 0x18 > d.size()) return false;
    for (int k = 0; k < 3; k++) { m.vmin[k] = bes16(&d[m.vtx + 2 * k]); m.vmax[k] = bes16(&d[m.vtx + 6 + 2 * k]); }
    if (m.coll == 0 || m.coll + 0x18 > d.size()) { m.coll = 0; return true; }
    const uint8_t* c = &d[m.coll];
    const int cell = be16(c + 18);
    m.tris = be16(c + 20);
    m.extent = float(std::max(bes16(c + 6) - bes16(c + 0), bes16(c + 10) - bes16(c + 4))) * float(cell);
    return true;
}

// Fingerprint of a collision list: header, triangle count and the first triangles.
uint32_t fingerprint(const uint8_t* collision, size_t available) {
    if (available < 0x18) return 0;
    const int tris = be16(collision + 20);
    const int geo = be16(collision + 16);
    const size_t triAt = 0x18 + size_t(geo) * 4;
    const size_t n = std::min(available, triAt + std::min<size_t>(size_t(tris) * 12, 2048));
    uint32_t h = 2166136261u;
    for (size_t k = 0; k < std::min<size_t>(n, 0x18); k++) h = (h ^ collision[k]) * 16777619u;
    for (size_t k = triAt; k < n; k++) h = (h ^ collision[k]) * 16777619u;
    return h == 0 ? 1 : h;
}

// The textures a model references by id, assembled the way the game does (header and entries
// copied, then each id's asset in list order, to the list's total size). Empty if the model's
// list holds offsets (its textures are inside it).
std::vector<uint8_t> assembleTextures(std::span<const uint8_t> rom, const std::vector<uint8_t>& model) {
    std::vector<uint8_t> block;
    const uint32_t own = be32(&model[0x08]) >> 16;
    if (own + 8 > model.size()) return block;
    const uint32_t total = be32(&model[own]);
    const int n = be16(&model[own + 4]);
    if (n <= 0 || n > 1024 || own + 8 + 8u * n > model.size() || be32(&model[own + 8]) == 0 || total > 0x400000) return block;
    block.assign(model.begin() + own, model.begin() + own + 8 + 8u * n);
    std::vector<uint8_t> tex;
    for (int k = 0; k < n && block.size() < 8 + 8u * n + total; k++) {
        const uint32_t id = be32(&model[own + 8 + 8u * k]);
        if (!loadAnyAsset(rom, int(id) + kTextureIdOffset, tex)) return {};
        block.insert(block.end(), tex.begin(), tex.end());
    }
    block.resize(8 + 8u * n + total, 0);
    return block;
}

struct Scratch {
    std::vector<uint8_t> ram;   // host order, as the recompiler keeps RDRAM
    void put(uint32_t guest, const std::vector<uint8_t>& bytes) {
        for (size_t k = 0; k < bytes.size(); k++) ram[((guest - 0x80000000u) + k) ^ 3] = bytes[k];
    }
    void put32(uint32_t guest, uint32_t v) { std::memcpy(&ram[guest - 0x80000000u], &v, 4); }
};

// Lays a level model (and its see-through partner, if the next asset is one over the same
// ground) out in a scratch RDRAM as the game would. Returns false if it isn't a level model.
bool layOut(std::span<const uint8_t> rom, int asset, Scratch& s, std::vector<uint8_t>& model) {
    if (!loadAnyAsset(rom, asset, model)) return false;
    ModelInfo m;
    if (!modelInfo(model, m) || m.coll == 0) return false;
    s.ram.assign(8 * 1024 * 1024, 0);
    constexpr uint32_t kModelAt = 0x80300000u, kBlockAt = 0x80400000u, kSecondAt = 0x80580000u, kSecondBlockAt = 0x80680000u;
    if (model.size() > kBlockAt - kModelAt) return false;
    s.put(kModelAt, model);
    s.put32(0x801285BCu, kModelAt);
    s.put32(0x801285B0u, kModelAt + m.coll);
    s.put32(0x801285C0u, kModelAt + m.vtx);
    std::vector<uint8_t> block = assembleTextures(rom, model);
    if (!block.empty() && block.size() <= kSecondAt - kBlockAt) {
        s.put(kBlockAt, block);
        s.put32(0x801285D4u, kBlockAt);
    }
    std::vector<uint8_t> second;
    ModelInfo m2;
    if (loadAnyAsset(rom, asset + 1, second) && modelInfo(second, m2) && second.size() <= kSecondBlockAt - kSecondAt) {
        // Over the same ground: the x/z boxes overlap by most of the smaller one.
        const float ox = float(std::min(m.vmax[0], m2.vmax[0]) - std::max(m.vmin[0], m2.vmin[0]));
        const float oz = float(std::min(m.vmax[2], m2.vmax[2]) - std::max(m.vmin[2], m2.vmin[2]));
        const float small = std::min(float(m2.vmax[0] - m2.vmin[0]) * float(m2.vmax[2] - m2.vmin[2]),
                                     float(m.vmax[0] - m.vmin[0]) * float(m.vmax[2] - m.vmin[2]));
        if (ox > 0 && oz > 0 && ox * oz > 0.5f * small && m2.tris < 1500) {
            s.put(kSecondAt, second);
            s.put32(0x8012B458u, kSecondAt);
            std::vector<uint8_t> block2 = assembleTextures(rom, second);
            if (!block2.empty() && block2.size() <= 0x80780000u - kSecondBlockAt) {
                s.put(kSecondBlockAt, block2);
                s.put32(0x80128788u, kSecondBlockAt);
            }
        }
    }
    return true;
}

// Storeys: heights between overlapping walkable floors, from the collision list. Up to 3,
// lowest first.
std::vector<float> storeys(const std::vector<uint8_t>& model) {
    std::vector<float> result;
    ModelInfo m;
    if (!modelInfo(model, m) || m.coll == 0) return result;
    const uint8_t* c = &model[m.coll];
    const int geo = be16(c + 16), tris = be16(c + 20);
    const size_t triAt = m.coll + 0x18 + size_t(geo) * 4;
    const size_t vAt = m.vtx + 0x18;
    // The header's count can run past the end of the asset (Spiral Mountain: 11953 for room
    // for 10353): use what's there.
    const int vcount = int(std::min<size_t>(be16(&model[m.vtx + 0x14]), (model.size() - std::min(model.size(), vAt)) / 16));
    if (triAt + size_t(tris) * 12 > model.size() || vcount <= 0) return result;
    const float x0 = m.vmin[0], z0 = m.vmin[2];
    const float span = std::max(1.0f, float(std::max(m.vmax[0] - m.vmin[0], m.vmax[2] - m.vmin[2])));
    const int G = 160;
    const float cell = span / G;
    std::vector<std::vector<int16_t>> heights(size_t(G) * G);
    int walkCells = 0;
    for (int t = 0; t < tris; t++) {
        const uint8_t* tr = &model[triAt + size_t(t) * 12];
        if (be32(tr + 8) & 0x01000000u) continue;   // the boundary shell
        float p[3][3];
        bool okTri = true;
        for (int v = 0; v < 3; v++) {
            const int idx = be16(tr + 2 * v);
            if (idx < 0 || idx >= vcount) { okTri = false; break; }
            const uint8_t* vv = &model[vAt + size_t(idx) * 16];
            p[v][0] = bes16(vv); p[v][1] = bes16(vv + 2); p[v][2] = bes16(vv + 4);
        }
        if (!okTri) continue;
        const float ux = p[1][0] - p[0][0], uy = p[1][1] - p[0][1], uz = p[1][2] - p[0][2];
        const float vx = p[2][0] - p[0][0], vy = p[2][1] - p[0][1], vz = p[2][2] - p[0][2];
        const float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        const float nl = std::sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-3f || std::fabs(ny) / nl < 0.7f) continue;   // walls and ceilings
        const int cx0 = std::clamp(int((std::min({ p[0][0], p[1][0], p[2][0] }) - x0) / cell), 0, G - 1);
        const int cx1 = std::clamp(int((std::max({ p[0][0], p[1][0], p[2][0] }) - x0) / cell), 0, G - 1);
        const int cz0 = std::clamp(int((std::min({ p[0][2], p[1][2], p[2][2] }) - z0) / cell), 0, G - 1);
        const int cz1 = std::clamp(int((std::max({ p[0][2], p[1][2], p[2][2] }) - z0) / cell), 0, G - 1);
        const float den = (p[1][2] - p[2][2]) * (p[0][0] - p[2][0]) + (p[2][0] - p[1][0]) * (p[0][2] - p[2][2]);
        if (std::fabs(den) < 1e-6f) continue;
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                const float px = x0 + (cx + 0.5f) * cell, pz = z0 + (cz + 0.5f) * cell;
                const float a = ((p[1][2] - p[2][2]) * (px - p[2][0]) + (p[2][0] - p[1][0]) * (pz - p[2][2])) / den;
                const float b = ((p[2][2] - p[0][2]) * (px - p[2][0]) + (p[0][0] - p[2][0]) * (pz - p[2][2])) / den;
                if (a < -0.02f || b < -0.02f || a + b > 1.02f) continue;
                const float py = a * p[0][1] + b * p[1][1] + (1 - a - b) * p[2][1];
                auto& hv = heights[size_t(cz) * G + cx];
                if (hv.size() < 12) hv.push_back(int16_t(std::clamp(py, -32000.0f, 32000.0f)));
            }
        }
    }
    // Per cell: distinct floors more than 350 apart; the midpoints between them vote.
    std::vector<int> votes;
    for (auto& hv : heights) {
        if (hv.empty()) continue;
        walkCells++;
        std::sort(hv.begin(), hv.end());
        int prev = hv[0];
        for (size_t k = 1; k < hv.size(); k++) {
            if (hv[k] - prev > 350) votes.push_back((hv[k] + prev) / 2);
            prev = hv[k];
        }
    }
#ifdef BT_MAP_TEST
    std::fprintf(stderr, "storeys: %d walk cells, %zu votes\n", walkCells, votes.size());
#endif
    if (votes.empty() || walkCells == 0) return result;
    std::sort(votes.begin(), votes.end());
    // Group votes within 300 of each other; keep groups with enough cells.
    const int needed = std::max(15, walkCells / 400);
    size_t k = 0;
    std::vector<std::pair<int, float>> groups;   // (count, median)
    while (k < votes.size()) {
        size_t e = k + 1;
        while (e < votes.size() && votes[e] - votes[e - 1] <= 300) e++;
        const int n = int(e - k);
        if (n >= needed) groups.push_back({ n, float(votes[k + n / 2]) });
        k = e;
    }
    std::sort(groups.begin(), groups.end(), [](auto& a, auto& b) { return a.first > b.first; });
    for (size_t g = 0; g < groups.size() && result.size() < 3; g++) result.push_back(groups[g].second);
    std::sort(result.begin(), result.end());
    return result;
}

std::vector<int> g_levelModels;
bool g_levelModelsScanned = false;

}   // namespace

#ifdef BT_MAP_TEST
// Test harness: the scratch RAM with this model laid out as the game does.
const uint8_t* bt_atlas_test_ram(std::span<const uint8_t> rom, int asset) {
    static Scratch s;
    std::vector<uint8_t> model;
    return layOut(rom, asset, s, model) ? s.ram.data() : nullptr;
}
#endif

// Level map models in the ROM (asset indices): with a collision list of 1500+ triangles over
// 6000+ units. 85 in the US ROM.
const std::vector<int>& bt_atlas_level_models(std::span<const uint8_t> rom) {
    if (g_levelModelsScanned) return g_levelModels;
    g_levelModelsScanned = true;
    if (rom.size() < kTable + 8) return g_levelModels;
    const uint32_t count = be32(&rom[kTable]);
    std::vector<uint8_t> d;
    for (uint32_t i = 0; i + 1 < count && i < 20000; i++) {
        const uint8_t* e = &rom[kTable + 8 + 4 * i];
        if ((e[3] & 15) != 0) continue;   // models are type 0
        if (!loadAnyAsset(rom, int(i), d)) continue;
        ModelInfo m;
        if (!modelInfo(d, m) || m.coll == 0) continue;
        if (m.tris >= 1500 && m.extent >= 6000) g_levelModels.push_back(int(i));
    }
    return g_levelModels;
}

// The fingerprint of a model asset's collision list (0 if none).
uint32_t bt_atlas_fingerprint(std::span<const uint8_t> rom, int asset) {
    std::vector<uint8_t> d;
    ModelInfo m;
    if (!loadAnyAsset(rom, asset, d) || !modelInfo(d, m) || m.coll == 0) return 0;
    return fingerprint(&d[m.coll], d.size() - m.coll);
}

// The fingerprint of the area loaded right now (its collision list in RDRAM).
uint32_t bt_atlas_current_fingerprint(const uint8_t* rdram) {
    if (rdram == nullptr) return 0;
    uint32_t ptr;
    std::memcpy(&ptr, rdram + (0x801285B0u - 0x80000000u), 4);
    if (ptr < 0x80000000u || ptr >= 0x807F0000u) return 0;
    std::vector<uint8_t> c(std::min<size_t>(0x10000, 0x80800000u - ptr));
    for (size_t k = 0; k < c.size(); k++) c[k] = rdram[((ptr - 0x80000000u) + k) ^ 3];
    // The collision list may be shorter than the window: fingerprint() reads only what it needs.
    return fingerprint(c.data(), c.size());
}

// Builds one level model's map from the ROM (capY as for live maps), in both styles: the
// classic picture (the game's textures) and the atlas painting, plus the backdrop. Safe on
// several threads at once (each has its own scratch RAM; the build keeps nothing shared).
bool bt_atlas_build_both(std::span<const uint8_t> rom, int asset, float capY, std::vector<int32_t>& classic,
                         std::vector<int32_t>& atlas, float meta[5], std::vector<float>* storeyOut,
                         std::vector<int32_t>& backdrop, float bdMeta[5]) {
    thread_local Scratch scratch;
    std::vector<uint8_t> model;
    if (!layOut(rom, asset, scratch, model)) return false;
    if (storeyOut != nullptr) *storeyOut = storeys(model);
    std::vector<uint8_t> kind;
    std::vector<int16_t> gy;
    const bool ok = bt_build_map_private(scratch.ram.data(), 2048, capY, classic, meta, kind, gy, backdrop, bdMeta);
    if (!ok) return false;
    bt_atlas_paint(int(meta[0]), int(meta[1]), meta[2], meta[3], meta[4], kind.data(), gy.data(), classic.data(), atlas);
    return true;
}

// The atlas style only (the PC test harness).
bool bt_atlas_build(std::span<const uint8_t> rom, int asset, float capY, uint32_t, std::vector<int32_t>& out, float meta[5],
                    std::vector<float>* storeyOut) {
    std::vector<int32_t> classic, backdrop;
    float bd[5];
    return bt_atlas_build_both(rom, asset, capY, classic, out, meta, storeyOut, backdrop, bd);
}

#ifndef BT_MAP_TEST
namespace {
// The ROM: the game's once it has started, else the one imported (stored by the launcher as
// bt.n64.us.1.0.z64 in the app's folder), read once, so every map can be drawn before the
// game starts.
std::mutex g_romMutex;
std::vector<uint8_t> g_storedRom;
bool g_storedRomTried = false;

std::span<const uint8_t> atlasRom() {
    const std::span<const uint8_t> live = recomp::get_rom();
    if (!live.empty()) return live;
    std::lock_guard lock{ g_romMutex };
    if (!g_storedRomTried || g_storedRom.empty()) {
        // The app's data folder, once the game's main thread has set it (android_main.cpp).
        // Not recompui's get_app_folder_path(): called before that, it checks for
        // "portable.txt" in the working directory, which is still / then, where Android
        // denies access, and std::filesystem throws (0.9.32 closed at launch).
        const char* folder = std::getenv("APP_FOLDER_PATH");
        if (folder == nullptr || folder[0] == '\0') return {};
        g_storedRomTried = true;
        const std::string file = std::string{ folder } + "/bt.n64.us.1.0.z64";
        std::FILE* f = std::fopen(file.c_str(), "rb");
        if (f != nullptr) {
            std::vector<uint8_t> d;
            uint8_t buf[1 << 16];
            for (size_t n; (n = std::fread(buf, 1, sizeof buf, f)) > 0; ) d.insert(d.end(), buf, buf + n);
            std::fclose(f);
            // A big-endian .z64 (the launcher stores it so): 80 37 12 40.
            if (d.size() >= (16u << 20) && d[0] == 0x80 && d[1] == 0x37 && d[2] == 0x12 && d[3] == 0x40) g_storedRom = std::move(d);
        }
    }
    return g_storedRom;
}
}   // namespace

// Map Style (Input > Dual Screen): 1 = atlas (default), 0 = classic. Read by nativeBuildMap.
int g_btMapStyle = 1;

extern "C" JNIEXPORT void JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeSetMapStyle(JNIEnv*, jclass, jint style) {
    g_btMapStyle = style;
}

// The ROM's level models (asset numbers), or null when there's no ROM yet. Call from one
// thread first (the scan isn't guarded).
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeAtlasModels(JNIEnv* env, jclass) try {
    const std::span<const uint8_t> rom = atlasRom();
    if (rom.empty()) return nullptr;
    const std::vector<int>& models = bt_atlas_level_models(rom);
    jintArray a = env->NewIntArray(jsize(models.size()));
    if (a != nullptr) env->SetIntArrayRegion(a, 0, jsize(models.size()), reinterpret_cast<const jint*>(models.data()));
    return a;
} catch (...) {
    return nullptr;   // never let an exception reach Java (it aborts the app)
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeAtlasFingerprint(JNIEnv*, jclass, jint asset) try {
    const std::span<const uint8_t> rom = atlasRom();
    return rom.empty() ? 0 : jint(bt_atlas_fingerprint(rom, asset));
} catch (...) {
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeCurrentFingerprint(JNIEnv*, jclass) try {
    return jint(bt_atlas_current_fingerprint(bt_android_running_rdram()));
} catch (...) {
    return 0;
}

// Both styles of one model's map: {classic ARGB, atlas ARGB, backdrop ARGB (may be empty)}.
// info (13): W, H, minX, minZ, scale, 3 storey heights (NaN where none), then the backdrop's
// W, H, minX, minZ, scale.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeAtlasBuildBoth(JNIEnv* env, jclass, jint asset, jfloat capY, jfloatArray info) try {
    const std::span<const uint8_t> rom = atlasRom();
    if (rom.empty() || info == nullptr || env->GetArrayLength(info) < 13) return nullptr;
    std::vector<int32_t> classic, atlas, backdrop;
    std::vector<float> st;
    float meta[13];
    if (!bt_atlas_build_both(rom, asset, capY, classic, atlas, meta, &st, backdrop, meta + 8)) return nullptr;
    for (int k = 0; k < 3; k++) meta[5 + k] = k < int(st.size()) ? st[size_t(k)] : NAN;
    jclass intArray = env->FindClass("[I");
    if (intArray == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(3, intArray, nullptr);
    if (result == nullptr) return nullptr;
    const std::vector<int32_t>* parts[3] = { &classic, &atlas, &backdrop };
    for (int k = 0; k < 3; k++) {
        jintArray a = env->NewIntArray(jsize(parts[k]->size()));
        if (a == nullptr) return nullptr;
        if (!parts[k]->empty()) env->SetIntArrayRegion(a, 0, jsize(parts[k]->size()), reinterpret_cast<const jint*>(parts[k]->data()));
        env->SetObjectArrayElement(result, k, a);
        env->DeleteLocalRef(a);
    }
    env->SetFloatArrayRegion(info, 0, 13, meta);
    return result;
} catch (...) {
    return nullptr;   // out of memory or a bad model: skip it
}
#endif
