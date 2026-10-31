package com.folio.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Minimal QR Code encoder (byte mode, error correction L, versions 1-9) so Folio Transfer can show its
 * address as a scannable code without any library. Short URLs only; encode() returns null if too long.
 */
final class QrCode {
    private static final int[] DATA = {0, 19, 34, 55, 80, 108, 136, 156, 194, 232};
    private static final int[] EC = {0, 7, 10, 15, 20, 26, 18, 20, 24, 30};
    private static final int[] BLOCKS = {0, 1, 1, 1, 1, 1, 2, 2, 2, 2};
    private static final int[][] ALIGN = {{}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}};

    private final int n;
    private final boolean[][] mod;
    private final boolean[][] fn;

    private QrCode(int version) {
        n = version * 4 + 17;
        mod = new boolean[n][n];
        fn = new boolean[n][n];
    }

    static boolean[][] encode(String text) {
        byte[] d;
        try {
            d = text.getBytes("UTF-8");
        } catch (Exception e) {
            return null;
        }
        int v = 1;
        while (v <= 9 && d.length > DATA[v] - 2) v++;
        if (v > 9) return null;

        // bit stream: mode 0100, 8-bit count, bytes, terminator, padding
        int cap = DATA[v];
        boolean[] bits = new boolean[cap * 8 + 16];
        int[] pos = {0};
        put(bits, pos, 4, 4);
        put(bits, pos, d.length, 8);
        for (byte b : d) put(bits, pos, b & 0xFF, 8);
        put(bits, pos, 0, Math.min(4, cap * 8 - pos[0]));
        while (pos[0] % 8 != 0) bits[pos[0]++] = false;
        byte[] data = new byte[cap];
        for (int i = 0; i < pos[0] / 8; i++) {
            int x = 0;
            for (int j = 0; j < 8; j++) x = (x << 1) | (bits[i * 8 + j] ? 1 : 0);
            data[i] = (byte) x;
        }
        for (int i = pos[0] / 8, pad = 0xEC; i < cap; i++, pad ^= 0xEC ^ 0x11) data[i] = (byte) pad;

        // Reed-Solomon per block, then interleave
        int nb = BLOCKS[v], per = cap / nb, ec = EC[v];
        byte[][] blocks = new byte[nb][], ecs = new byte[nb][];
        byte[] gen = generator(ec);
        for (int i = 0; i < nb; i++) {
            blocks[i] = java.util.Arrays.copyOfRange(data, i * per, (i + 1) * per);
            ecs[i] = remainder(blocks[i], gen);
        }
        byte[] all = new byte[(per + ec) * nb];
        int k = 0;
        for (int i = 0; i < per; i++) for (int b = 0; b < nb; b++) all[k++] = blocks[b][i];
        for (int i = 0; i < ec; i++) for (int b = 0; b < nb; b++) all[k++] = ecs[b][i];

        QrCode q = new QrCode(v);
        q.drawFunctions(v);
        q.place(all);
        int best = 0, bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            q.mask(m);
            q.drawFormat(m);
            int p = q.penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
            q.mask(m); // undo (xor is its own inverse)
        }
        q.mask(best);
        q.drawFormat(best);
        return q.mod;
    }

    private static void put(boolean[] bits, int[] pos, int val, int len) {
        for (int i = len - 1; i >= 0; i--) bits[pos[0]++] = ((val >>> i) & 1) != 0;
    }

    // ---------- Reed-Solomon over GF(256), polynomial 0x11D ----------
    private static int mul(int a, int b) {
        int r = 0;
        for (int i = 7; i >= 0; i--) {
            r = (r << 1) ^ ((r >>> 7) * 0x11D);
            r ^= ((b >>> i) & 1) * a;
        }
        return r & 0xFF;
    }

    private static byte[] generator(int deg) {
        byte[] g = new byte[deg];
        g[deg - 1] = 1;
        int root = 1;
        for (int i = 0; i < deg; i++) {
            for (int j = 0; j < deg; j++) {
                g[j] = (byte) mul(g[j] & 0xFF, root);
                if (j + 1 < deg) g[j] ^= g[j + 1];
            }
            root = mul(root, 2);
        }
        return g;
    }

    private static byte[] remainder(byte[] data, byte[] gen) {
        byte[] r = new byte[gen.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= mul(gen[i] & 0xFF, f);
        }
        return r;
    }

    // ---------- layout ----------
    private void set(int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private void drawFunctions(int v) {
        for (int i = 0; i < n; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(n - 4, 3);
        finder(3, n - 4);
        int[] a = ALIGN[v];
        for (int i = 0; i < a.length; i++)
            for (int j = 0; j < a.length; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == a.length - 1) || (i == a.length - 1 && j == 0);
                if (!corner) alignment(a[i], a[j]);
            }
        drawFormat(0); // reserve
        if (v >= 7) {
            int rem = v;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = v << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int x = n - 11 + i % 3, y = i / 3;
                set(x, y, b);
                set(y, x, b);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= n || y >= n) continue;
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                set(x, y, d != 2 && d != 4);
            }
    }

    private void alignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
    }

    private void drawFormat(int m) {
        int data = (1 << 3) | m; // ECC level L = 01
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(n - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, n - 15 + i, bit(bits, i));
        set(8, n - 8, true);
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void place(byte[] all) {
        int i = 0;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < n; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? n - 1 - vert : vert;
                    if (!fn[y][x] && i < all.length * 8) {
                        mod[y][x] = bit(all[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
        }
    }

    private void mask(int m) {
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!fn[y][x] && inv) mod[y][x] ^= true;
            }
    }

    private int penalty() {
        int p = 0, dark = 0;
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) if (mod[y][x]) dark++;
        for (int a = 0; a < n; a++) {
            int rr = 1, rc = 1;
            for (int b = 1; b < n; b++) {
                rr = mod[a][b] == mod[a][b - 1] ? rr + 1 : 1;
                rc = mod[b][a] == mod[b - 1][a] ? rc + 1 : 1;
                if (rr == 5) p += 3; else if (rr > 5) p++;
                if (rc == 5) p += 3; else if (rc > 5) p++;
            }
        }
        for (int y = 0; y < n - 1; y++)
            for (int x = 0; x < n - 1; x++)
                if (mod[y][x] == mod[y][x + 1] && mod[y][x] == mod[y + 1][x] && mod[y][x] == mod[y + 1][x + 1]) p += 3;
        int total = n * n;
        p += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10;
        return p;
    }

    /** Draws a QR in black on white (scanners need that contrast whatever the theme). */
    static View view(Context c, String text) {
        final boolean[][] m = encode(text);
        return new View(c) {
            private final Paint paint = new Paint();

            @Override
            protected void onDraw(Canvas cv) {
                if (m == null) return;
                int quiet = 4, cells = m.length + quiet * 2;
                float s = Math.min(getWidth(), getHeight()) / (float) cells;
                paint.setColor(Color.WHITE);
                cv.drawRect(0, 0, cells * s, cells * s, paint);
                paint.setColor(Color.BLACK);
                for (int y = 0; y < m.length; y++)
                    for (int x = 0; x < m.length; x++)
                        if (m[y][x]) cv.drawRect((x + quiet) * s, (y + quiet) * s, (x + quiet + 1) * s, (y + quiet + 1) * s, paint);
            }
        };
    }
}
