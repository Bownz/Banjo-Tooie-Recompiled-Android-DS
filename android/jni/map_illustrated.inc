// Illustrated map style (0.9.42): a clean drawing traced over what the level's model says,
// worked out on desktop with the user on Mayahem Temple (24 Nov):
//   - one smooth outline round the play area and the buildings on its edge, the level's own
//     canopy texture round it fading out;
//   - flat grass, lighter for higher ground, gentle light from the north-west, cast shadows;
//   - dirt as a soft brown wash, not shapes (the game's ground fades the same way);
//   - stone ground grey; paths exactly where the game draws its path overlays (their own
//     see-through edges), cream; big paved areas stone;
//   - a stone edge wherever terraced ground steps down, an ink line along cliffs;
//   - water blue with a pale shore; buildings (warm roofs, or masonry also walked on) as blocks.
// Included in map_render.cpp's anonymous namespace (uses its Tri, Texture, raster, wrapCoord,
// boxBlur and MapCache). Drawn into its own frame: the map's plus a margin for the buildings
// and the canopy (meta out = its own placement).
namespace illus {

constexpr uint8_t kGrass = 0, kDirt = 1, kStone = 2, kMoss = 3;

inline float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
inline float smooth(float a, float b, float v) { const float t = clamp01((v - a) / (b - a)); return t * t * (3 - 2 * t); }

struct Px { float r, g, b; };
inline Px mix(Px a, Px b, float k) { return { a.r + (b.r - a.r) * k, a.g + (b.g - a.g) * k, a.b + (b.b - a.b) * k }; }
inline Px mul(Px a, float k) { return { a.r * k, a.g * k, a.b * k }; }

// Three box passes ~ a Gaussian of about this radius.
void soft(std::vector<float>& a, int W, int H, int r) {
    if (r <= 0) return;
    const int b = std::max(1, int(std::lround(r * 0.58f)));
    boxBlur(a, W, H, b); boxBlur(a, W, H, b); boxBlur(a, W, H, b);
}

// Chamfer distance (px) from every pixel to the nearest pixel with src set, and that pixel's index.
void distance(const std::vector<uint8_t>& src, int W, int H, std::vector<float>& d, std::vector<int>* nearest) {
    const float inf = 1e9f;
    const size_t N = size_t(W) * H;
    d.assign(N, inf);
    if (nearest) nearest->assign(N, -1);
    for (size_t i = 0; i < N; i++) if (src[i]) { d[i] = 0; if (nearest) (*nearest)[i] = int(i); }
    auto relax = [&](int i, int j, float w) {
        if (d[j] + w < d[i]) { d[i] = d[j] + w; if (nearest) (*nearest)[i] = (*nearest)[j]; }
    };
    for (int z = 0; z < H; z++) for (int x = 0; x < W; x++) {
        const int i = z * W + x;
        if (x > 0) relax(i, i - 1, 1);
        if (z > 0) { relax(i, i - W, 1); if (x > 0) relax(i, i - W - 1, 1.414f); if (x + 1 < W) relax(i, i - W + 1, 1.414f); }
    }
    for (int z = H - 1; z >= 0; z--) for (int x = W - 1; x >= 0; x--) {
        const int i = z * W + x;
        if (x + 1 < W) relax(i, i + 1, 1);
        if (z + 1 < H) { relax(i, i + W, 1); if (x + 1 < W) relax(i, i + W + 1, 1.414f); if (x > 0) relax(i, i + W - 1, 1.414f); }
    }
}

std::vector<uint8_t> grow(const std::vector<uint8_t>& m, int W, int H, int r) { return morph(m, W, H, r, true); }
std::vector<uint8_t> shrink(const std::vector<uint8_t>& m, int W, int H, int r) { return morph(m, W, H, r, false); }

// Connected pieces (8-way): label per pixel (-1 none), and their sizes.
int label(const std::vector<uint8_t>& m, int W, int H, std::vector<int>& lab, std::vector<int>& size) {
    lab.assign(m.size(), -1); size.clear();
    std::vector<int> stack;
    for (size_t s = 0; s < m.size(); s++) {
        if (!m[s] || lab[s] >= 0) continue;
        const int id = int(size.size()); size.push_back(0);
        stack.push_back(int(s)); lab[s] = id;
        while (!stack.empty()) {
            const int i = stack.back(); stack.pop_back(); size[id]++;
            const int x = i % W, z = i / W;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                const int xx = x + dx, zz = z + dz;
                if (xx < 0 || zz < 0 || xx >= W || zz >= H) continue;
                const int j = zz * W + xx;
                if (m[j] && lab[j] < 0) { lab[j] = id; stack.push_back(j); }
            }
        }
    }
    return int(size.size());
}

using U8 = std::vector<uint8_t>;
inline U8 q8(const std::vector<float>& f, float scale = 255.0f) {
    U8 o(f.size());
    for (size_t i = 0; i < f.size(); i++) o[i] = uint8_t(std::clamp(f[i] * scale + 0.5f, 0.0f, 255.0f));
    return o;
}
inline float f8(uint8_t v) { return v * (1.0f / 255.0f); }

// A mask's soft-edged coverage (0..1): blurred, then a narrow ramp round 0.5: smooth outlines.
std::vector<uint8_t> shape(const std::vector<uint8_t>& m, int W, int H, int r) {
    std::vector<float> f(m.size());
    for (size_t i = 0; i < m.size(); i++) f[i] = m[i] ? 1.0f : 0.0f;
    soft(f, W, H, r);
    for (float& v : f) v = smooth(0.42f, 0.58f, v);
    return q8(f);
}

// The band just outside (out = true) or inside a soft shape, about w px wide.
inline float band(float cov, float w) { return clamp01(1.0f - std::fabs(cov - 0.5f) * (6.0f / std::max(0.5f, w))); }

}   // namespace illus

bool illustrate(const MapCache& c, const int32_t* classic, float capY, std::vector<int32_t>& out, float meta[5]) {
    using namespace illus;
    const int W0 = c.W, H0 = c.H;
    const int pad = std::max(40, int(0.06f * std::max(W0, H0)));
    const int W = W0 + 2 * pad, H = H0 + 2 * pad;
    const size_t N = size_t(W) * H;
    const float s = c.scale, minX = c.minX - pad / s, minZ = c.minZ - pad / s;
    const float px = 1.0f / s;   // world units per pixel
    const float F = std::max(0.5f, W0 / 2048.0f);   // pixel sizes below were tuned at 2048 px

    // ---- Inputs moved into this frame
    std::vector<uint8_t> play(N, 0), water(N, 0);
    std::vector<Px> col(N, Px{ 0, 0, 0 });
    for (int z = 0; z < H0; z++) for (int x = 0; x < W0; x++) {
        const size_t i0 = size_t(z) * W0 + x, i = size_t(z + pad) * W + (x + pad);
        const uint8_t k = c.kind[i0];
        play[i] = k == kMain || k == kWater || k == kSolid;
        water[i] = k == kWater;
        const uint32_t p = uint32_t(classic[i0]);
        col[i] = { float((p >> 16) & 255), float((p >> 8) & 255), float(p & 255) };
    }

    // ---- The model from straight above: height, texture and steepness per pixel
    std::vector<float> top(N, -1e9f);
    std::vector<const Texture*> tex(N, nullptr);
    std::vector<uint8_t> flatTop(N, 0);
    std::map<const Texture*, double> area;
    for (const Tri& t : c.tris) {
        if (t.water) continue;
        float X[3], Z[3];
        for (int k = 0; k < 3; k++) { X[k] = (t.v[k].x - minX) * s; Z[k] = (t.v[k].z - minZ) * s; }
        const float ax = t.v[1].x - t.v[0].x, ay = t.v[1].y - t.v[0].y, az = t.v[1].z - t.v[0].z;
        const float bx = t.v[2].x - t.v[0].x, by = t.v[2].y - t.v[0].y, bz = t.v[2].z - t.v[0].z;
        const float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        const float len = std::sqrt(nx * nx + ny * ny + nz * nz);
        if (len <= 0) continue;
        const float up = std::fabs(ny) / len;
        if (up < 0.2f) continue;
        if (t.tex) area[t.tex] += std::fabs(ax * bz - az * bx) / 2;
        raster(X, Z, W, H, [&](int i, float l0, float l1, float l2) {
            const float y = l0 * t.v[0].y + l1 * t.v[1].y + l2 * t.v[2].y;
            if (capY < 1e8f && y > capY) return;
            if (y < top[i]) return;
            top[i] = y; tex[i] = t.tex; flatTop[i] = up > 0.8f;
        });
    }
    std::vector<uint8_t> drawn(N);
    for (size_t i = 0; i < N; i++) drawn[i] = top[i] > -1e8f;
    // Height everywhere (gaps take the nearest drawn height)
    std::vector<float> hm(N);
    {
        std::vector<float> dd; std::vector<int> nn;
        distance(drawn, W, H, dd, &nn);
        for (size_t i = 0; i < N; i++) hm[i] = nn[i] >= 0 ? top[size_t(nn[i])] : 0.0f;
    }

    // ---- Ground classes from the game's colours (smoothed: texture ids alone give shards)
    std::vector<uint8_t> cls(N, kGrass);
    {
        std::vector<float> R(N), G(N), B(N);
        for (size_t i = 0; i < N; i++) { R[i] = col[i].r; G[i] = col[i].g; B[i] = col[i].b; }
        const int r = int(3 * F);
        soft(R, W, H, r); soft(G, W, H, r); soft(B, W, H, r);
        std::vector<float> one[4];
        for (auto& o : one) o.assign(N, 0);
        for (size_t i = 0; i < N; i++) {
            const float mx = std::max({ R[i], G[i], B[i] }), mn = std::min({ R[i], G[i], B[i] });
            const float sat = mx > 0 ? (mx - mn) / mx : 0;
            float h = 0;
            if (mx > mn) {
                if (mx == R[i]) h = std::fmod((G[i] - B[i]) / (mx - mn), 6.0f);
                else if (mx == G[i]) h = (B[i] - R[i]) / (mx - mn) + 2;
                else h = (R[i] - G[i]) / (mx - mn) + 4;
                h /= 6; if (h < 0) h += 1;
            }
            uint8_t k = kGrass;
            if (sat <= 0.20f) k = kStone;
            else if (h < 0.13f && sat > 0.38f) k = kDirt;
            else if (h >= 0.33f && h < 0.5f && sat > 0.42f) k = kMoss;
            one[k][i] = 1;
        }
        for (auto& o : one) soft(o, W, H, int(6 * F));
        for (size_t i = 0; i < N; i++) {
            int best = 0;
            for (int k = 1; k < 4; k++) if (one[k][i] > one[best][i]) best = k;
            cls[i] = uint8_t(best);
        }
    }

    std::vector<Px>().swap(col);   // not needed from here (memory: two maps are drawn at once)

    // ---- Textures: average colour, saturation; which are walked on (inside the play area)
    std::map<const Texture*, int> inside, all;
    for (size_t i = 0; i < N; i++) if (tex[i]) { all[tex[i]]++; if (play[i]) inside[tex[i]]++; }
    auto satOf = [](const Texture* t) {
        const float mx = std::max({ t->avgR, t->avgG, t->avgB }), mn = std::min({ t->avgR, t->avgG, t->avgB });
        return mx > 0 ? (mx - mn) / mx : 0.0f;
    };
    auto warm = [](const Texture* t) { return t->avgR > t->avgG * 0.85f && t->avgR > t->avgB * 1.2f && t->avgR > 60; };

    // ---- Paving: the grey/cobble textures on flat ground, drawn exactly with their own
    // see-through edges (path overlays), where nothing is over them
    std::vector<uint8_t> paving(N, 0);
    {
        std::set<const Texture*> pv;
        for (auto& [t, n] : inside) {
            if (!t || t->bluish || n < int(400 * F * F)) continue;
            if (satOf(t) < 0.42f && !warm(t)) pv.insert(t);
        }
        for (const Tri& t : c.tris) {
            if (t.water || !t.tex || !pv.count(t.tex)) continue;
            float X[3], Z[3];
            for (int k = 0; k < 3; k++) { X[k] = (t.v[k].x - minX) * s; Z[k] = (t.v[k].z - minZ) * s; }
            const float ax = t.v[1].x - t.v[0].x, ay = t.v[1].y - t.v[0].y, az = t.v[1].z - t.v[0].z;
            const float bx = t.v[2].x - t.v[0].x, by = t.v[2].y - t.v[0].y, bz = t.v[2].z - t.v[0].z;
            const float ny = az * bx - ax * bz, len = std::sqrt(std::pow(ay * bz - az * by, 2.0f) + ny * ny + std::pow(ax * by - ay * bx, 2.0f));
            if (len <= 0 || std::fabs(ny) / len < 0.8f) continue;
            raster(X, Z, W, H, [&](int i, float l0, float l1, float l2) {
                const float y = l0 * t.v[0].y + l1 * t.v[1].y + l2 * t.v[2].y;
                if (y < top[i] - 80.0f || (capY < 1e8f && y > capY)) return;   // under a bridge or roof
                const float ss = l0 * t.v[0].s + l1 * t.v[1].s + l2 * t.v[2].s;
                const float tt = l0 * t.v[0].t + l1 * t.v[1].t + l2 * t.v[2].t;
                const int tws = std::min(t.tile.w, t.tex->w), twt = std::min(t.tile.h, t.tex->h);
                const int cms = t.tile.w > t.tex->w ? (t.tile.cms & 1) : t.tile.cms;
                const int cmt = t.tile.h > t.tex->h ? (t.tile.cmt & 1) : t.tile.cmt;
                const int sa = wrapCoord(ss, t.tex->w, cms, t.tile.ms, tws), ta = wrapCoord(tt, t.tex->h, cmt, t.tile.mt, twt);
                if (t.tex->px[size_t(ta) * t.tex->w + sa].a > 127) paving[i] = 1;
            });
        }
        for (size_t i = 0; i < N; i++) if (!play[i] || water[i]) paving[i] = 0;
    }
    // Paths are the narrow paving; wide pieces are plazas and platform tops (stone)
    std::vector<uint8_t> path(N, 0), plaza(N, 0);
    {
        std::vector<uint8_t> inv(N);
        for (size_t i = 0; i < N; i++) inv[i] = !paving[i];
        std::vector<float> din;
        distance(inv, W, H, din, nullptr);   // how deep inside the paving
        std::vector<uint8_t> core(N);
        for (size_t i = 0; i < N; i++) core[i] = din[i] > 40.0f * F;   // more than 80 px across: a plaza, not a junction
        std::vector<uint8_t> wide = grow(core, W, H, int(44 * F));
        for (size_t i = 0; i < N; i++) {
            plaza[i] = paving[i] && wide[i];
            path[i] = paving[i] && !wide[i];
        }
    }

    // ---- Buildings: raised blocks beside the play area: warm roofs (thatch, timber), or the
    // masonry also walked on
    std::vector<uint8_t> build(N, 0);
    {
        std::vector<float> dPlay; std::vector<int> nPlay;
        distance(play, W, H, dPlay, &nPlay);
        std::set<const Texture*> canopyLike;
        const Texture* canopy = nullptr; double ca = 0;
        for (auto& [t, a] : area) if (a > ca) { ca = a; canopy = t; }
        std::vector<uint8_t> cand(N, 0);
        for (size_t i = 0; i < N; i++) {
            const Texture* t = tex[i];
            if (!t || t == canopy || play[i] || dPlay[i] > 150.0f * F || nPlay[i] < 0) continue;
            const bool masonry = inside.count(t) && inside.at(t) > int(2000 * F * F);
            const bool w = warm(t) && inside.count(t) == 0 ? true : (warm(t) && inside.at(t) < all.at(t) / 2);
            const bool raised = top[i] > top[size_t(nPlay[i])] + 250.0f;
            if ((masonry && raised) || w) cand[i] = 1;
        }
        // also the roof's part inside the play area's edge
        for (size_t i = 0; i < N; i++) if (tex[i] && warm(tex[i]) && inside.count(tex[i]) && all.count(tex[i]) && inside.at(tex[i]) < all.at(tex[i]) / 2) cand[i] = 1;
        cand = shrink(grow(cand, W, H, 2), W, H, 2);
        std::vector<int> lab, size;
        label(cand, W, H, lab, size);
        std::vector<int> x0(size.size(), W), x1(size.size(), 0), z0(size.size(), H), z1(size.size(), 0);
        for (size_t i = 0; i < N; i++) if (lab[i] >= 0) {
            const int id = lab[i], x = int(i % W), z = int(i / W);
            x0[id] = std::min(x0[id], x); x1[id] = std::max(x1[id], x); z0[id] = std::min(z0[id], z); z1[id] = std::max(z1[id], z);
        }
        for (size_t i = 0; i < N; i++) {
            const int id = lab[i];
            if (id < 0) continue;
            const float fill = float(size[id]) / float((x1[id] - x0[id] + 1) * (z1[id] - z0[id] + 1));
            if (size[id] >= int(300 * F * F) && size[id] <= int(80000 * F * F) && fill > 0.45f) build[i] = 1;
        }
        build = shrink(grow(build, W, H, int(4 * F)), W, H, int(4 * F));
    }

    // ---- Shapes
    std::vector<uint8_t> outerM(N);
    for (size_t i = 0; i < N; i++) outerM[i] = play[i] || build[i];
    outerM = shrink(grow(outerM, W, H, int(6 * F)), W, H, int(6 * F));
    const U8 outer = shape(outerM, W, H, int(7 * F));
    const U8 waterA = shape(water, W, H, int(3 * F));
    const U8 deepA = shape(shrink(water, W, H, int(14 * F)), W, H, int(4 * F));
    const U8 pathA = shape(path, W, H, std::max(1, int(1.5f * F)));
    const U8 plazaA = shape(plaza, W, H, int(2 * F));
    const U8 buildA = shape(build, W, H, int(1.5f * F));
    U8 dirtA;
    {
        std::vector<float> f(N);
        for (size_t i = 0; i < N; i++) f[i] = cls[i] == kDirt && play[i] && !water[i] ? 1.0f : 0.0f;
        soft(f, W, H, int(9 * F));
        dirtA = q8(f);
    }
    U8 stoneA;
    {
        std::vector<uint8_t> sm(N);
        for (size_t i = 0; i < N; i++) sm[i] = cls[i] == kStone && play[i] && !water[i] && !paving[i];
        sm = grow(shrink(sm, W, H, int(10 * F)), W, H, int(10 * F));   // only sizeable stone ground
        stoneA = shape(sm, W, H, int(3 * F));
    }

    // ---- Light: from the north-west, gentle; cast shadows; steps and cliffs
    U8 hs8, sh8;
    {
        std::vector<float> hs(N), sh(N, 0);
        std::vector<float> h2 = hm;
        soft(h2, W, H, int(3 * F));
        const float az = 315.0f * 3.14159265f / 180, al = 50.0f * 3.14159265f / 180;
        for (int z = 1; z < H - 1; z++) for (int x = 1; x < W - 1; x++) {
            const size_t i = size_t(z) * W + x;
            const float dx = (h2[i + 1] - h2[i - 1]) / (2 * px), dy = (h2[i + W] - h2[i - W]) / (2 * px);
            const float sl = std::atan(std::hypot(dx, dy) * 0.8f), asp = std::atan2(-dx, dy);
            hs[i] = clamp01(std::sin(al) * std::cos(sl) + std::cos(al) * std::sin(sl) * std::cos(az - asp));
        }
        const int L = int(60 * F);
        for (int k = 1; k < L; k += 2) {
            const float need = k * px * 1.414f * 0.9f, w = std::sqrt(1.0f - float(k) / L);
            for (int z = k; z < H; z++) for (int x = k; x < W; x++) {
                const size_t i = size_t(z) * W + x, j = size_t(z - k) * W + (x - k);
                if (hm[j] - hm[i] > need) sh[i] = std::max(sh[i], w);
            }
        }
        soft(sh, W, H, int(2 * F));
        hs8 = q8(hs); sh8 = q8(sh);
    }
    std::vector<uint8_t> stepM(N, 0), cliffM(N, 0);
    for (int z = 1; z < H - 1; z++) for (int x = 1; x < W - 1; x++) {
        const size_t i = size_t(z) * W + x;
        if (!play[i] || water[i]) continue;
        float mx = hm[i], mn = hm[i];
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) { const float v = hm[i + dz * W + dx]; mx = std::max(mx, v); mn = std::min(mn, v); }
        // the line on the higher side of each drop
        if (mx - mn > 45.0f && hm[i] > mn + 0.5f * (mx - mn)) (mx - mn > 140.0f ? cliffM : stepM)[i] = 1;
    }
    const U8 stepA = shape(stepM, W, H, 1), cliffA = shape(cliffM, W, H, 1);
    stepM.clear(); stepM.shrink_to_fit(); cliffM.clear(); cliffM.shrink_to_fit();

    // ---- Canopy: the level's biggest texture, laid flat at its own mapping
    const Texture* canopy = nullptr; double cBest = 0;
    for (auto& [t, a] : area) if (a > cBest && !(inside.count(t) && inside.at(t) > all.at(t) / 2)) { cBest = a; canopy = t; }
    float dsx = 0.04f, dsz = 0, dtx = 0, dtz = 0.04f;
    Tile ctile;
    if (canopy) {
        std::vector<float> a, b, cc, d;
        for (const Tri& t : c.tris) {
            if (t.tex != canopy) continue;
            const float ax = t.v[1].x - t.v[0].x, az = t.v[1].z - t.v[0].z, bx = t.v[2].x - t.v[0].x, bz = t.v[2].z - t.v[0].z;
            const float det = ax * bz - az * bx;
            if (std::fabs(det) < 1.0f) continue;
            const float as = t.v[1].s - t.v[0].s, bs = t.v[2].s - t.v[0].s, at = t.v[1].t - t.v[0].t, bt = t.v[2].t - t.v[0].t;
            a.push_back((as * bz - bs * az) / det); b.push_back((bs * ax - as * bx) / det);
            cc.push_back((at * bz - bt * az) / det); d.push_back((bt * ax - at * bx) / det);
            ctile = t.tile;
        }
        auto med = [](std::vector<float>& v) { if (v.empty()) return 0.0f; std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end()); return v[v.size() / 2]; };
        if (!a.empty()) { dsx = med(a); dsz = med(b); dtx = med(cc); dtz = med(d); }
        // Only a leafy texture laid out evenly: otherwise (Spiral Mountain's biggest is a cliff
        // face) it smears into stripes, so a plain leafy edge is drawn instead.
        const float det = std::fabs(dsx * dtz - dsz * dtx);
        const float l1 = std::hypot(dsx, dsz), l2 = std::hypot(dtx, dtz);
        const bool even = det > 1e-6f && l1 > 0 && l2 > 0 && det / (l1 * l2) > 0.5f && std::max(l1, l2) / std::min(l1, l2) < 3.0f;
        const bool leafy = canopy->avgG > canopy->avgR * 1.2f && canopy->avgG > canopy->avgB * 1.2f;
        if (a.empty() || !even || !leafy) canopy = nullptr;
    }
    U8 dOut, dIn;   // px, up to 255
    {
        std::vector<uint8_t> o(N), notO(N);
        for (size_t i = 0; i < N; i++) { o[i] = outer[i] > 127; notO[i] = !o[i]; }
        std::vector<float> d;
        distance(o, W, H, d, nullptr); dOut = q8(d, 1.0f);
        distance(notO, W, H, d, nullptr); dIn = q8(d, 1.0f);
    }
    float lo = 1e9f, hi = -1e9f;
    {
        std::vector<float> v;
        for (size_t i = 0; i < N; i += 7) if (play[i]) v.push_back(hm[i]);
        if (!v.empty()) {
            std::sort(v.begin(), v.end());
            lo = v[v.size() * 3 / 100]; hi = v[v.size() * 97 / 100];
        }
        if (hi <= lo) hi = lo + 1;
    }

    // ---- Paint
    const Px grass{ 127, 198, 74 }, dirt{ 158, 122, 74 }, stone{ 184, 188, 178 }, stoneEdge{ 141, 145, 135 };
    const Px pathC{ 210, 206, 187 }, pathEdge{ 159, 154, 132 }, ink{ 43, 42, 53 }, lip{ 211, 214, 201 };
    const Px waterC{ 62, 168, 236 }, deepC{ 42, 132, 207 }, shoreC{ 196, 236, 255 }, rim{ 250, 244, 222 };
    out.assign(N, 0);
    for (int z = 0; z < H; z++) for (int x = 0; x < W; x++) {
        const size_t i = size_t(z) * W + x;
        // the canopy outside, fading out
        Px o{ 30, 62, 34 };
        if (!canopy) {
            // a plain leafy edge: soft dark-green clumps
            const float wx = minX + (x + 0.5f) / s, wz = minZ + (z + 0.5f) / s;
            const float n = 0.5f + 0.25f * std::sin(wx * 0.011f + std::sin(wz * 0.007f) * 2.0f) + 0.25f * std::sin(wz * 0.013f + std::sin(wx * 0.009f) * 2.0f);
            o = mix(Px{ 26, 58, 30 }, Px{ 52, 112, 50 }, n);
        } else {
            const float wx = minX + (x + 0.5f) / s, wz = minZ + (z + 0.5f) / s;
            const int tws = std::min(ctile.w > 0 ? ctile.w : canopy->w, canopy->w), twt = std::min(ctile.h > 0 ? ctile.h : canopy->h, canopy->h);
            const int ps = wrapCoord(wx * dsx + wz * dsz, canopy->w, ctile.cms & 1, ctile.ms, tws);
            const int pt = wrapCoord(wx * dtx + wz * dtz, canopy->h, ctile.cmt & 1, ctile.mt, twt);
            const Rgba& t = canopy->px[size_t(pt) * canopy->w + ps];
            o = { t.r * 0.8f, t.g * 0.8f, t.b * 0.8f };
        }
        float alphaOut = 1.0f - smooth(70.0f * F, 120.0f * F, float(dOut[i]));

        // the ground
        const float lift = clamp01((hm[i] - lo) / (hi - lo));
        Px g = mix(grass, Px{ 255, 255, 255 }, 0.15f * lift);
        g = mix(g, stone, f8(stoneA[i]));
        const float wash = std::min(0.72f, f8(dirtA[i]) * 0.85f) * (1.0f - std::max({ f8(pathA[i]), f8(plazaA[i]), f8(stoneA[i]), f8(buildA[i]) }));
        g = mix(g, mul(dirt, 0.9f + 0.2f * f8(hs8[i])), wash);
        g = mul(g, 1.0f - (0.2f - 0.3f * (f8(hs8[i]) - 0.7f)));
        g = mul(g, 1.0f - 0.3f * f8(sh8[i]));
        // terrace steps: a lit stone lip; cliffs: ink
        g = mix(g, lip, f8(stepA[i]) * 0.85f);
        g = mix(g, ink, f8(cliffA[i]) * 0.8f);
        // paving
        g = mix(g, stoneEdge, band(f8(plazaA[i]), 2.0f * F) * (f8(plazaA[i]) > 0.05f ? 1.0f : 0.0f));
        g = mix(g, stone, f8(plazaA[i]));
        g = mix(g, pathEdge, std::min(1.0f, band(f8(pathA[i]), 2.5f * F)) * 0.9f);
        g = mix(g, pathC, f8(pathA[i]));
        // water
        if (f8(waterA[i]) > 0) {
            Px w = mix(waterC, deepC, f8(deepA[i]) * 0.55f);
            w = mix(w, shoreC, band(f8(waterA[i]), 4.0f * F) * (1.0f - f8(deepA[i])));
            g = mix(g, w, f8(waterA[i]));
            g = mix(g, ink, band(f8(waterA[i]), 1.5f * F) * 0.8f);
        }
        // buildings: walls below the roof (offset), the roof, an outline
        {
            const int sx = int(2 * F), sz = int(6 * F);
            const size_t j = (x >= sx && z >= sz) ? size_t(z - sz) * W + (x - sx) : i;
            const float wall = f8(buildA[j]) * (1.0f - f8(buildA[i]));
            g = mix(g, Px{ 125, 86, 52 }, wall);
            if (f8(buildA[i]) > 0) {
                const Texture* t = tex[i];
                Px roof = t ? Px{ std::min(255.0f, t->avgR * 1.35f), std::min(255.0f, t->avgG * 1.35f), std::min(255.0f, t->avgB * 1.35f) } : Px{ 217, 205, 176 };
                roof = mix(roof, Px{ 255, 255, 255 }, 0.25f);
                g = mix(g, roof, f8(buildA[i]));
            }
            g = mix(g, ink, band(f8(buildA[i]), 1.5f * F) * 0.9f);
        }
        // the jungle wall shades the ground's edge; the outline
        g = mul(g, 1.0f - 0.28f * std::exp(-float(dIn[i]) / (10.0f * F)) * (f8(outer[i]) > 0.5f ? 1.0f : 0.0f));
        Px p = mix(o, g, f8(outer[i]));
        const float edge = f8(outer[i]) < 0.5f ? clamp01(1.0f - float(dOut[i]) / (3.0f * F)) : 0.0f;
        p = mix(p, rim, edge * (1.0f - f8(outer[i])));
        p = mix(p, ink, band(f8(outer[i]), 1.2f * F) * 0.7f);
        const float a = std::max(f8(outer[i]), alphaOut);
        if (a <= 0.004f) continue;
        auto cl = [](float v) { return uint32_t(std::clamp(v, 0.0f, 255.0f)); };
        out[i] = int32_t((uint32_t(std::lround(a * 255)) << 24) | (cl(p.r) << 16) | (cl(p.g) << 8) | cl(p.b));
    }
    meta[0] = float(W); meta[1] = float(H); meta[2] = minX; meta[3] = minZ; meta[4] = s;
    return true;
}
