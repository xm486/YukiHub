package E7;

import android.text.TextUtils;
import java.util.Locale;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.misc.IjkMediaFormat;

/* JADX INFO: loaded from: classes.dex */
public final class c extends d {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    public final /* synthetic */ int f1684a;

    public /* synthetic */ c(int i8) {
        this.f1684a = i8;
    }

    @Override // E7.d
    public final String a(IjkMediaFormat ijkMediaFormat) {
        String str;
        switch (this.f1684a) {
            case 0:
                int integer = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_BITRATE);
                if (integer <= 0) {
                    return null;
                }
                if (integer < 1000) {
                    Locale locale = Locale.US;
                    return integer + " bit/s";
                }
                Locale locale2 = Locale.US;
                return (integer / 1000) + " kb/s";
            case 1:
                switch (ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID)) {
                    case IjkMediaMeta.FF_PROFILE_H264_CAVLC_444 /* 44 */:
                        str = "CAVLC 4:4:4";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_BASELINE /* 66 */:
                        str = "Baseline";
                        break;
                    case 77:
                        str = "Main";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_EXTENDED /* 88 */:
                        str = "Extended";
                        break;
                    case 100:
                        str = "High";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_HIGH_10 /* 110 */:
                        str = "High 10";
                        break;
                    case 122:
                        str = "High 4:2:2";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_HIGH_444 /* 144 */:
                        str = "High 4:4:4";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_HIGH_444_PREDICTIVE /* 244 */:
                        str = "High 4:4:4 Predictive";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_CONSTRAINED_BASELINE /* 578 */:
                        str = "Constrained Baseline";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_HIGH_10_INTRA /* 2158 */:
                        str = "High 10 Intra";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_HIGH_422_INTRA /* 2170 */:
                        str = "High 4:2:2 Intra";
                        break;
                    case IjkMediaMeta.FF_PROFILE_H264_HIGH_444_INTRA /* 2292 */:
                        str = "High 4:4:4 Intra";
                        break;
                    default:
                        return null;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(str);
                String string = ijkMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME);
                if (!TextUtils.isEmpty(string) && string.equalsIgnoreCase(IjkMediaFormat.CODEC_NAME_H264)) {
                    int integer2 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_CODEC_LEVEL);
                    if (integer2 < 10) {
                        return sb.toString();
                    }
                    sb.append(" Profile Level ");
                    sb.append((integer2 / 10) % 10);
                    int i8 = integer2 % 10;
                    if (i8 != 0) {
                        sb.append(".");
                        sb.append(i8);
                    }
                }
                return sb.toString();
            case 2:
                return ijkMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_PIXEL_FORMAT);
            case 3:
                int integer3 = ijkMediaFormat.getInteger("width");
                int integer4 = ijkMediaFormat.getInteger("height");
                int integer5 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_SAR_NUM);
                int integer6 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_SAR_DEN);
                if (integer3 <= 0 || integer4 <= 0) {
                    return null;
                }
                if (integer5 <= 0 || integer6 <= 0) {
                    Locale locale3 = Locale.US;
                    return integer3 + " x " + integer4;
                }
                Locale locale4 = Locale.US;
                return integer3 + " x " + integer4 + " [SAR " + integer5 + ":" + integer6 + "]";
            case 4:
                int integer7 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_FPS_NUM);
                int integer8 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_FPS_DEN);
                if (integer7 <= 0 || integer8 <= 0) {
                    return null;
                }
                return String.valueOf(integer7 / integer8);
            case 5:
                int integer9 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_SAMPLE_RATE);
                if (integer9 <= 0) {
                    return null;
                }
                Locale locale5 = Locale.US;
                return integer9 + " Hz";
            default:
                int integer10 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_CHANNEL_LAYOUT);
                if (integer10 <= 0) {
                    return null;
                }
                long j = integer10;
                return j == 4 ? "mono" : j == 3 ? "stereo" : String.format(Locale.US, "%x", Integer.valueOf(integer10));
        }
    }
}
