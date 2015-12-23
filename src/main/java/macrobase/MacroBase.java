package macrobase;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Lists;
import macrobase.analysis.outlier.OutlierDetector;
import macrobase.analysis.outlier.ZScore;
import macrobase.analysis.summary.result.DatumWithScore;
import macrobase.analysis.summary.count.ExactCount;
import macrobase.analysis.summary.itemset.FPGrowth;
import macrobase.analysis.summary.itemset.Apriori;
import macrobase.analysis.summary.itemset.result.ItemsetWithCount;
import macrobase.datamodel.Datum;
import macrobase.ingest.DatumEncoder;
import macrobase.ingest.PostgresLoader;
import macrobase.server.MacroBaseServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Hello world!
 *
 */
public class MacroBase
{
    public static final MetricRegistry metrics = new MetricRegistry();
    public static final ConsoleReporter reporter = ConsoleReporter.forRegistry(metrics)
                                                    .convertRatesTo(TimeUnit.SECONDS)
                                                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                                                    .build();

    private static final Logger log = LoggerFactory.getLogger(MacroBase.class);

    public static void main( String[] args ) throws Exception
    {
        System.out.println("Welcome to\n" +
                           "  _   _   _   _   _   _   _   _   _  \n" +
                           " / \\ / \\ / \\ / \\ / \\ / \\ / \\ / \\ / \\ \n" +
                           "( m | a | c | r | o | b | a | s | e )\n" +
                           " \\_/ \\_/ \\_/ \\_/ \\_/ \\_/ \\_/ \\_/ \\_/ \n");

        //benchmark();

        MacroBaseServer.main(args);
    }

    static void benchmark() throws Exception {

        double MIN_INLIER_RATIO = 0;
        double MIN_SUPPORT = 0.01;

        DatumEncoder encoder = new DatumEncoder();

        // OUTLIER ANALYSIS

        PostgresLoader loader = new PostgresLoader();
        loader.connect("postgres");

        List<Datum> data = loader.getData(encoder,
                                          Lists.newArrayList("userid",
                                                             "hardware_manufacturer",
                                                             "hardware_bootloader",
                                                             "hardware_carrier"),
                                          Lists.newArrayList("data_count_minutes"),
                                          Lists.newArrayList(),
                                          "SELECT * FROM sf_datasets D, mapmatch_history H WHERE H.dataset_id = D.id LIMIT 10000000");

        ZScore detector = new ZScore();
        OutlierDetector.BatchResult or = detector.classifyBatchByZScoreEquivalent(data, 3);

        // SUMMARY

        final int supportCountRequired = (int) MIN_SUPPORT*or.getOutliers().size();

        Map<Integer, Integer> inlierCounts = new ExactCount().count(or.getInliers()).getCounts();
        Map<Integer, Integer> outlierCounts = new ExactCount().count(or.getOutliers()).getCounts();

        // TODO: truncate inliers!
        ArrayList<Set<Integer>> outlierTransactions = new ArrayList<>();

        for(DatumWithScore d : or.getOutliers()) {
            Set<Integer> txn = null;

            for(int i : d.getDatum().getAttributes()) {
                int outlierCount = outlierCounts.get(i);
                if(outlierCount > supportCountRequired) {
                    Integer inlierCount = inlierCounts.get(i);

                    double outlierInlierRatio;
                    if(inlierCount == null || inlierCount == 0) {
                        outlierInlierRatio = Double.POSITIVE_INFINITY;
                    } else {
                        outlierInlierRatio = (((double) outlierCount) / inlierCount)*or.getOutliers().size()/or.getInliers().size();
                    }
                    if(outlierInlierRatio > MIN_INLIER_RATIO) {
                        if(txn == null) {
                            txn = new HashSet<>();
                        }
                        txn.add(i);
                    }
                }
            }

            if(txn != null) {
                outlierTransactions.add(txn);
            }
        }

        System.out.printf("%d inliers %d outliers\n",
                          or.getInliers().size(),
                          or.getOutliers().size());

        long st = System.currentTimeMillis();
        FPGrowth fpg = new FPGrowth();
        List<ItemsetWithCount> is1 = fpg.getItemsets(outlierTransactions, MIN_SUPPORT);
        long en = System.currentTimeMillis();
        long tot = en - st;
        System.out.printf("FP Growth: %d %d\n", is1.size(), tot);


        st = System.currentTimeMillis();
        Apriori ap = new Apriori();
        ap.getItemsets(outlierTransactions, MIN_SUPPORT);
        en = System.currentTimeMillis();
        tot = en - st;
        System.out.printf("Apriori: %d %d\n", is1.size(), tot);

    }
}
