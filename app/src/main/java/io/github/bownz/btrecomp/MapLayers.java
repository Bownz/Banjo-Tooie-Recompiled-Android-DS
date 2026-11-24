package io.github.bownz.btrecomp;

/**
 * Cuts the structure over Banjo out of the whole-area map (BottomScreenView.composeLayer): plain
 * arrays, no Android types, so it can be tested on a desktop JVM against real maps.
 */
final class MapLayers {
    private MapLayers() {
    }

    /**
     * a: the whole map's pixels (w x h; world place wMinX, wMinZ, wScale), changed in place: the
     * patch over (px, pz) where it differs from the storey map sp (sw x sh, its own place) is
     * taken from sp, with soft edges. Returns the cut (w x h), or null when the two maps are the
     * same around (px, pz). Uses about 60 MB for a 2048 x 2048 map while it runs.
     */
    static boolean[] compose(int[] a, int w, int h, float wMinX, float wMinZ, float wScale,
                             int[] sp, int sw, int sh, float sMinX, float sMinZ, float sScale, float px0, float pz0) {
        // The storey map resampled onto the whole map's pixels (same world place).
        int[] b = new int[w * h];
        final float k = sScale / wScale;
        final float ox = (wMinX - sMinX) * sScale, oz = (wMinZ - sMinZ) * sScale;

        boolean[] diff = new boolean[w * h];
        for (int z = 0; z < h; z++) {
            int sz = (int) (oz + (z + 0.5f) * k);
            for (int x = 0; x < w; x++) {
                int sx = (int) (ox + (x + 0.5f) * k);
                int v = sx >= 0 && sz >= 0 && sx < sw && sz < sh ? sp[sz * sw + sx] : 0;
                int i = z * w + x;
                b[i] = v;
                int u = a[i];
                int da = Math.abs((u >>> 24) - (v >>> 24));
                int d = Math.abs(((u >> 16) & 255) - ((v >> 16) & 255)) + Math.abs(((u >> 8) & 255) - ((v >> 8) & 255)) + Math.abs((u & 255) - (v & 255));
                diff[i] = da > 64 || d > 54;
            }
        }
        // Grown a few pixels, so the pieces of one structure (rings of steps, a bridge's planks)
        // join up: measured on Spiral Mountain, 1727 pieces become 242 whole structures.
        diff = growMask(diff, w, h, Math.max(2, Math.round(Math.max(w, h) / 680f)));
        // The differing patch Banjo is under (or the nearest one, a little way off).
        int px = (int) ((px0 - wMinX) * wScale), pz = (int) ((pz0 - wMinZ) * wScale);
        int seed = -1;
        int maxR = Math.max(4, Math.round(250f * wScale));
        for (int rr = 0; rr <= maxR && seed < 0; rr++) {
            for (int dz = -rr; dz <= rr && seed < 0; dz++) {
                for (int dx = -rr; dx <= rr; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != rr) continue;
                    int x = px + dx, z = pz + dz;
                    if (x < 0 || z < 0 || x >= w || z >= h) continue;
                    if (diff[z * w + x]) {
                        seed = z * w + x;
                        break;
                    }
                }
            }
        }
        if (seed < 0) {
            return null;
        }
        boolean[] cut = new boolean[w * h];
        int[] stack = new int[1 << 16];
        int top = 0, x0 = w, z0 = h, x1 = -1, z1 = -1;
        stack[top++] = seed;
        cut[seed] = true;
        while (top > 0) {
            int i = stack[--top];
            int x = i % w, z = i / w;
            if (x < x0) x0 = x;
            if (x > x1) x1 = x;
            if (z < z0) z0 = z;
            if (z > z1) z1 = z;
            if (top + 4 > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
            if (x > 0 && diff[i - 1] && !cut[i - 1]) { cut[i - 1] = true; stack[top++] = i - 1; }
            if (x < w - 1 && diff[i + 1] && !cut[i + 1]) { cut[i + 1] = true; stack[top++] = i + 1; }
            if (z > 0 && diff[i - w] && !cut[i - w]) { cut[i - w] = true; stack[top++] = i - w; }
            if (z < h - 1 && diff[i + w] && !cut[i + w]) { cut[i + w] = true; stack[top++] = i + w; }
        }
        // Soft edge: the cut grown by r pixels, then blurred over r (box, twice).
        final int r = Math.max(2, Math.round(Math.min(w, h) / 400f));
        final int bx0 = Math.max(0, x0 - 3 * r), bz0 = Math.max(0, z0 - 3 * r);
        final int bx1 = Math.min(w - 1, x1 + 3 * r), bz1 = Math.min(h - 1, z1 + 3 * r);
        final int bw = bx1 - bx0 + 1, bh = bz1 - bz0 + 1;
        float[] m = new float[bw * bh];
        for (int z = 0; z < bh; z++)
            for (int x = 0; x < bw; x++)
                if (cut[(z + bz0) * w + x + bx0]) m[z * bw + x] = 1f;
        m = boxMax(m, bw, bh, r);
        m = boxBlur(boxBlur(m, bw, bh, r), bw, bh, r);
        for (int z = 0; z < bh; z++) {
            for (int x = 0; x < bw; x++) {
                float t = m[z * bw + x];
                if (t <= 0.004f) continue;
                int i = (z + bz0) * w + x + bx0;
                a[i] = t >= 0.996f ? b[i] : mixArgb(a[i], b[i], t);
            }
        }
        return cut;
    }

    /**
     * Parchment background: an ink outline and a soft shadow around a level picture, from its
     * alpha (w x h). Returns ARGB of (w + 2 pad) x (h + 2 pad), the picture at (pad, pad).
     */
    static int[] ink(int[] alpha, int w, int h, int pad) {
        final int W = w + 2 * pad, H = h + 2 * pad;
        boolean[] land = new boolean[W * H];
        float[] m = new float[W * H];
        final int off = Math.max(1, pad / 3);   // the shadow falls down and to the right
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                if (alpha[z * w + x] > 128) {
                    land[(z + pad) * W + x + pad] = true;
                    m[(z + pad + off) * W + x + pad + off] = 1f;
                }
            }
        }
        int sr = Math.max(1, pad / 4);
        float[] shadow = boxBlur(boxBlur(m, W, H, sr), W, H, sr);
        boolean[] ring = growMask(land, W, H, 1);
        int[] out = new int[W * H];
        for (int i = 0; i < out.length; i++) {
            if (land[i]) continue;   // under the picture: nothing shows
            if (ring[i]) {
                out[i] = 0xD84E321C;   // ink: dark brown, mostly opaque
            } else {
                int a = Math.round(Math.min(1f, shadow[i]) * 95f);
                if (a > 0) out[i] = (a << 24) | 0x3C2614;
            }
        }
        return out;
    }

    /**
     * The orange bird foot on the shock jump pad's picture, cut out (Talon Trot's button): the
     * biggest patch of orange plus the white claw tips touching it, holes filled, a thin dark
     * edge round it, cropped. Returns {w, h, ARGB...} or null.
     */
    static int[] footCutout(int[] px, int w, int h) {
        final int n = w * h;
        boolean[] orange = new boolean[n], white = new boolean[n];
        for (int i = 0; i < n; i++) {
            int a = px[i] >>> 24, r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, b = px[i] & 255;
            orange[i] = a > 128 && r > 140 && r > g + 25 && g > b + 10;
            white[i] = a > 128 && r > 190 && g > 180 && b > 160 && Math.abs(r - g) < 40;
        }
        // The biggest orange patch.
        int[] label = new int[n];
        int[] stack = new int[n];
        int best = 0, bestSize = 0, next = 0;
        for (int s = 0; s < n; s++) {
            if (!orange[s] || label[s] != 0) continue;
            next++;
            int top = 0, size = 0;
            stack[top++] = s;
            label[s] = next;
            while (top > 0) {
                int i = stack[--top];
                size++;
                int x = i % w, y = i / w;
                if (x > 0 && orange[i - 1] && label[i - 1] == 0) { label[i - 1] = next; stack[top++] = i - 1; }
                if (x < w - 1 && orange[i + 1] && label[i + 1] == 0) { label[i + 1] = next; stack[top++] = i + 1; }
                if (y > 0 && orange[i - w] && label[i - w] == 0) { label[i - w] = next; stack[top++] = i - w; }
                if (y < h - 1 && orange[i + w] && label[i + w] == 0) { label[i + w] = next; stack[top++] = i + w; }
            }
            if (size > bestSize) { bestSize = size; best = next; }
        }
        if (best == 0) return null;
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) keep[i] = label[i] == best;
        boolean[] near = growMask(keep, w, h, Math.max(2, w / 60));
        for (int i = 0; i < n; i++) if (white[i] && near[i]) keep[i] = true;
        // Fill holes: whatever the outside can't reach.
        // (Each pixel is marked as it's pushed, so the stack never holds one twice.)
        boolean[] outside = new boolean[n];
        int top = 0;
        for (int i = 0; i < n; i++) {
            int x = i % w, y = i / w;
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && !keep[i]) { outside[i] = true; stack[top++] = i; }
        }
        while (top > 0) {
            int i = stack[--top];
            int x = i % w, y = i / w;
            if (x > 0 && !outside[i - 1] && !keep[i - 1]) { outside[i - 1] = true; stack[top++] = i - 1; }
            if (x < w - 1 && !outside[i + 1] && !keep[i + 1]) { outside[i + 1] = true; stack[top++] = i + 1; }
            if (y > 0 && !outside[i - w] && !keep[i - w]) { outside[i - w] = true; stack[top++] = i - w; }
            if (y < h - 1 && !outside[i + w] && !keep[i + w]) { outside[i + w] = true; stack[top++] = i + w; }
        }
        for (int i = 0; i < n; i++) if (!outside[i]) keep[i] = true;
        // A thin dark edge, then crop.
        int edge = Math.max(1, w / 100);
        boolean[] ring = growMask(keep, w, h, edge);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int i = 0; i < n; i++) {
            if (!ring[i]) continue;
            int x = i % w, y = i / w;
            x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
        }
        int ow = x1 - x0 + 1, oh = y1 - y0 + 1;
        int[] out = new int[2 + ow * oh];
        out[0] = ow; out[1] = oh;
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int i = (y + y0) * w + x + x0;
                out[2 + y * ow + x] = keep[i] ? (0xFF000000 | (px[i] & 0xFFFFFF)) : ring[i] ? 0xE0341C0C : 0;
            }
        }
        return out;
    }

    /** The mask grown by r pixels (separable, running counts). */
    static boolean[] growMask(boolean[] m, int w, int h, int r) {
        boolean[] t = new boolean[m.length], o = new boolean[m.length];
        for (int z = 0; z < h; z++) {
            int n = 0, row = z * w;
            for (int x = 0; x <= Math.min(w - 1, r); x++) if (m[row + x]) n++;
            for (int x = 0; x < w; x++) {
                t[row + x] = n > 0;
                if (x + r + 1 < w && m[row + x + r + 1]) n++;
                if (x - r >= 0 && m[row + x - r]) n--;
            }
        }
        for (int x = 0; x < w; x++) {
            int n = 0;
            for (int z = 0; z <= Math.min(h - 1, r); z++) if (t[z * w + x]) n++;
            for (int z = 0; z < h; z++) {
                o[z * w + x] = n > 0;
                if (z + r + 1 < h && t[(z + r + 1) * w + x]) n++;
                if (z - r >= 0 && t[(z - r) * w + x]) n--;
            }
        }
        return o;
    }

    private static int mixArgb(int u, int v, float t) {
        float s = 1f - t;
        int aa = Math.round((u >>> 24) * s + (v >>> 24) * t);
        int rr = Math.round(((u >> 16) & 255) * s + ((v >> 16) & 255) * t);
        int gg = Math.round(((u >> 8) & 255) * s + ((v >> 8) & 255) * t);
        int bb = Math.round((u & 255) * s + (v & 255) * t);
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /** Separable max over (2r+1)² (grows the cut). */
    private static float[] boxMax(float[] m, int w, int h, int r) {
        float[] t = new float[m.length], o = new float[m.length];
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                float v = 0;
                for (int d = Math.max(0, x - r); d <= Math.min(w - 1, x + r) && v < 1f; d++) v = Math.max(v, m[z * w + d]);
                t[z * w + x] = v;
            }
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                float v = 0;
                for (int d = Math.max(0, z - r); d <= Math.min(h - 1, z + r) && v < 1f; d++) v = Math.max(v, t[d * w + x]);
                o[z * w + x] = v;
            }
        return o;
    }

    /** Separable box blur over (2r+1)² (running sums). */
    private static float[] boxBlur(float[] m, int w, int h, int r) {
        float[] t = new float[m.length], o = new float[m.length];
        final float n = 2 * r + 1;
        for (int z = 0; z < h; z++) {
            float s = 0;
            for (int x = -r; x <= r; x++) s += m[z * w + Math.min(w - 1, Math.max(0, x))];
            for (int x = 0; x < w; x++) {
                t[z * w + x] = s / n;
                s += m[z * w + Math.min(w - 1, x + r + 1)] - m[z * w + Math.max(0, x - r)];
            }
        }
        for (int x = 0; x < w; x++) {
            float s = 0;
            for (int z = -r; z <= r; z++) s += t[Math.min(h - 1, Math.max(0, z)) * w + x];
            for (int z = 0; z < h; z++) {
                o[z * w + x] = s / n;
                s += t[Math.min(h - 1, z + r + 1) * w + x] - t[Math.max(0, z - r) * w + x];
            }
        }
        return o;
    }

}
