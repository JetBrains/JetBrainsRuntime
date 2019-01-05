package j2dbench.report;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * The class reads J2DBench scores and reports them into output stream in format applicable for TeamCity charts.
 * The directory, where J2DBench result files placed, is specified via command line parameter like follows:
 * <p>
 * <code>-basexml | -b <xml file path></code>
 * </p>
 * This directory must contain one file with pattern <code>*{openjdk}*.{res}</code> which is considered as a container
 * of reference scores and several <code>*{jbsdk}*.{res}</code>.
 * <p>
 * <p> Names of these files have several mandatory fields separated by <code>"_"</code> and look like
 * <code>osName_jdkName_renderName_*.res</code>
 * </p>
 * <p>
 * If any of score is less than corresponding reference value by 5% then exit code <code>1</code> is returned otherwise
 * exit code <code>0</code> is returned.
 * <p>
 * Standard output will contain scores in format required for TeamCity charts.
 * <p>
 * Created by vprovodin on 13/02/2017.
 */
public class TCChartReporter {

    private static final DecimalFormat decimalFormat =
            new DecimalFormat("0.00");

    private static FileSystem defaultFileSystem = FileSystems.getDefault();

    private static double getMeasurementError(String testCaseName, String osName) {
        if (testCaseName.contains("text.Rendering.tests.drawString") && osName.toLowerCase().contains("lin")  )
            return 0.18;
        return 0.1;
    }

    /**
     * Level at which tests are grouped to be displayed in summary
     */
    private static final int LEVEL = 2;

    /**
     * Holds the groups and corresponding group-display-names
     */
    private static List<String> groups = new ArrayList<>();
    private static Map<String, Double> referenceValues = new HashMap<>();
    private static boolean testFailed = false;

    private static void printUsage() {
        String usage =
                "\njava TCChartReporter [options]      " +
                        "                                      \n\n" +
                        "where options include:                " +
                        "                                      \n" +
                        "    -basexml | -b <xml file path>     " +
                        "path to base-build result";
        System.out.println(usage);
        System.exit(0);
    }

    /**
     * String = getTestResultsTableForSummary()
     */
    private static double generateTestCaseReport(
            Object key,
            Map<String, J2DAnalyzer.ResultHolder> testCaseResult,
            Map<String, Integer> testCaseResultCount) {

        Integer curTestCountObj = testCaseResultCount.get(key.toString());
        int curTestCount = 0;
        if (curTestCountObj != null) {
            curTestCount = curTestCountObj;
        }

        double totalScore = 0;

        for (int i = 0; i < curTestCount; i++) {
            J2DAnalyzer.ResultHolder resultTCR = testCaseResult.get(key.toString() + "_" + i);
            totalScore = totalScore + resultTCR.getScore();
        }

        return totalScore;
    }

    /**
     * Generate Testcase Summary Report for TC - *.out
     */
    private static void generateTestCaseSummaryReport(
            String OJRname,
            Map<String, Double> consoleResult,
            Map<String, J2DAnalyzer.ResultHolder> testCaseResult,
            Map<String, Integer> testCaseResultCount,
            boolean rememberReference) {

        String curGroupName, curTestName;

        Object[] groupNameArray = groups.toArray();

        Object[] testCaseList = consoleResult.keySet().toArray();
        Arrays.sort(testCaseList);

        for (Object aGroupNameArray : groupNameArray) {

            double value;
            curGroupName = aGroupNameArray.toString();

            for (Object aTestCaseList : testCaseList) {

                curTestName = aTestCaseList.toString();

                if (curTestName.contains(curGroupName)) {

                    value = generateTestCaseReport(curTestName, testCaseResult, testCaseResultCount);

                    System.out.println("##teamcity[buildStatisticValue key='" + (OJRname.isEmpty()? "": OJRname + ".") + curTestName
                            + "' value='" + decimalFormat.format(value) + "']");
                    System.out.println((OJRname.isEmpty()? "": OJRname + ".") + curTestName + "," + decimalFormat.format(value));
                    if (rememberReference) {
                        referenceValues.put(curTestName, value);
                    } else {
                        double refValue = referenceValues.getOrDefault(curTestName, 0.);
                        if (Math.abs(value/refValue - 1) >= getMeasurementError(curTestName, OJRname)) {
                            System.err.println(OJRname);
                            System.err.println(curTestName);
                            System.err.println("\treferenceValue=" + refValue);
                            System.err.println("\t   actualValue=" + value);
                            System.err.println("\t          diff:" + ((value / refValue - 1) * 100));
                            testFailed = (value < refValue);
                        }
                    }
                }
            }
        }
    }

    /**
     * main
     */
    public static void main(String args[]) {

        String baseXML = null;
        int group = 2;

        /* ---- Analysis Mode ----
            BEST    = 1;
            WORST   = 2;
            AVERAGE = 3;
            MIDAVG  = 4;
         ------------------------ */
        int analyzerMode = 4;

        try {

            for (int i = 0; i < args.length; i++) {

                if (args[i].startsWith("-basexml") ||
                        args[i].startsWith("-b")) {
                    i++;
                    baseXML = args[i];
                }
            }
        } catch (Exception e) {
            printUsage();
        }

        XMLHTMLReporter.setGroupLevel(group);
        J2DAnalyzer.setMode(analyzerMode);
        if (baseXML != null) {
            generateComparisonReport(defaultFileSystem.getPath(baseXML));
        } else {
            printUsage();
        }

        if (testFailed)
            System.exit(1);
    }

    /**
     * Add Test Group to the list
     */
    private static void addGroup(String testName) {

        String testNameSplit[] = testName.replace('.', '_').split("_");
        StringBuilder group = new StringBuilder(testNameSplit[0]);
        for (int i = 1; i < LEVEL; i++) {
            group.append(".").append(testNameSplit[i]);
        }

        if (!groups.contains(group.toString()))
            groups.add(group.toString());
    }

    private static List<Path> listResFiles(Path dir, String pattern) throws IOException {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, pattern)) {
            for (Path entry : stream) {
                result.add(entry);
            }
        } catch (DirectoryIteratorException ex) {
            throw ex.getCause();
        }
        return result;
    }

    /**
     * Generate the reports from the base & target result XML
     */
    private static void generateComparisonReport(Path directoryToResFiles) {

        if (directoryToResFiles.toFile().isDirectory()) {
            List<Path> jbsdkFiles, openjdkFiles;

            try {
                jbsdkFiles = listResFiles(directoryToResFiles, "*{jbsdk,jbre}*.{res}");
                openjdkFiles = listResFiles(directoryToResFiles, "*{openjdk}*.{res}");
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }

            readScores(openjdkFiles.get(0), true);

            for (Path file : jbsdkFiles) {
                readScores(file, false);
            }
        } else {
            readScores(directoryToResFiles, true);
        }
    }

    private static void readScores(Path file, boolean rememberReference) {
        String fileName = file.getName(file.getNameCount() - 1).toString();
        String osName="", jdkName="", renderName="";
        if (fileName.contains("win") || fileName.contains("linux") || fileName.contains("osx")) {
            String[] fileNameComponents = fileName.split("_");
            if (fileNameComponents.length > 0)
                osName = fileNameComponents[0];
            if (fileNameComponents.length > 1)
                jdkName = fileNameComponents[1];
            if (fileNameComponents.length > 2)
                renderName = fileNameComponents[2];
        }

        String resultXMLFileName = file.toString();

        J2DAnalyzer.results = new Vector();
        J2DAnalyzer.readResults(resultXMLFileName);
        J2DAnalyzer.SingleResultSetHolder baseSRSH =
                (J2DAnalyzer.SingleResultSetHolder) J2DAnalyzer.results.elementAt(0);
        Enumeration baseEnum_ = baseSRSH.getKeyEnumeration();
        Vector<String> baseKeyvector = new Vector<>();
        while (baseEnum_.hasMoreElements()) {
            baseKeyvector.add((String) baseEnum_.nextElement());
        }
        String baseKeys[] = new String[baseKeyvector.size()];
        baseKeyvector.copyInto(baseKeys);
        J2DAnalyzer.sort(baseKeys);

        Map<String, Double> consoleBaseRes = new HashMap<>();

        Map<String, J2DAnalyzer.ResultHolder> testCaseBaseResult = new HashMap<>();
        Map<String, Integer> testCaseResultCount = new HashMap<>();

        for (String baseKey : baseKeys) {

            J2DAnalyzer.ResultHolder baseTCR =
                    baseSRSH.getResultByKey(baseKey);

            Integer curTestCountObj = testCaseResultCount.get(baseTCR.getName());
            int curTestCount = 0;
            if (curTestCountObj != null) {
                curTestCount = curTestCountObj;
            }
            curTestCount++;
            testCaseBaseResult.put(baseTCR.getName() + "_" + (curTestCount - 1), baseTCR);
            testCaseResultCount.put(baseTCR.getName(), curTestCount);

            addGroup(baseTCR.getName());

            Double curTotalScoreObj = consoleBaseRes.get(baseTCR.getName());
            double curTotalScore = 0;
            if (curTotalScoreObj != null) {
                curTotalScore = curTotalScoreObj;
            }
            curTotalScore = curTotalScore + baseTCR.getScore();
            consoleBaseRes.put(baseTCR.getName(), curTotalScore);
        }

        String OJRname = osName + "." + jdkName + "." + renderName;
        System.out.println("******" + OJRname);
        generateTestCaseSummaryReport((OJRname.length() == 2? "": OJRname),
                consoleBaseRes,
                testCaseBaseResult,
                testCaseResultCount,
                rememberReference);

    }
}
