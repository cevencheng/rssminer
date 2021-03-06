package rssminer.classfier;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class FetcherEvent extends Event {
    final int subid;
    final List<Integer> feedids;

    public FetcherEvent(int subid, List<Integer> feedids) {
        super(DELAY);
        this.subid = subid;
        this.feedids = feedids;
    }

    public String toString() {
        return "fetcher:" + subid + ", count:" + feedids.size() + " due in "
                + getDelay(TimeUnit.SECONDS) + " s";
    }
}
