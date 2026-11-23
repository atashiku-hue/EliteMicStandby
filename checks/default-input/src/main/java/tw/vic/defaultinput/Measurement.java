package tw.vic.defaultinput;

import java.util.Arrays;
import java.util.Locale;

public final class Measurement {
    static final int RATE = 16000;
    static final int FRAME = RATE / 20; // 50 ms
    public static final int QUIET = 40;
    public static final int TOTAL = 120;
    private final int frameSize;
    Measurement() { this(RATE); }
    public Measurement(int rate) { frameSize = rate / 20; }
    private final double[] levels = new double[TOTAL];
    private int count;
    private int originalPort;
    private String originalType;
    private boolean routeUncertain;
    private boolean silenced;
    private boolean anyNonzero;

    public void frame(short[] samples, int port, String type, boolean systemSilenced) {
        if (count >= TOTAL || samples.length != frameSize) throw new IllegalArgumentException();
        if (count == 0) {
            originalPort = port;
            originalType = type;
        }
        routeUncertain |= port <= 0 || port != originalPort || !type.equals(originalType);
        silenced |= systemSilenced;
        double sum = 0, squares = 0;
        for (short sample : samples) {
            if(sample!=0) anyNonzero=true;
            sum += sample;
            squares += (double) sample * sample;
        }
        double mean = sum / frameSize;
        double rms = Math.sqrt(Math.max(0, squares / frameSize - mean * mean));
        levels[count++] = rms > 0 ? Math.max(-96, 20 * Math.log10(rms / 32768)) : -96;
    }

    public String sampleSummary() { return count==TOTAL ? (anyNonzero ? "有非零音訊樣本" : "所有音訊樣本均為零") : "量測未完成"; }

    public String stableType() { return count == TOTAL && !routeUncertain ? originalType : null; }

    public String result(int requestedPort) {
        if (count != TOTAL) return "量測未完成";
        double[] quiet = Arrays.copyOf(levels, QUIET);
        Arrays.sort(quiet);
        double baseline = (quiet[QUIET / 2 - 1] + quiet[QUIET / 2]) / 2;
        int audibleFrames = 0;
        double loudest = -96;
        for (int i = QUIET; i < TOTAL; i++) {
            loudest = Math.max(loudest, levels[i]);
            if (levels[i] >= Math.max(-72, baseline + 6)) audibleFrames++;
        }
        String port = routeUncertain ? "未確認（裝置變動或未公開）" : originalType;
        if (requestedPort > 0 && (routeUncertain || originalPort != requestedPort))
            return "藍牙輸入未持續生效。實際輸入：" + port + "；此次音量不能歸因於所選耳機。";
        String signal = silenced ? "系統曾靜音" :
            audibleFrames >= 6 ? "觀察到訊號上升" : "未達訊號上升門檻";
        return String.format(Locale.ROOT, "實際輸入：%s\n%s\n安靜基準 %.1f dBFS，發聲時段最高 %.1f dBFS",
            port, signal, baseline, loudest);
    }
}
