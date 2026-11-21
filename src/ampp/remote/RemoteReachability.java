package ampp.remote;

final class RemoteReachability {
    private static final long OFFLINE_GRACE_MS = 12000L;
    private long offlineSince = -1L;

    // Called on the polling worker. A brief interruption keeps the remote session alive.
    boolean shouldRecover(boolean reachable, long now) {
        if (reachable) {
            offlineSince = -1L;
        } else if (offlineSince < 0L) {
            offlineSince = now;
        }
        return offlineSince >= 0L && now - offlineSince >= OFFLINE_GRACE_MS;
    }
}
