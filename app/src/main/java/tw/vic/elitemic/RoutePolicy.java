package tw.vic.elitemic;

final class RoutePolicy {
    enum Action { RELEASE_CALL, RELEASE_ABSENT, ALREADY_ROUTED, WAIT, START, FAILED }
    static final long SETTLE_MS = 8000;
    private int attempts;
    private long deadline;
    private int lastId = -1;
    private static final long STABLE_MS = 30000;
    private long confirmedSince = -1;

    Action decide(boolean call, int deviceId, boolean ownsRequest, boolean confirmed, long now) {
        if (call || deviceId < 0) {
            reset();
            return call ? Action.RELEASE_CALL : Action.RELEASE_ABSENT;
        }
        if (lastId != deviceId) { reset(); lastId=deviceId; }
        if (ownsRequest && confirmed) {
            if (confirmedSince < 0) confirmedSince = now;
            return Action.ALREADY_ROUTED;
        }
        // There may be no callbacks between confirmation and a later route loss.
        if (confirmedSince >= 0 && now - confirmedSince >= STABLE_MS) attempts = 0;
        confirmedSince = -1;
        if (now < deadline) return Action.WAIT;
        if (attempts >= 2) return Action.FAILED;
        attempts++; deadline=now+SETTLE_MS;
        return Action.START;
    }
    void reset(){attempts=0;deadline=0;lastId=-1;confirmedSince=-1;}
}
