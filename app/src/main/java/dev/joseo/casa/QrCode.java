package dev.joseo.casa;

import java.nio.charset.StandardCharsets;

/**
 * A small QR encoder: byte mode, error correction level M, versions 1 to 6 (up to 106 bytes), which is plenty for
 * the panel's address. The TV shows the code so a phone camera can open the panel without typing anything.
 */
final class QrCode {

    // index = version; every block of a version has the same size at level M for versions 1..6
    private static final int[] DATA_CW = {0, 16, 28, 44, 64, 86, 108};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4};
    private static final int[] ALIGN = {0, 0, 18, 22, 26, 30, 34};

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if (x >= 256) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private final int size;
    private final boolean[][] dark;
    private final boolean[][] fixed;

    private QrCode(int version) {
        size = 17 + 4 * version;
        dark = new boolean[size][size];
        fixed = new boolean[size][size];
    }

    /** The module grid, true = dark, indexed [y][x]. Throws IllegalArgumentException if the text is too long. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = 0;
        for (int v = 1; v <= 6; v++) {
            if (data.length <= DATA_CW[v] - 2) {   // 4 mode bits + 8 count bits + terminator fit in 2 bytes' room
                version = v;
                break;
            }
        }
        if (version == 0) throw new IllegalArgumentException("text too long for a QR code");

        // 1. the bit stream
        int cap = DATA_CW[version];
        int[] bits = new int[cap * 8 + 16];
        int[] n = {0};
        put(bits, n, 0b0100, 4);
        put(bits, n, data.length, 8);
        for (byte b : data) put(bits, n, b & 0xFF, 8);
        int limit = cap * 8;
        put(bits, n, 0, Math.min(4, limit - n[0]));
        while (n[0] % 8 != 0) put(bits, n, 0, 1);
        for (int pad = 0xEC; n[0] < limit; pad ^= 0xEC ^ 0x11) put(bits, n, pad, 8);
        int[] cw = new int[cap];
        for (int i = 0; i < cap * 8; i++) cw[i >> 3] |= bits[i] << (7 - (i & 7));

        // 2. error correction per block, then interleave
        int nb = BLOCKS[version], per = cap / nb, ecn = EC_PER_BLOCK[version];
        int[][] dataBlocks = new int[nb][per];
        int[][] ecBlocks = new int[nb][];
        int[] gen = generator(ecn);
        for (int b = 0; b < nb; b++) {
            System.arraycopy(cw, b * per, dataBlocks[b], 0, per);
            ecBlocks[b] = remainder(dataBlocks[b], gen);
        }
        int[] all = new int[cap + nb * ecn];
        int k = 0;
        for (int i = 0; i < per; i++) for (int b = 0; b < nb; b++) all[k++] = dataBlocks[b][i];
        for (int i = 0; i < ecn; i++) for (int b = 0; b < nb; b++) all[k++] = ecBlocks[b][i];

        // 3. place and choose the best mask
        QrCode q = new QrCode(version);
        q.drawFunctionPatterns(version);
        q.placeData(all);
        int best = 0, bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            q.applyMask(m);
            q.drawFormat(m);
            int p = q.penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
            q.applyMask(m);   // masking twice undoes it
        }
        q.applyMask(best);
        q.drawFormat(best);
        return q.dark;
    }

    private static void put(int[] bits, int[] n, int value, int len) {
        for (int i = len - 1; i >= 0; i--) bits[n[0]++] = (value >> i) & 1;
    }

    private static int mul(int a, int b) {
        return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
    }

    private static int[] generator(int degree) {
        int[] g = new int[degree + 1];
        g[0] = 1;
        for (int i = 0; i < degree; i++) {   // multiply by (x - alpha^i)
            for (int j = degree; j >= 0; j--) {
                g[j] = mul(g[j], EXP[i]) ^ (j > 0 ? g[j - 1] : 0);
            }
        }
        return g;   // g[0] is the lowest-degree coefficient
    }

    private static int[] remainder(int[] data, int[] gen) {
        int deg = gen.length - 1;
        int[] r = new int[deg];
        for (int d : data) {
            int factor = d ^ r[0];
            System.arraycopy(r, 1, r, 0, deg - 1);
            r[deg - 1] = 0;
            for (int i = 0; i < deg; i++) r[i] ^= mul(gen[deg - 1 - i], factor);
        }
        return r;
    }

    // ---- matrix -----------------------------------------------------------------------------------------------

    private void set(int x, int y, boolean value) {
        dark[y][x] = value;
        fixed[y][x] = true;
    }

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {   // timing
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version >= 2) {
            int a = ALIGN[version];
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(a + dx, a + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormat(0);   // reserves the format cells; real bits are written once the mask is known
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, dist != 2 && dist != 4);
            }
        }
    }

    private void drawFormat(int mask) {
        int data = mask;   // level M = 0b00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);   // the always-dark module
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    private void placeData(int[] all) {
        int i = 0, total = all.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int v = 0; v < size; v++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - v : v;
                    if (!fixed[y][x]) {
                        dark[y][x] = i < total && bit(all[i >> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean flip;
                switch (m) {
                    case 0: flip = (x + y) % 2 == 0; break;
                    case 1: flip = y % 2 == 0; break;
                    case 2: flip = x % 3 == 0; break;
                    case 3: flip = (x + y) % 3 == 0; break;
                    case 4: flip = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: flip = x * y % 2 + x * y % 3 == 0; break;
                    case 6: flip = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: flip = ((x + y) % 2 + x * y % 3) % 2 == 0;
                }
                if (flip && !fixed[y][x]) dark[y][x] = !dark[y][x];
            }
        }
    }

    private int penalty() {
        int p = 0;
        for (int pass = 0; pass < 2; pass++) {   // rows, then columns
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? dark[a][b] : dark[b][a];
                    boolean prev = pass == 0 ? dark[a][b - 1] : dark[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) p += 3;
                        else if (run > 5) p++;
                    } else {
                        run = 1;
                    }
                }
                for (int b = 0; b + 10 < size; b++) {   // finder-like 1:1:3:1:1 with a white margin
                    int w = 0;
                    for (int t = 0; t < 11; t++) w = (w << 1) | ((pass == 0 ? dark[a][b + t] : dark[b + t][a]) ? 1 : 0);
                    if (w == 0b10111010000 || w == 0b00001011101) p += 40;
                }
            }
        }
        int count = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (dark[y][x]) count++;
                if (x + 1 < size && y + 1 < size && dark[y][x] == dark[y][x + 1] && dark[y][x] == dark[y + 1][x]
                        && dark[y][x] == dark[y + 1][x + 1]) p += 3;
            }
        }
        int total = size * size;
        p += ((Math.abs(count * 20 - total * 10) + total - 1) / total - 1) * 10;
        return p;
    }
}
