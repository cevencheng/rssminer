package rssminer.search;

import static rssminer.Utils.K_DATA_SOURCE;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Index;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.Field.TermVector;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiCollector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rssminer.Utils;
import rssminer.db.DBHelper;
import rssminer.db.Feed;
import rssminer.db.MinerDAO;
import rssminer.jsoup.HtmlUtils;
import clojure.lang.Keyword;

public class Searcher {
    static final Version V = Version.LUCENE_35;
    public static final Analyzer analyzer = new RssminerAnalyzer();
    public static final Logger logger = LoggerFactory.getLogger(Searcher.class);
    public static final String FEED_ID = "id";
    public static final String RSS_ID = "rid";
    public static final String AUTHOR = "author";
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    public static final String TAG = "tag";

    public static final int DELAY = 3;

    static final float AUTHOR_BOOST = 2;
    static final float TITLE_BOOST = 3;
    static final float TAG_BOOST = 2;
    static final float CONTENT_BOOST = 1;

    // cache it, String.intern is heavy
    public static final Term TITLE_TERM = new Term(TITLE);
    public static final Term CONTNET_TERM = new Term(CONTENT);
    public static final Term TAG_TERM = new Term(TAG);
    public static final Term AUTHOR_TERM = new Term(AUTHOR);
    static Term FEED_ID_TERM = new Term(FEED_ID);
    static Term RSS_ID_TERM = new Term(RSS_ID);

    private volatile boolean closed = false;

    public static Term[] ANALYZE_FIELDS = new Term[] { TITLE_TERM, CONTNET_TERM };
    public static Term[] ALL_FIELDS = new Term[] { TITLE_TERM, CONTNET_TERM,
            TAG_TERM, AUTHOR_TERM };
    public static final TermVector TV = TermVector.WITH_POSITIONS_OFFSETS;
    private RefreshThead mRefreshThead = new RefreshThead();
    private List<IndexReader> pendingReader = new LinkedList<IndexReader>();

    private void closeReaders() {
        Iterator<IndexReader> it = pendingReader.iterator();
        while (it.hasNext()) {
            IndexReader r = it.next();
            if (r.getRefCount() == 1) {
                try {
                    r.decRef();
                } catch (IOException ignore) {
                }
                it.remove();
            }
        }
    }

    public void refreshReader() throws CorruptIndexException, IOException {
        IndexReader tmp = IndexReader.open(mIndexer, false);
        synchronized (this) {
            pendingReader.add(mReader);
            closeReaders();
            mReader = tmp; // swap with the new one
            mSearcher = new IndexSearcher(mReader);
        }
    }

    private class RefreshThead extends Thread {
        public RefreshThead() {
            setName("refresh-reader");
            setDaemon(true);
        }

        public void run() {
            try {
                while (!closed) {
                    TimeUnit.MINUTES.sleep(DELAY);
                    refreshReader();
                }
            } catch (InterruptedException ignore) {
            } catch (Exception e) {
                logger.error("refresh read thread died", e);
            }
        }
    }

    public static Searcher initGlobalSearcher(String path,
            Map<Keyword, Object> config) throws IOException {
        closeGlobalSearcher();
        SEARCHER = new Searcher(config, path);
        return SEARCHER;
    }

    private IndexWriter mIndexer = null;
    private IndexReader mReader = null;
    private IndexSearcher mSearcher = null;
    private final String mPath;

    private final Map<Keyword, Object> mConfig;
    private final DataSource mDs;
    private final Map<String, Float> mBoosts = new TreeMap<String, Float>();
    public static Searcher SEARCHER; // global

    public static void closeGlobalSearcher() {
        if (SEARCHER != null) {
            try {
                SEARCHER.close(false);
            } catch (Exception ignore) {
            }
            SEARCHER = null;
        }
    }

    private Searcher(Map<Keyword, Object> config, String path)
            throws IOException {
        final IndexWriterConfig cfg = new IndexWriterConfig(V, analyzer);
        this.mPath = path;
        this.mConfig = config;

        this.mDs = (DataSource) config.get(K_DATA_SOURCE);
        if (this.mDs == null) {
            throw new NullPointerException("ds can not be null");
        }

        cfg.setOpenMode(OpenMode.CREATE_OR_APPEND);
        Directory dir = null;
        if (path.equals("RAM")) {
            dir = new RAMDirectory();
        } else {
            dir = FSDirectory.open(new File(path));
        }
        // used by classifier
        mBoosts.put(AUTHOR, AUTHOR_BOOST);
        mBoosts.put(TITLE, TITLE_BOOST);
        mBoosts.put(CONTENT, CONTENT_BOOST);
        mBoosts.put(TAG, TAG_BOOST);

        mIndexer = new IndexWriter(dir, cfg);
        mReader = IndexReader.open(mIndexer, false);
        mSearcher = new IndexSearcher(mReader);
        mRefreshThead.start();
    }

    private List<String> getTerms(String text) throws IOException {
        text = Mapper.toSimplified(text);
        TokenStream stream = analyzer.tokenStream("", new StringReader(text));
        CharTermAttribute c = stream.getAttribute(CharTermAttribute.class);
        List<String> terms = new ArrayList<String>(4);
        while (stream.incrementToken()) {
            String term = new String(c.buffer(), 0, c.length());
            terms.add(term);
        }
        return terms;
    }

    private BooleanQuery buildQuery(String text, List<Integer> rssids)
            throws IOException {
        BooleanQuery query = new BooleanQuery();
        if (text != null && !text.isEmpty()) {
            List<String> terms = getTerms(text);
            BooleanQuery q = new BooleanQuery();
            for (Term t : ANALYZE_FIELDS) {
                BooleanQuery part = new BooleanQuery();
                for (String term : terms) {
                    part.add(new TermQuery(t.createTerm(term)), Occur.MUST);
                }
                q.add(part, Occur.SHOULD);
            }

            List<String> parts = Utils.simpleSplit(text);
            BooleanQuery part = new BooleanQuery();
            for (String term : parts) {
                // already lower cased by analyzer
                part.add(new TermQuery(TAG_TERM.createTerm(term)), Occur.MUST);
            }
            q.add(part, Occur.SHOULD);

            query.add(q, Occur.MUST);
        }
        BooleanQuery ids = new BooleanQuery();
        for (Integer rid : rssids) {
            ids.add(new TermQuery(RSS_ID_TERM.createTerm(rid.toString())),
                    Occur.SHOULD);
        }
        query.add(ids, Occur.MUST);
        return query;
    }

    private void addFilter(BooleanQuery query, String tags, String authors) {
        if (tags != null && tags.length() > 0) {
            List<String> ts = Utils.split(tags, ';');
            BooleanQuery part = new BooleanQuery();
            for (String tag : ts) {
                part.add(new TermQuery(TAG_TERM.createTerm(tag)), Occur.MUST);
            }
            query.add(part, Occur.MUST);
        }

        if (authors != null && authors.length() > 0) {
            List<String> as = Utils.split(authors, ';');
            BooleanQuery part = new BooleanQuery();
            for (String author : as) {
                part.add(new TermQuery(AUTHOR_TERM.createTerm(author)),
                        Occur.MUST);
            }
            query.add(part, Occur.MUST);
        }
    }

    public void close(boolean optimize) throws IOException {
        if (mIndexer != null) {
            if (optimize) {
                logger.info("optimize index");
                mIndexer.forceMerge(1);
            }
            logger.info("close Searcher@" + mPath);
            mIndexer.close();
            mIndexer = null;
            closed = true;
            mRefreshThead.interrupt();
        }
    }

    private Document createDocument(int feeId, int rssID, String author,
            String title, String summary, String tags) {
        Document doc = new Document();
        // not intern, already interned
        Field fid = new Field(FEED_ID, false, Integer.toString(feeId),
                Store.YES, Index.NOT_ANALYZED, TermVector.NO);
        doc.add(fid);

        Field rid = new Field(RSS_ID, false, Integer.toString(rssID), Store.NO,
                Index.NOT_ANALYZED, TermVector.NO);
        doc.add(rid);

        if (author != null && author.length() > 0) {
            author = Mapper.toSimplified(author);
            Field f = new Field(AUTHOR, false, author, Store.NO,
                    Index.NOT_ANALYZED, TV);
            f.setBoost(AUTHOR_BOOST);
            doc.add(f);
        }

        if (title != null) {
            title = Mapper.toSimplified(title);
            Field f = new Field(TITLE, false, title, Store.NO, Index.ANALYZED,
                    TV);
            f.setBoost(TITLE_BOOST);
            doc.add(f);
        }

        if (tags != null && tags.length() > 0) {
            tags = Mapper.toSimplified(tags).toLowerCase();
            List<String> ts = Utils.split(tags, ';');
            for (String tag : ts) {
                Field f = new Field(TAG, false, tag, Store.NO,
                        Index.NOT_ANALYZED, TV);
                f.setBoost(TAG_BOOST);
                doc.add(f);
            }
        }

        if (summary != null) {
            try {
                // String content = Utils.extractText(summary);
                String content = HtmlUtils.text(summary);
                content = Mapper.toSimplified(content);
                Field f = new Field(CONTENT, false, content, Store.NO,
                        Index.ANALYZED, TV);
                doc.add(f);
            } catch (Exception ignore) {
                logger.error("feed:" + feeId, ignore);
            }
        }
        return doc;
    }

    public int feedID2DocID(IndexSearcher searcher, int feedid)
            throws IOException {
        TermQuery query = new TermQuery(FEED_ID_TERM.createTerm(Integer
                .toString(feedid)));
        TopDocs docs = searcher.search(query, 1);
        if (docs.totalHits == 1) {
            return docs.scoreDocs[0].doc;
        } else {
            return -1; // return -1, not found
        }
    }

    public int[] feedID2DocIDs(List<Integer> feeds) throws IOException {
        int[] array = new int[feeds.size()];
        IndexReader reader = acquireReader();
        try {
            IndexSearcher searcher = new IndexSearcher(reader);
            for (int i = 0; i < feeds.size(); i++) {
                int l = feeds.get(i);
                array[i] = feedID2DocID(searcher, l);
            }
        } finally {
            releaseReader();
        }
        return array;
    }

    public Map<String, Float> getBoost() {
        return mBoosts;
    }

    public synchronized IndexSearcher acquireSearcher() { // need to call
                                                          // release
        mReader.incRef();
        return mSearcher;
    }

    // only searcher exits
    public synchronized IndexReader acquireReader() throws IOException {
        mReader.incRef();
        return mReader;
    }

    public synchronized void releaseReader() throws IOException {
        mReader.decRef();
    }

    public void index(int feeID, int rssID, String author, String title,
            String summary, String tags) throws IOException {
        Document doc = createDocument(feeID, rssID, author, title, summary,
                tags);
        mIndexer.addDocument(doc);
    }

    public Map<String, Object> search(String q, String tags, String authors,
            int userID, int limit, int offset, boolean facted)
            throws IOException, ParseException, SQLException {
        List<Integer> subids = DBHelper.getUserSubIDS(mDs, userID);
        IndexSearcher searcher = acquireSearcher();
        try {
            BooleanQuery query = buildQuery(q, subids);
            addFilter(query, tags, authors);

            TopScoreDocCollector top = TopScoreDocCollector.create(limit
                    + offset, false);
            Map<String, Object> ret = new TreeMap<String, Object>();
            if (facted) {
                FacetCollector f = new FacetCollector(searcher.getIndexReader());
                Collector col = MultiCollector.wrap(top, f);
                searcher.search(query, col);
                ret.put("authors", f.getAuthor(15));
                ret.put("tags", f.getTag(15));
            } else {
                searcher.search(query, top);
            }

            TopDocs docs = top.topDocs(offset);
            final int count = docs.scoreDocs.length;
            List<Integer> feedids = new ArrayList<Integer>(count);
            for (int i = 0; i < count; i++) {
                int docid = docs.scoreDocs[i].doc;
                Document doc = searcher.doc(docid);
                feedids.add(Integer.valueOf(doc.get(FEED_ID)));
            }
            ret.put("total", docs.totalHits);
            if (feedids.isEmpty()) {
                ret.put("feeds", new ArrayList<Feed>(0));
            } else {
                MinerDAO db = new MinerDAO(mConfig);
                ret.put("feeds", db.fetchFeedsWithScore(userID, feedids));
            }
            return ret;
        } finally {
            releaseReader();
        }
    }

    public String toString() {
        return "Searcher@" + mPath;
    }
}
