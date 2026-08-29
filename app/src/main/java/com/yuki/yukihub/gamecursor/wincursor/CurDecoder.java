package com.yuki.yukihub.gamecursor.wincursor;

import android.graphics.Bitmap;

/**
 * Windows .cur（光标）解码器。
 *
 * CUR 与 ICO 是同一种容器，区别只在 header 的 type 字段（1=ICO, 2=CUR），
 * 以及目录项里那两个字段的含义：ICO 存颜色平面/位深，CUR 存**热点坐标**。
 *
 * 结构：
 *   ICONDIR       6B   reserved(2) type(2) count(2)
 *   ICONDIRENTRY 16B × count
 *                      width(1) height(1) colorCount(1) reserved(1)
 *                      hotspotX(2) hotspotY(2)   ← CUR 特有
 *                      bytesInRes(4) imageOffset(4)
 *   图像数据            BITMAPINFOHEADER + 调色板 + XOR 位图 + AND 掩码
 *                      或者整个是一个 PNG（Vista 之后允许）
 *
 * 支持 32bpp（带 alpha）、24bpp、8/4/1bpp 调色板，以及 PNG 内嵌。
 * DIB 是**自下而上**存储的（除非 height 为负），解码时要翻转行序 —— 这是最常见的坑。
 */
public final class CurDecoder {
    private CurDecoder() { }

    /** 单帧解码。data 是完整的 .cur 文件内容（或 ani 里的一个 icon chunk）。 */
    public static CursorFrame decode(byte[] data, int maxSize) {
        return decode(data, 0, data == null ? 0 : data.length, maxSize, 0);
    }

    /**
     * @param off        起始偏移（ani 里 icon chunk 的数据区起点）
     * @param len        长度
     * @param maxSize    输出位图最大边长，超出等比缩小（光标实际只显示几十 dp，
     *                   128 就绰绰有余，限制它是为了防 256×256×60 帧那种极端包吃内存）
     * @param durationMs 本帧时长
     */
    public static CursorFrame decode(byte[] data, int off, int len, int maxSize, int durationMs) {
        try {
            if (data == null || len < 22) return null;
            int type = u16(data, off + 2);
            int count = u16(data, off + 4);
            if (count <= 0) return null;
            // type 1=ICO 2=CUR，都接受；有些包里 ani 内嵌的是 ICO 型
            if (type != 1 && type != 2) return null;

            // 选面积最大的那一项（光标包通常只有一项，多项时取最清晰的）
            int bestEntry = -1, bestArea = -1;
            for (int i = 0; i < count; i++) {
                int e = off + 6 + i * 16;
                if (e + 16 > off + len) break;
                int w = data[e] & 0xFF;
                int h = data[e + 1] & 0xFF;
                if (w == 0) w = 256;   // 0 表示 256（单字节存不下）
                if (h == 0) h = 256;
                int area = w * h;
                if (area > bestArea) {
                    bestArea = area;
                    bestEntry = e;
                }
            }
            if (bestEntry < 0) return null;

            int hotX = u16(data, bestEntry + 4);
            int hotY = u16(data, bestEntry + 6);
            int imgLen = i32(data, bestEntry + 8);
            int imgOff = off + i32(data, bestEntry + 12);
            if (imgOff < off || imgLen <= 0 || imgOff + imgLen > off + len) {
                // 有些文件的 bytesInRes 不可靠，退而用到末尾
                imgLen = off + len - imgOff;
                if (imgOff < off || imgLen <= 0) return null;
            }

            Bitmap bmp = decodeImage(data, imgOff, imgLen);
            if (bmp == null) return null;

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            // 热点转相对坐标：必须在缩放**之前**用原始尺寸算
            float rx = bw > 0 ? clamp01(hotX / (float) bw) : 0f;
            float ry = bh > 0 ? clamp01(hotY / (float) bh) : 0f;

            if (maxSize > 0 && Math.max(bw, bh) > maxSize) {
                float k = maxSize / (float) Math.max(bw, bh);
                int nw = Math.max(1, Math.round(bw * k));
                int nh = Math.max(1, Math.round(bh * k));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            return new CursorFrame(bmp, rx, ry, durationMs);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 图像数据可能是 PNG，也可能是 BMP(DIB)。按魔数分流。 */
    private static Bitmap decodeImage(byte[] data, int off, int len) {
        // PNG 签名
        if (len > 8 && (data[off] & 0xFF) == 0x89 && data[off + 1] == 'P'
                && data[off + 2] == 'N' && data[off + 3] == 'G') {
            return android.graphics.BitmapFactory.decodeByteArray(data, off, len);
        }
        return decodeDib(data, off, len);
    }

    /**
     * 解 BITMAPINFOHEADER 格式的 DIB。
     *
     * 光标 DIB 的高度字段是**两倍实际高度**：上半是 XOR 颜色位图，
     * 下半是 AND 单色掩码。32bpp 时 alpha 通道已经在颜色数据里，掩码可忽略；
     * 低位深必须靠掩码决定透明区域。
     */
    private static Bitmap decodeDib(byte[] d, int off, int len) {
        if (len < 40) return null;
        int headerSize = i32(d, off);
        if (headerSize < 40) return null;
        int width = i32(d, off + 4);
        int heightField = i32(d, off + 8);
        int bitCount = u16(d, off + 14);
        int compression = i32(d, off + 16);
        if (compression != 0) return null;   // BI_RGB 之外不处理
        if (width <= 0 || width > 4096) return null;

        boolean topDown = heightField < 0;
        int totalH = Math.abs(heightField);
        // 光标的 height 含掩码那一半；ICO 里也是这个约定
        int height = totalH / 2;
        if (height <= 0 || height > 4096) return null;

        int paletteEntries = 0;
        if (bitCount <= 8) {
            paletteEntries = i32(d, off + 32);           // biClrUsed
            if (paletteEntries == 0) paletteEntries = 1 << bitCount;
        }
        int paletteOff = off + headerSize;
        int pixelOff = paletteOff + paletteEntries * 4;

        int rowBytes = ((width * bitCount + 31) / 32) * 4;   // 每行 4 字节对齐
        int maskRowBytes = ((width + 31) / 32) * 4;
        int maskOff = pixelOff + rowBytes * height;

        if (pixelOff + rowBytes * height > off + len) return null;
        boolean hasMask = maskOff + maskRowBytes * height <= off + len;

        int[] px = new int[width * height];
        for (int y = 0; y < height; y++) {
            // DIB 自下而上：源第 0 行是图像最后一行
            int srcY = topDown ? y : (height - 1 - y);
            int rowOff = pixelOff + srcY * rowBytes;
            int maskRowOff = maskOff + srcY * maskRowBytes;
            for (int x = 0; x < width; x++) {
                int argb = readPixel(d, rowOff, paletteOff, x, bitCount);
                if (bitCount != 32) {
                    // 低位深没有 alpha：透明度完全由 AND 掩码决定（1=透明）
                    boolean transparent = false;
                    if (hasMask) {
                        int byteIdx = maskRowOff + (x >> 3);
                        if (byteIdx < off + len) {
                            int bit = (d[byteIdx] >> (7 - (x & 7))) & 1;
                            transparent = bit != 0;
                        }
                    }
                    argb = transparent ? 0 : (argb | 0xFF000000);
                }
                px[y * width + x] = argb;
            }
        }

        // 32bpp 但 alpha 全 0 的文件是存在的（工具写错），这时退回用掩码
        if (bitCount == 32 && hasMask && isFullyTransparent(px)) {
            for (int y = 0; y < height; y++) {
                int srcY = topDown ? y : (height - 1 - y);
                int maskRowOff = maskOff + srcY * maskRowBytes;
                for (int x = 0; x < width; x++) {
                    int byteIdx = maskRowOff + (x >> 3);
                    boolean transparent = false;
                    if (byteIdx < off + len) {
                        transparent = ((d[byteIdx] >> (7 - (x & 7))) & 1) != 0;
                    }
                    int i = y * width + x;
                    px[i] = transparent ? 0 : (px[i] | 0xFF000000);
                }
            }
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888);
    }

    private static boolean isFullyTransparent(int[] px) {
        for (int p : px) {
            if ((p >>> 24) != 0) return false;
        }
        return true;
    }

    /** 按位深读一个像素，返回 ARGB。DIB 的颜色序是 BGRA。 */
    private static int readPixel(byte[] d, int rowOff, int paletteOff, int x, int bitCount) {
        switch (bitCount) {
            case 32: {
                int i = rowOff + x * 4;
                int b = d[i] & 0xFF, g = d[i + 1] & 0xFF, r = d[i + 2] & 0xFF, a = d[i + 3] & 0xFF;
                return (a << 24) | (r << 16) | (g << 8) | b;
            }
            case 24: {
                int i = rowOff + x * 3;
                int b = d[i] & 0xFF, g = d[i + 1] & 0xFF, r = d[i + 2] & 0xFF;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            case 8:
                return palette(d, paletteOff, d[rowOff + x] & 0xFF);
            case 4: {
                int bv = d[rowOff + (x >> 1)] & 0xFF;
                int idx = (x & 1) == 0 ? (bv >> 4) : (bv & 0x0F);
                return palette(d, paletteOff, idx);
            }
            case 1: {
                int bv = d[rowOff + (x >> 3)] & 0xFF;
                int idx = (bv >> (7 - (x & 7))) & 1;
                return palette(d, paletteOff, idx);
            }
            default:
                return 0;
        }
    }

    private static int palette(byte[] d, int paletteOff, int index) {
        int i = paletteOff + index * 4;
        if (i + 3 >= d.length) return 0xFF000000;
        int b = d[i] & 0xFF, g = d[i + 1] & 0xFF, r = d[i + 2] & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int u16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    static int i32(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8)
                | ((d[i + 2] & 0xFF) << 16) | ((d[i + 3] & 0xFF) << 24);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}