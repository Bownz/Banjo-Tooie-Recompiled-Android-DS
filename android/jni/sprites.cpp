// The game's own sprites (the HUD's Jiggy, note, honeycomb, feathers, eggs, character heads),
// read at run time from the ROM the player supplied, for the bottom screen's map icons.
// Nothing from the ROM is stored in the repository; the app decodes it on the device.
//
// Asset table (US 1.0): u32 count at ROM 0x5180, entries from 0x5188, 4 bytes each:
// 24-bit offset / 4 (from 0x12B24), then a byte: high nibble 1 = compressed (skip 2 bytes,
// then raw deflate), low nibble = type (7 = sprite). An asset ends where the next one starts.
//
// Sprite: s16 frame count, s16 format (1 = CI4, 4 = CI8, 0x40 = I8, 0x400 = RGBA16,
// 0x800 = RGBA32), s16 x2 (?), s16 width, height, s16 x2 (?), then u32 data offset and two
// more u32; frame records from 0x1C, 12 bytes each (s16 x4, u32 offset into the data). For CI
// formats the palette (RGBA16) is at the data offset and the frame's offset counts from there.
// Found 13 Nov 2026 by decoding every sprite in the user's ROM (see bt-android-map-layers.md).

#include <cstdint>
#include <cstring>
#include <span>
#include <vector>
#include <zlib.h>

#ifndef BT_SPRITE_TEST
#include <jni.h>
// librecomp/game.hpp declares it; its headers pull in the whole RSP and ultramodern set.
namespace recomp { std::span<const uint8_t> get_rom(); }
#endif

namespace {

uint32_t be32(const uint8_t* p) { return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3]; }
uint16_t be16(const uint8_t* p) { return uint16_t((p[0] << 8) | p[1]); }

constexpr uint32_t kTable = 0x5180, kBase = 0x12B24;

uint32_t rgba16(uint16_t v) {   // to Android ARGB
    const uint32_t r = ((v >> 11) & 31) * 255 / 31, g = ((v >> 6) & 31) * 255 / 31, b = ((v >> 1) & 31) * 255 / 31;
    return ((v & 1) ? 0xFF000000u : 0u) | (r << 16) | (g << 8) | b;
}

bool loadAsset(std::span<const uint8_t> rom, int index, std::vector<uint8_t>& out) {
    if (rom.size() < kTable + 8) return false;
    const uint32_t count = be32(&rom[kTable]);
    if (index < 0 || uint32_t(index) + 1 >= count || kTable + 8 + 4 * (uint32_t(index) + 2) > rom.size()) return false;
    const uint8_t* e = &rom[kTable + 8 + 4 * uint32_t(index)];
    const uint32_t start = kBase + ((uint32_t(e[0]) << 16 | uint32_t(e[1]) << 8 | e[2]) * 4);
    const uint32_t end = kBase + ((uint32_t(e[4]) << 16 | uint32_t(e[5]) << 8 | e[6]) * 4);
    const bool compressed = (e[3] >> 4) & 1;
    if ((e[3] & 15) != 7 || end <= start || end > rom.size() || end - start > 0x100000) return false;
    if (!compressed) {
        out.assign(rom.begin() + start, rom.begin() + end);
        return true;
    }
    z_stream z{};
    if (inflateInit2(&z, -MAX_WBITS) != Z_OK) return false;
    z.next_in = const_cast<Bytef*>(&rom[start + 2]);
    z.avail_in = end - start - 2;
    out.resize(0x40000);
    z.next_out = out.data();
    z.avail_out = uInt(out.size());
    const int status = inflate(&z, Z_FINISH);
    out.resize(z.total_out);
    inflateEnd(&z);
    return (status == Z_STREAM_END || status == Z_BUF_ERROR) && !out.empty();
}

}   // namespace

// One frame of sprite `index` as ARGB pixels; false if it isn't a sprite this can decode.
bool bt_decode_sprite(std::span<const uint8_t> rom, int index, int frame, int& w, int& h, std::vector<uint32_t>& argb) {
    std::vector<uint8_t> d;
    if (!loadAsset(rom, index, d) || d.size() < 0x28) return false;
    const int frames = int16_t(be16(&d[0])), format = be16(&d[2]);
    w = int16_t(be16(&d[8]));
    h = int16_t(be16(&d[10]));
    if (frames <= 0 || frame < 0 || frame >= frames || w <= 0 || h <= 0 || w > 256 || h > 256) return false;
    const size_t rec = 0x1C + 12 * size_t(frame);
    if (rec + 12 > d.size()) return false;
    const size_t base = be32(&d[0x10]);
    const size_t p = base + be32(&d[rec + 8]);
    const size_t n = size_t(w) * h;
    size_t need = 0, palSize = 0;
    switch (format) {
        case 0x001: need = (n + 1) / 2; palSize = 16; break;
        case 0x004: need = n; palSize = 256; break;
        case 0x040: need = n; break;
        case 0x400: need = 2 * n; break;
        case 0x800: need = 4 * n; break;
        default: return false;
    }
    if (p + need > d.size() || base + 2 * palSize > d.size()) return false;
    argb.resize(n);
    for (size_t i = 0; i < n; i++) {
        switch (format) {
            case 0x001: {
                const uint8_t b = d[p + i / 2];
                argb[i] = rgba16(be16(&d[base + 2 * ((i & 1) ? (b & 15) : (b >> 4))]));
                break;
            }
            case 0x004: argb[i] = rgba16(be16(&d[base + 2 * d[p + i]])); break;
            case 0x040: { const uint32_t v = d[p + i]; argb[i] = 0xFF000000u | v << 16 | v << 8 | v; break; }
            case 0x400: argb[i] = rgba16(be16(&d[p + 2 * i])); break;
            case 0x800: {
                const uint8_t* q = &d[p + 4 * i];
                argb[i] = uint32_t(q[3]) << 24 | uint32_t(q[0]) << 16 | uint32_t(q[1]) << 8 | q[2];
                break;
            }
        }
    }
    return true;
}

#ifndef BT_SPRITE_TEST
// GameState.nativeSprite: { width, height, ARGB pixels... } or null.
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_bownz_btrecomp_GameState_nativeSprite(JNIEnv* env, jclass, jint index, jint frame) {
    const std::span<const uint8_t> rom = recomp::get_rom();
    int w = 0, h = 0;
    std::vector<uint32_t> argb;
    if (rom.empty() || !bt_decode_sprite(rom, index, frame, w, h, argb)) return nullptr;
    jintArray out = env->NewIntArray(jsize(2 + argb.size()));
    if (out == nullptr) return nullptr;
    const jint size[2] = { w, h };
    env->SetIntArrayRegion(out, 0, 2, size);
    env->SetIntArrayRegion(out, 2, jsize(argb.size()), reinterpret_cast<const jint*>(argb.data()));
    return out;
}
#endif
