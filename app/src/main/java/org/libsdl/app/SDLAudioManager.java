package org.libsdl.app;

import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.util.Log;

/* JADX INFO: loaded from: classes.dex */
public class SDLAudioManager {
    protected static final String TAG = "SDLAudio";
    protected static AudioRecord mAudioRecord;
    protected static AudioTrack mAudioTrack;

    public static void audioClose() {
        AudioTrack audioTrack = mAudioTrack;
        if (audioTrack != null) {
            audioTrack.stop();
            mAudioTrack.release();
            mAudioTrack = null;
        }
    }

    public static int[] audioOpen(int i8, int i9, int i10, int i11) {
        return open(false, i8, i9, i10, i11);
    }

    public static void audioSetThreadPriority(boolean z, int i8) {
        try {
            if (z) {
                Thread.currentThread().setName("SDLAudioC" + i8);
            } else {
                Thread.currentThread().setName("SDLAudioP" + i8);
            }
            Process.setThreadPriority(-16);
        } catch (Exception e8) {
            Log.v(TAG, "modify thread properties failed " + e8.toString());
        }
    }

    public static void audioWriteByteBuffer(byte[] bArr) {
        if (mAudioTrack == null) {
            Log.e(TAG, "Attempted to make audio call with uninitialized audio!");
            return;
        }
        int i8 = 0;
        while (i8 < bArr.length) {
            int iWrite = mAudioTrack.write(bArr, i8, bArr.length - i8);
            if (iWrite > 0) {
                i8 += iWrite;
            } else {
                if (iWrite != 0) {
                    Log.w(TAG, "SDL audio: error return from write(byte)");
                    return;
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException unused) {
                }
            }
        }
    }

    public static void audioWriteFloatBuffer(float[] fArr) {
        if (mAudioTrack == null) {
            Log.e(TAG, "Attempted to make audio call with uninitialized audio!");
            return;
        }
        int i8 = 0;
        while (i8 < fArr.length) {
            int iWrite = mAudioTrack.write(fArr, i8, fArr.length - i8, 0);
            if (iWrite > 0) {
                i8 += iWrite;
            } else {
                if (iWrite != 0) {
                    Log.w(TAG, "SDL audio: error return from write(float)");
                    return;
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException unused) {
                }
            }
        }
    }

    public static void audioWriteShortBuffer(short[] sArr) {
        if (mAudioTrack == null) {
            Log.e(TAG, "Attempted to make audio call with uninitialized audio!");
            return;
        }
        int i8 = 0;
        while (i8 < sArr.length) {
            int iWrite = mAudioTrack.write(sArr, i8, sArr.length - i8);
            if (iWrite > 0) {
                i8 += iWrite;
            } else {
                if (iWrite != 0) {
                    Log.w(TAG, "SDL audio: error return from write(short)");
                    return;
                }
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException unused) {
                }
            }
        }
    }

    public static void captureClose() {
        AudioRecord audioRecord = mAudioRecord;
        if (audioRecord != null) {
            audioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
    }

    public static int[] captureOpen(int i8, int i9, int i10, int i11) {
        return open(true, i8, i9, i10, i11);
    }

    public static int captureReadByteBuffer(byte[] bArr, boolean z) {
        return Build.VERSION.SDK_INT < 23 ? mAudioRecord.read(bArr, 0, bArr.length) : mAudioRecord.read(bArr, 0, bArr.length, !z ? 1 : 0);
    }

    public static int captureReadFloatBuffer(float[] fArr, boolean z) {
        return mAudioRecord.read(fArr, 0, fArr.length, !z ? 1 : 0);
    }

    public static int captureReadShortBuffer(short[] sArr, boolean z) {
        return Build.VERSION.SDK_INT < 23 ? mAudioRecord.read(sArr, 0, sArr.length) : mAudioRecord.read(sArr, 0, sArr.length, !z ? 1 : 0);
    }

    public static String getAudioFormatString(int i8) {
        return i8 != 2 ? i8 != 3 ? i8 != 4 ? Integer.toString(i8) : "float" : "8-bit" : "16-bit";
    }

    public static void initialize() {
        mAudioTrack = null;
        mAudioRecord = null;
    }

    public static native int nativeSetupJNI();

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Removed duplicated region for block: B:14:0x0061  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
    */
    public static int[] open(boolean z, int i8, int i9, int i10, int i11) {
        int i12 = i8;
        char c8;
        int i13;
        int i14;
        char c9;
        int i15;
        int i16 = i10;
        StringBuilder sb = new StringBuilder("Opening ");
        sb.append(z ? "capture" : "playback");
        sb.append(", requested ");
        sb.append(i11);
        sb.append(" frames of ");
        sb.append(i16);
        sb.append(" channel ");
        sb.append(getAudioFormatString(i9));
        sb.append(" audio at ");
        sb.append(i8);
        sb.append(" Hz");
        Log.v(TAG, sb.toString());
        int i17 = Build.VERSION.SDK_INT;
        if (i17 >= 22) {
            i12 = i8;
        } else if (i8 < 8000) {
            i12 = 8000;
        } else if (i8 > 48000) {
            i12 = 48000;
        }
        int i18 = i9;
        if (i18 == 4) {
            if (i17 < (z ? 23 : 21)) {
                i18 = 2;
            }
        }
        if (i18 == 2) {
            c8 = 3;
            i13 = 2;
        } else if (i18 != 3) {
            c8 = 3;
            if (i18 != 4) {
                Log.v(TAG, "Requested format " + i18 + ", getting ENCODING_PCM_16BIT");
                i13 = 2;
                i18 = 2;
            } else {
                i13 = 4;
            }
        } else {
            c8 = 3;
            i13 = 1;
        }
        if (!z) {
            i14 = i13;
            c9 = 2;
            switch (i16) {
                case 1:
                    i15 = 4;
                    break;
                case 2:
                    i15 = 12;
                    break;
                case 3:
                    i15 = 28;
                    break;
                case 4:
                    i15 = 111;
                    break;
                case 5:
                    i15 = 220;
                    break;
                case 6:
                    i15 = 112;
                    break;
                case 7:
                    i15 = 1276;
                    break;
                case 8:
                    if (i17 < 23) {
                        Log.v(TAG, "Requested " + i16 + " channels, getting 5.1 surround");
                        i16 = 6;
                        i15 = 112;
                    } else {
                        i15 = 6396;
                    }
                    break;
                default:
                    Log.v(TAG, "Requested " + i16 + " channels, getting stereo");
                    i16 = 2;
                    i15 = 12;
                    break;
            }
        } else {
            i14 = i13;
            if (i16 != 1) {
                c9 = 2;
                if (i16 != 2) {
                    Log.v(TAG, "Requested " + i16 + " channels, getting stereo");
                    i16 = 2;
                }
                i15 = 12;
            } else {
                c9 = 2;
                i15 = 16;
            }
        }
        int i19 = i16 * i14;
        int iMax = Math.max(i11, (((z ? AudioRecord.getMinBufferSize(i12, i15, i18) : AudioTrack.getMinBufferSize(i12, i15, i18)) + i19) - 1) / i19);
        int[] iArr = new int[4];
        if (z) {
            if (mAudioRecord == null) {
                AudioRecord audioRecord = new AudioRecord(0, i12, i15, i18, iMax * i19);
                mAudioRecord = audioRecord;
                if (audioRecord.getState() != 1) {
                    Log.e(TAG, "Failed during initialization of AudioRecord");
                    mAudioRecord.release();
                    mAudioRecord = null;
                    return null;
                }
                mAudioRecord.startRecording();
            }
            iArr[0] = mAudioRecord.getSampleRate();
            iArr[1] = mAudioRecord.getAudioFormat();
            iArr[c9] = mAudioRecord.getChannelCount();
        } else {
            int i20 = i15;
            int i21 = i18;
            if (mAudioTrack == null) {
                AudioTrack audioTrack = new AudioTrack(3, i12, i20, i21, iMax * i19, 1);
                mAudioTrack = audioTrack;
                if (audioTrack.getState() != 1) {
                    Log.e(TAG, "Failed during initialization of Audio Track");
                    mAudioTrack.release();
                    mAudioTrack = null;
                    return null;
                }
                mAudioTrack.play();
            }
            iArr[0] = mAudioTrack.getSampleRate();
            iArr[1] = mAudioTrack.getAudioFormat();
            iArr[c9] = mAudioTrack.getChannelCount();
        }
        iArr[c8] = iMax;
        StringBuilder sb2 = new StringBuilder("Opening ");
        sb2.append(z ? "capture" : "playback");
        sb2.append(", got ");
        sb2.append(iArr[c8]);
        sb2.append(" frames of ");
        sb2.append(iArr[c9]);
        sb2.append(" channel ");
        sb2.append(getAudioFormatString(iArr[1]));
        sb2.append(" audio at ");
        sb2.append(iArr[0]);
        sb2.append(" Hz");
        Log.v(TAG, sb2.toString());
        return iArr;
    }
}
