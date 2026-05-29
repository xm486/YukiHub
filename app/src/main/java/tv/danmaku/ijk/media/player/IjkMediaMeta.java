package tv.danmaku.ijk.media.player;

import android.os.Bundle;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public class IjkMediaMeta {
    public static final long AV_CH_BACK_CENTER = 256;
    public static final long AV_CH_BACK_LEFT = 16;
    public static final long AV_CH_BACK_RIGHT = 32;
    public static final long AV_CH_FRONT_CENTER = 4;
    public static final long AV_CH_FRONT_LEFT = 1;
    public static final long AV_CH_FRONT_LEFT_OF_CENTER = 64;
    public static final long AV_CH_FRONT_RIGHT = 2;
    public static final long AV_CH_FRONT_RIGHT_OF_CENTER = 128;
    public static final long AV_CH_LAYOUT_2POINT1 = 11;
    public static final long AV_CH_LAYOUT_2_1 = 259;
    public static final long AV_CH_LAYOUT_2_2 = 1539;
    public static final long AV_CH_LAYOUT_3POINT1 = 15;
    public static final long AV_CH_LAYOUT_4POINT0 = 263;
    public static final long AV_CH_LAYOUT_4POINT1 = 271;
    public static final long AV_CH_LAYOUT_5POINT0 = 1543;
    public static final long AV_CH_LAYOUT_5POINT0_BACK = 55;
    public static final long AV_CH_LAYOUT_5POINT1 = 1551;
    public static final long AV_CH_LAYOUT_5POINT1_BACK = 63;
    public static final long AV_CH_LAYOUT_6POINT0 = 1799;
    public static final long AV_CH_LAYOUT_6POINT0_FRONT = 1731;
    public static final long AV_CH_LAYOUT_6POINT1 = 1807;
    public static final long AV_CH_LAYOUT_6POINT1_BACK = 319;
    public static final long AV_CH_LAYOUT_6POINT1_FRONT = 1739;
    public static final long AV_CH_LAYOUT_7POINT0 = 1591;
    public static final long AV_CH_LAYOUT_7POINT0_FRONT = 1735;
    public static final long AV_CH_LAYOUT_7POINT1 = 1599;
    public static final long AV_CH_LAYOUT_7POINT1_WIDE = 1743;
    public static final long AV_CH_LAYOUT_7POINT1_WIDE_BACK = 255;
    public static final long AV_CH_LAYOUT_HEXAGONAL = 311;
    public static final long AV_CH_LAYOUT_MONO = 4;
    public static final long AV_CH_LAYOUT_OCTAGONAL = 1847;
    public static final long AV_CH_LAYOUT_QUAD = 51;
    public static final long AV_CH_LAYOUT_STEREO = 3;
    public static final long AV_CH_LAYOUT_STEREO_DOWNMIX = 1610612736;
    public static final long AV_CH_LAYOUT_SURROUND = 7;
    public static final long AV_CH_LOW_FREQUENCY = 8;
    public static final long AV_CH_LOW_FREQUENCY_2 = 34359738368L;
    public static final long AV_CH_SIDE_LEFT = 512;
    public static final long AV_CH_SIDE_RIGHT = 1024;
    public static final long AV_CH_STEREO_LEFT = 536870912;
    public static final long AV_CH_STEREO_RIGHT = 1073741824;
    public static final long AV_CH_SURROUND_DIRECT_LEFT = 8589934592L;
    public static final long AV_CH_SURROUND_DIRECT_RIGHT = 17179869184L;
    public static final long AV_CH_TOP_BACK_CENTER = 65536;
    public static final long AV_CH_TOP_BACK_LEFT = 32768;
    public static final long AV_CH_TOP_BACK_RIGHT = 131072;
    public static final long AV_CH_TOP_CENTER = 2048;
    public static final long AV_CH_TOP_FRONT_CENTER = 8192;
    public static final long AV_CH_TOP_FRONT_LEFT = 4096;
    public static final long AV_CH_TOP_FRONT_RIGHT = 16384;
    public static final long AV_CH_WIDE_LEFT = 2147483648L;
    public static final long AV_CH_WIDE_RIGHT = 4294967296L;
    public static final int FF_PROFILE_H264_BASELINE = 66;
    public static final int FF_PROFILE_H264_CAVLC_444 = 44;
    public static final int FF_PROFILE_H264_CONSTRAINED = 512;
    public static final int FF_PROFILE_H264_CONSTRAINED_BASELINE = 578;
    public static final int FF_PROFILE_H264_EXTENDED = 88;
    public static final int FF_PROFILE_H264_HIGH = 100;
    public static final int FF_PROFILE_H264_HIGH_10 = 110;
    public static final int FF_PROFILE_H264_HIGH_10_INTRA = 2158;
    public static final int FF_PROFILE_H264_HIGH_422 = 122;
    public static final int FF_PROFILE_H264_HIGH_422_INTRA = 2170;
    public static final int FF_PROFILE_H264_HIGH_444 = 144;
    public static final int FF_PROFILE_H264_HIGH_444_INTRA = 2292;
    public static final int FF_PROFILE_H264_HIGH_444_PREDICTIVE = 244;
    public static final int FF_PROFILE_H264_INTRA = 2048;
    public static final int FF_PROFILE_H264_MAIN = 77;
    public static final String IJKM_KEY_AUDIO_STREAM = "audio";
    public static final String IJKM_KEY_BITRATE = "bitrate";
    public static final String IJKM_KEY_CHANNEL_LAYOUT = "channel_layout";
    public static final String IJKM_KEY_CODEC_LEVEL = "codec_level";
    public static final String IJKM_KEY_CODEC_LONG_NAME = "codec_long_name";
    public static final String IJKM_KEY_CODEC_NAME = "codec_name";
    public static final String IJKM_KEY_CODEC_PIXEL_FORMAT = "codec_pixel_format";
    public static final String IJKM_KEY_CODEC_PROFILE = "codec_profile";
    public static final String IJKM_KEY_CODEC_PROFILE_ID = "codec_profile_id";
    public static final String IJKM_KEY_DURATION_US = "duration_us";
    public static final String IJKM_KEY_FORMAT = "format";
    public static final String IJKM_KEY_FPS_DEN = "fps_den";
    public static final String IJKM_KEY_FPS_NUM = "fps_num";
    public static final String IJKM_KEY_HEIGHT = "height";
    public static final String IJKM_KEY_LANGUAGE = "language";
    public static final String IJKM_KEY_SAMPLE_RATE = "sample_rate";
    public static final String IJKM_KEY_SAR_DEN = "sar_den";
    public static final String IJKM_KEY_SAR_NUM = "sar_num";
    public static final String IJKM_KEY_START_US = "start_us";
    public static final String IJKM_KEY_STREAMS = "streams";
    public static final String IJKM_KEY_TBR_DEN = "tbr_den";
    public static final String IJKM_KEY_TBR_NUM = "tbr_num";
    public static final String IJKM_KEY_TIMEDTEXT_STREAM = "timedtext";
    public static final String IJKM_KEY_TYPE = "type";
    public static final String IJKM_KEY_VIDEO_STREAM = "video";
    public static final String IJKM_KEY_WIDTH = "width";
    public static final String IJKM_VAL_TYPE__AUDIO = "audio";
    public static final String IJKM_VAL_TYPE__TIMEDTEXT = "timedtext";
    public static final String IJKM_VAL_TYPE__UNKNOWN = "unknown";
    public static final String IJKM_VAL_TYPE__VIDEO = "video";
    public IjkStreamMeta mAudioStream;
    public long mBitrate;
    public long mDurationUS;
    public String mFormat;
    public Bundle mMediaMeta;
    public long mStartUS;
    public final ArrayList<IjkStreamMeta> mStreams = new ArrayList<>();
    public IjkStreamMeta mVideoStream;

    public static class IjkStreamMeta {
        public long mBitrate;
        public long mChannelLayout;
        public String mCodecLongName;
        public String mCodecName;
        public String mCodecProfile;
        public int mFpsDen;
        public int mFpsNum;
        public int mHeight;
        public final int mIndex;
        public String mLanguage;
        public Bundle mMeta;
        public int mSampleRate;
        public int mSarDen;
        public int mSarNum;
        public int mTbrDen;
        public int mTbrNum;
        public String mType;
        public int mWidth;

        public IjkStreamMeta(int i8) {
            this.mIndex = i8;
        }

        public String getBitrateInline() {
            long j = this.mBitrate;
            if (j <= 0) {
                return "N/A";
            }
            if (j < 1000) {
                Locale locale = Locale.US;
                return j + " bit/s";
            }
            Locale locale2 = Locale.US;
            return (j / 1000) + " kb/s";
        }

        public String getChannelLayoutInline() {
            long j = this.mChannelLayout;
            return j <= 0 ? "N/A" : j == 4 ? "mono" : j == 3 ? "stereo" : String.format(Locale.US, "%x", Long.valueOf(j));
        }

        public String getCodecLongNameInline() {
            return !TextUtils.isEmpty(this.mCodecLongName) ? this.mCodecLongName : !TextUtils.isEmpty(this.mCodecName) ? this.mCodecName : "N/A";
        }

        public String getCodecShortNameInline() {
            return !TextUtils.isEmpty(this.mCodecName) ? this.mCodecName : "N/A";
        }

        public String getFpsInline() {
            int i8;
            int i9 = this.mFpsNum;
            return (i9 <= 0 || (i8 = this.mFpsDen) <= 0) ? "N/A" : String.valueOf(i9 / i8);
        }

        public int getInt(String str) {
            return getInt(str, 0);
        }

        public long getLong(String str) {
            return getLong(str, 0L);
        }

        public String getResolutionInline() {
            int i8 = this.mWidth;
            if (i8 <= 0 || this.mHeight <= 0) {
                return "N/A";
            }
            if (this.mSarNum <= 0 || this.mSarDen <= 0) {
                Locale locale = Locale.US;
                return i8 + " x " + this.mHeight;
            }
            Locale locale2 = Locale.US;
            return i8 + " x " + this.mHeight + " [SAR " + this.mSarNum + ":" + this.mSarDen + "]";
        }

        public String getSampleRateInline() {
            int i8 = this.mSampleRate;
            if (i8 <= 0) {
                return "N/A";
            }
            Locale locale = Locale.US;
            return i8 + " Hz";
        }

        public String getString(String str) {
            return this.mMeta.getString(str);
        }

        public int getInt(String str, int i8) {
            String string = getString(str);
            if (!TextUtils.isEmpty(string)) {
                try {
                    return Integer.parseInt(string);
                } catch (NumberFormatException unused) {
                }
            }
            return i8;
        }

        public long getLong(String str, long j) {
            String string = getString(str);
            if (!TextUtils.isEmpty(string)) {
                try {
                    return Long.parseLong(string);
                } catch (NumberFormatException unused) {
                }
            }
            return j;
        }
    }

    public static IjkMediaMeta parse(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        IjkMediaMeta ijkMediaMeta = new IjkMediaMeta();
        ijkMediaMeta.mMediaMeta = bundle;
        ijkMediaMeta.mFormat = ijkMediaMeta.getString(IJKM_KEY_FORMAT);
        ijkMediaMeta.mDurationUS = ijkMediaMeta.getLong(IJKM_KEY_DURATION_US);
        ijkMediaMeta.mStartUS = ijkMediaMeta.getLong(IJKM_KEY_START_US);
        ijkMediaMeta.mBitrate = ijkMediaMeta.getLong(IJKM_KEY_BITRATE);
        int i8 = -1;
        int i9 = ijkMediaMeta.getInt("video", -1);
        int i10 = ijkMediaMeta.getInt("audio", -1);
        ijkMediaMeta.getInt("timedtext", -1);
        ArrayList<Bundle> parcelableArrayList = ijkMediaMeta.getParcelableArrayList(IJKM_KEY_STREAMS);
        if (parcelableArrayList != null) {
            for (Bundle bundle2 : parcelableArrayList) {
                i8++;
                if (bundle2 != null) {
                    IjkStreamMeta ijkStreamMeta = new IjkStreamMeta(i8);
                    ijkStreamMeta.mMeta = bundle2;
                    ijkStreamMeta.mType = ijkStreamMeta.getString(IJKM_KEY_TYPE);
                    ijkStreamMeta.mLanguage = ijkStreamMeta.getString(IJKM_KEY_LANGUAGE);
                    if (!TextUtils.isEmpty(ijkStreamMeta.mType)) {
                        ijkStreamMeta.mCodecName = ijkStreamMeta.getString(IJKM_KEY_CODEC_NAME);
                        ijkStreamMeta.mCodecProfile = ijkStreamMeta.getString(IJKM_KEY_CODEC_PROFILE);
                        ijkStreamMeta.mCodecLongName = ijkStreamMeta.getString(IJKM_KEY_CODEC_LONG_NAME);
                        ijkStreamMeta.mBitrate = ijkStreamMeta.getInt(IJKM_KEY_BITRATE);
                        if (ijkStreamMeta.mType.equalsIgnoreCase("video")) {
                            ijkStreamMeta.mWidth = ijkStreamMeta.getInt("width");
                            ijkStreamMeta.mHeight = ijkStreamMeta.getInt("height");
                            ijkStreamMeta.mFpsNum = ijkStreamMeta.getInt(IJKM_KEY_FPS_NUM);
                            ijkStreamMeta.mFpsDen = ijkStreamMeta.getInt(IJKM_KEY_FPS_DEN);
                            ijkStreamMeta.mTbrNum = ijkStreamMeta.getInt(IJKM_KEY_TBR_NUM);
                            ijkStreamMeta.mTbrDen = ijkStreamMeta.getInt(IJKM_KEY_TBR_DEN);
                            ijkStreamMeta.mSarNum = ijkStreamMeta.getInt(IJKM_KEY_SAR_NUM);
                            ijkStreamMeta.mSarDen = ijkStreamMeta.getInt(IJKM_KEY_SAR_DEN);
                            if (i9 == i8) {
                                ijkMediaMeta.mVideoStream = ijkStreamMeta;
                            }
                        } else if (ijkStreamMeta.mType.equalsIgnoreCase("audio")) {
                            ijkStreamMeta.mSampleRate = ijkStreamMeta.getInt(IJKM_KEY_SAMPLE_RATE);
                            ijkStreamMeta.mChannelLayout = ijkStreamMeta.getLong(IJKM_KEY_CHANNEL_LAYOUT);
                            if (i10 == i8) {
                                ijkMediaMeta.mAudioStream = ijkStreamMeta;
                            }
                        }
                        ijkMediaMeta.mStreams.add(ijkStreamMeta);
                    }
                }
            }
        }
        return ijkMediaMeta;
    }

    public String getDurationInline() {
        long j = (this.mDurationUS + 5000) / 1000000;
        long j8 = j / 60;
        return String.format(Locale.US, "%02d:%02d:%02d", Long.valueOf(j8 / 60), Long.valueOf(j8 % 60), Long.valueOf(j % 60));
    }

    public int getInt(String str) {
        return getInt(str, 0);
    }

    public long getLong(String str) {
        return getLong(str, 0L);
    }

    public ArrayList<Bundle> getParcelableArrayList(String str) {
        return this.mMediaMeta.getParcelableArrayList(str);
    }

    public String getString(String str) {
        return this.mMediaMeta.getString(str);
    }

    public int getInt(String str, int i8) {
        String string = getString(str);
        if (!TextUtils.isEmpty(string)) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException unused) {
            }
        }
        return i8;
    }

    public long getLong(String str, long j) {
        String string = getString(str);
        if (!TextUtils.isEmpty(string)) {
            try {
                return Long.parseLong(string);
            } catch (NumberFormatException unused) {
            }
        }
        return j;
    }
}
