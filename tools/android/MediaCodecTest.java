// DAB+ AAC decode test for Android MediaCodec, run via app_process (no APK needed):
//   adb push mctest.dex /data/local/tmp/
//   adb shell CLASSPATH=/data/local/tmp/mctest.dex app_process /data/local/tmp MediaCodecTest \
//       <in.aus> <asc-hex> <rate> <channels> <out.pcm> [codec-name]
// in.aus: AUs as 4-byte big-endian length + bytes (tools/export_aus.py). Output: raw PCM s16le.
// Without codec-name it lists the AAC decoders and uses the default one for audio/mp4a-latm.
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MediaCodecTest {
    static final String MIME = "audio/mp4a-latm";

    public static void main(String[] args) throws Exception {
        String inPath = args[0];
        byte[] asc = hex(args[1]);
        int rate = Integer.parseInt(args[2]);
        int channels = Integer.parseInt(args[3]);
        String outPath = args[4];

        System.out.println("android.os.Build.VERSION.SDK_INT=" + android.os.Build.VERSION.SDK_INT);
        for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (ci.isEncoder()) continue;
            for (String t : ci.getSupportedTypes()) {
                if (t.equalsIgnoreCase(MIME)) System.out.println("decoder: " + ci.getName());
            }
        }

        List<byte[]> aus = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new FileInputStream(inPath))) {
            while (in.available() > 0) {
                byte[] au = new byte[in.readInt()];
                in.readFully(au);
                aus.add(au);
            }
        }

        MediaFormat fmt = MediaFormat.createAudioFormat(MIME, rate, channels);
        fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
        fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0);
        MediaCodec codec = args.length > 5 ? MediaCodec.createByCodecName(args[5])
                                           : MediaCodec.createDecoderByType(MIME);
        System.out.println("using: " + codec.getName() + "  input format: " + fmt);
        codec.configure(fmt, null, null, 0);
        codec.start();

        long frameUs = 960L * 1000000L / rate;  // informational timestamps only
        int fed = 0, outBufs = 0, errors = 0;
        long outBytes = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eosSent = false, eosSeen = false;
        try (FileOutputStream out = new FileOutputStream(outPath)) {
            while (!eosSeen) {
                if (!eosSent) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        b.clear();
                        if (fed < aus.size()) {
                            byte[] au = aus.get(fed);
                            b.put(au);
                            codec.queueInputBuffer(ii, 0, au.length, fed * frameUs, 0);
                            fed++;
                        } else {
                            codec.queueInputBuffer(ii, 0, 0, fed * frameUs,
                                                   MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eosSent = true;
                        }
                    }
                }
                int oi;
                try {
                    oi = codec.dequeueOutputBuffer(info, 10000);
                } catch (IllegalStateException e) {
                    errors++;
                    System.out.println("decode error: " + e);
                    break;
                }
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    System.out.println("output format: " + codec.getOutputFormat());
                } else if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    byte[] pcm = new byte[info.size];
                    ob.position(info.offset);
                    ob.get(pcm);
                    out.write(pcm);
                    outBytes += info.size;
                    outBufs++;
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eosSeen = true;
                }
            }
        }
        MediaFormat of = codec.getOutputFormat();
        System.out.println("RESULT fed=" + fed + " outBufs=" + outBufs + " outBytes=" + outBytes
                + " errors=" + errors
                + " outRate=" + of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                + " outChannels=" + of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        codec.stop();
        codec.release();
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
