package tw.vic.defaultinput;

import java.util.LinkedHashSet;
import java.util.Set;

public final class RatePlan {
    public static int[] candidates(int[] advertised) {
        Set<Integer> supported = new LinkedHashSet<>();
        for (int rate : advertised) if (rate >= 8000 && rate <= 192000) supported.add(rate);
        Set<Integer> result = new LinkedHashSet<>();
        for (int rate : new int[]{16000, 8000, 48000, 44100})
            if (advertised.length == 0 || supported.contains(rate)) result.add(rate);
        result.addAll(supported);
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    public interface Attempt<T> { T open(int rate); }

    public static <T> T open(int[] rates, Attempt<T> attempt) {
        StringBuilder failures = new StringBuilder();
        for (int rate : rates) {
            try {
                return attempt.open(rate);
            } catch (IllegalArgumentException | IllegalStateException failed) {
                if (failures.length() > 0) failures.append("、");
                failures.append(rate).append(" Hz：").append(failed.getMessage());
            }
        }
        throw new IllegalStateException(failures.length() > 0 ? failures.toString() : "沒有可測的取樣率");
    }
}
