package rssminer.db;

public class Vote {
    public final int feedID;
    public final int vote;
    public int docID;

    public Vote(int feedID, int vote) {
        this.feedID = feedID;
        this.vote = vote;
    }

    public void setDocID(int docID) {
        this.docID = docID;
    }

    public String toString() {
        return "Vote [feedID=" + feedID + ", vote=" + vote + ", docID="
                + docID + "]";
    }
}
