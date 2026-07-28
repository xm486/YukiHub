package com.yuki.yukihub.translate;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PP-OCRv6_small 离线 OCR 引擎。
 *
 * det/rec 模型来自 MoeTranslate 项目的 PP-OCRv6 适配，模型从 assets 加载，
 * 不依赖 Google Play services。当前实现先完成模型初始化、识别和 CTC 解码；
 * 检测框后处理使用轴对齐候选框，保证 Java 版本可控且便于后续替换为完整 DBNet/JTS 后处理。
 */
public final class PPOcrV6Engine {
    private static final String TAG = "PPOcrV6Engine";
    private static final String DET_ASSET = "ppocrv6/det_v6_small.onnx";
    private static final String REC_ASSET = "ppocrv6/rec_v6_small.onnx";
    private static final String DICT_ASSET = "ppocrv6/ppocrv6_dict.txt";

    private static final int DET_LIMIT_SIDE_LEN = 960;
    private static final float[] DET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] DET_STD = {0.229f, 0.224f, 0.225f};
    private static final float DET_THRESH = 0.20f;
    private static final float DET_BOX_THRESH = 0.45f;
    private static final int REC_IMG_HEIGHT = 48;
    private static final float TEXT_SCORE_THRESH = 0.50f;

    private static final Object LOCK = new Object();
    private static OrtEnvironment environment;
    private static OrtSession detSession;
    private static OrtSession recSession;
    private static List<String> dictionary = Collections.emptyList();
    private static volatile boolean initialized;

    private PPOcrV6Engine() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void initialize(Context context) throws Exception {
        synchronized (LOCK) {
            if (initialized) return;
            Context app = context.getApplicationContext();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setIntraOpNumThreads(4);
            try {
                environment = OrtEnvironment.getEnvironment();
                detSession = environment.createSession(readAsset(app, DET_ASSET), options);
                recSession = environment.createSession(readAsset(app, REC_ASSET), options);
                dictionary = loadDictionary(app);
                initialized = true;
                Log.i(TAG, "PP-OCRv6 initialized, dictionary=" + dictionary.size());
            } catch (Exception e) {
                release();
                throw e;
            } finally {
                options.close();
            }
        }
    }

    public static void release() {
        synchronized (LOCK) {
            try {
                if (detSession != null) detSession.close();
            } catch (Throwable ignored) {
            }
            try {
                if (recSession != null) recSession.close();
            } catch (Throwable ignored) {
            }
            detSession = null;
            recSession = null;
            environment = null;
            dictionary = Collections.emptyList();
            initialized = false;
        }
    }

    private static byte[] readAsset(Context context, String path) throws Exception {
        java.io.InputStream in = context.getAssets().open(path);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static List<String> loadDictionary(Context context) throws Exception {
        List<String> result = new ArrayList<>();
        result.add("blank");
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(DICT_ASSET), "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) result.add(line);
        } finally {
            reader.close();
        }
        return result;
    }

    public static String runOCR(Bitmap bitmap) throws Exception {
        if (!initialized) throw new IllegalStateException("PP-OCRv6 未初始化");
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("Bitmap 无效");

        List<TextBox> boxes = detect(bitmap);
        if (boxes.isEmpty()) return recognize(bitmap);

        StringBuilder result = new StringBuilder();
        for (TextBox box : boxes) {
            Bitmap crop = crop(bitmap, box);
            if (crop == null) continue;
            try {
                String text = recognize(crop);
                if (text != null && !text.trim().isEmpty()) {
                    if (result.length() > 0) result.append('\n');
                    result.append(text.trim());
                }
            } finally {
                if (!crop.isRecycled()) crop.recycle();
            }
        }
        return result.toString().trim();
    }

    private static final class TextBox {
        final int left, top, right, bottom;
        final float score;

        TextBox(int left, int top, int right, int bottom, float score) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.score = score;
        }
    }

    private static List<TextBox> detect(Bitmap bitmap) throws Exception {
        int sourceWidth = bitmap.getWidth();
        int sourceHeight = bitmap.getHeight();
        float ratio = 1f;
        int longest = Math.max(sourceWidth, sourceHeight);
        if (longest > DET_LIMIT_SIDE_LEN) ratio = DET_LIMIT_SIDE_LEN / (float) longest;
        int width = Math.max(32, ((Math.round(sourceWidth * ratio) + 16) / 32) * 32);
        int height = Math.max(32, ((Math.round(sourceHeight * ratio) + 16) / 32) * 32);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        try {
            int[] pixels = new int[width * height];
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);
            int plane = width * height;
            float[] input = new float[plane * 3];
            for (int i = 0; i < plane; i++) {
                int p = pixels[i];
                float b = (p & 0xff) / 255f;
                float g = ((p >> 8) & 0xff) / 255f;
                float r = ((p >> 16) & 0xff) / 255f;
                input[i] = (b - DET_MEAN[0]) / DET_STD[0];
                input[plane + i] = (g - DET_MEAN[1]) / DET_STD[1];
                input[plane * 2 + i] = (r - DET_MEAN[2]) / DET_STD[2];
            }
            OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input),
                    new long[]{1, 3, height, width});
            try {
                OrtSession.Result result = detSession.run(Collections.singletonMap("x", tensor));
                try {
                    OnnxTensor output = firstTensor(result);
                    long[] shape = output.getInfo().getShape();
                    int mapHeight = (int) shape[shape.length - 2];
                    int mapWidth = (int) shape[shape.length - 1];
                    float[] probability = new float[mapHeight * mapWidth];
                    output.getFloatBuffer().rewind();
                    output.getFloatBuffer().get(probability);
                    return connectedBoxes(probability, mapWidth, mapHeight,
                            sourceWidth / (float) mapWidth, sourceHeight / (float) mapHeight);
                } finally {
                    result.close();
                }
            } finally {
                tensor.close();
            }
        } finally {
            if (scaled != bitmap && !scaled.isRecycled()) scaled.recycle();
        }
    }

    private static List<TextBox> connectedBoxes(float[] probability, int width, int height,
                                                 float scaleX, float scaleY) {
        boolean[] visited = new boolean[width * height];
        List<TextBox> boxes = new ArrayList<>();
        int[] queue = new int[width * height];
        for (int start = 0; start < probability.length; start++) {
            if (visited[start] || score(probability[start]) <= DET_THRESH) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            int minX = start % width, maxX = minX;
            int minY = start / width, maxY = minY;
            float sum = 0f;
            int count = 0;
            while (head < tail) {
                int current = queue[head++];
                int x = current % width;
                int y = current / width;
                float value = score(probability[current]);
                sum += value;
                count++;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                        int next = ny * width + nx;
                        if (!visited[next] && score(probability[next]) > DET_THRESH) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            if (count < 3 || sum / count < DET_BOX_THRESH) continue;
            int left = Math.max(0, Math.round(minX * scaleX) - 2);
            int top = Math.max(0, Math.round(minY * scaleY) - 2);
            int right = Math.min((int) (scaleX * width), Math.round((maxX + 1) * scaleX) + 2);
            int bottom = Math.min((int) (scaleY * height), Math.round((maxY + 1) * scaleY) + 2);
            if (right > left && bottom > top) boxes.add(new TextBox(left, top, right, bottom, sum / count));
        }
        Collections.sort(boxes, (a, b) -> a.top == b.top ? a.left - b.left : a.top - b.top);
        return boxes;
    }

    private static float score(float value) {
        return value >= 0f && value <= 1f ? value : (float) (1.0 / (1.0 + Math.exp(-value)));
    }

    private static Bitmap crop(Bitmap source, TextBox box) {
        int left = Math.max(0, Math.min(box.left, source.getWidth() - 1));
        int top = Math.max(0, Math.min(box.top, source.getHeight() - 1));
        int right = Math.max(left + 1, Math.min(box.right, source.getWidth()));
        int bottom = Math.max(top + 1, Math.min(box.bottom, source.getHeight()));
        try {
            return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
        } catch (Throwable error) {
            Log.w(TAG, "文本区域裁剪失败", error);
            return null;
        }
    }

    private static String recognize(Bitmap bitmap) throws Exception {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int imageWidth = Math.max(1, Math.min(320,
                Math.round((float) REC_IMG_HEIGHT * width / Math.max(1, height))));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, imageWidth, REC_IMG_HEIGHT, true);
        try {
            int[] pixels = new int[imageWidth * REC_IMG_HEIGHT];
            scaled.getPixels(pixels, 0, imageWidth, 0, 0, imageWidth, REC_IMG_HEIGHT);
            int plane = imageWidth * REC_IMG_HEIGHT;
            float[] input = new float[3 * plane];
            for (int i = 0; i < plane; i++) {
                int pixel = pixels[i];
                input[i] = (((pixel) & 0xff) / 255f - 0.5f) / 0.5f;
                input[plane + i] = (((pixel >> 8) & 0xff) / 255f - 0.5f) / 0.5f;
                input[2 * plane + i] = (((pixel >> 16) & 0xff) / 255f - 0.5f) / 0.5f;
            }
            long[] shape = {1, 3, REC_IMG_HEIGHT, imageWidth};
            OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape);
            try {
                OrtSession.Result result = recSession.run(Collections.singletonMap("x", tensor));
                try {
                    OnnxTensor output = firstTensor(result);
                    long[] outputShape = output.getInfo().getShape();
                    if (outputShape.length < 3) return "";
                    int sequenceLength = (int) outputShape[outputShape.length - 2];
                    int classCount = (int) outputShape[outputShape.length - 1];
                    float[] data = new float[sequenceLength * classCount];
                    output.getFloatBuffer().rewind();
                    output.getFloatBuffer().get(data);
                    return decode(data, sequenceLength, classCount);
                } finally {
                    result.close();
                }
            } finally {
                tensor.close();
            }
        } finally {
            if (scaled != bitmap && !scaled.isRecycled()) scaled.recycle();
        }
    }

    private static OnnxTensor firstTensor(OrtSession.Result result) {
        for (int i = 0; i < result.size(); i++) {
            Object value = result.get(i);
            if (value instanceof OnnxTensor) return (OnnxTensor) value;
        }
        throw new IllegalStateException("ONNX 没有 Tensor 输出");
    }

    private static String decode(float[] data, int sequenceLength, int classCount) {
        StringBuilder text = new StringBuilder();
        int previous = -1;
        float score = 0f;
        int count = 0;
        for (int time = 0; time < sequenceLength; time++) {
            int offset = time * classCount;
            int best = 0;
            float bestValue = data[offset];
            for (int c = 1; c < classCount; c++) {
                if (data[offset + c] > bestValue) {
                    best = c;
                    bestValue = data[offset + c];
                }
            }
            if (best > 0 && best != previous && best < dictionary.size()
                    && bestValue >= TEXT_SCORE_THRESH) {
                text.append(dictionary.get(best));
                score += bestValue;
                count++;
            }
            previous = best;
        }
        return count == 0 ? "" : text.toString().trim();
    }
}
