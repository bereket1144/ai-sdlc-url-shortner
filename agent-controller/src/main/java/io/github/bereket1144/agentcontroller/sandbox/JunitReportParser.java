package io.github.bereket1144.agentcontroller.sandbox;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import io.github.bereket1144.agentcontroller.sandbox.VerificationResult.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Turns a JUnit XML report, which many test tools can write, into a {@link VerificationResult}. The report was
 * written by generated code, so it is untrusted: the parser refuses a document type declaration, which closes the
 * door on external and expanding entities.
 */
final class JunitReportParser {

    /** The most characters of the raw report that are kept in the result. */
    static final int EXCERPT_LIMIT = 2000;

    private static final Logger log = LoggerFactory.getLogger(JunitReportParser.class);

    private JunitReportParser() {
    }

    /**
     * A report that is not well-formed XML is {@code NO_RESULT}. A readable report with no test in it is
     * {@code FAILED}, because nothing was proved.
     */
    static VerificationResult parse(String report, String commitId) {
        Document document;
        try {
            document = read(report);
        } catch (ParserConfigurationException | SAXException | IOException failure) {
            // The text is not logged: it comes from generated code.
            log.warn("event=verification_report_unreadable commitId={} errorType={}",
                    commitId, failure.getClass().getName());
            return VerificationResult.noResult(commitId);
        }

        NodeList testCases = document.getElementsByTagName("testcase");
        List<String> failing = new ArrayList<>();
        int passed = 0;
        for (int index = 0; index < testCases.getLength(); index++) {
            Element testCase = (Element) testCases.item(index);
            if (contains(testCase, "failure") || contains(testCase, "error")) {
                failing.add(nameOf(testCase));
            } else if (!contains(testCase, "skipped")) {
                passed++;
            }
        }
        int run = testCases.getLength();
        Outcome outcome = failing.isEmpty() && run > 0 ? Outcome.PASSED : Outcome.FAILED;
        String excerpt = report.substring(0, Math.min(report.length(), EXCERPT_LIMIT));
        return new VerificationResult(outcome, run, passed, failing.size(), failing, excerpt, commitId);
    }

    private static Document read(String report) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // The default handler throws on a fatal error and, unlike the parser's own, does not print it to stderr.
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new InputSource(new StringReader(report)));
    }

    private static boolean contains(Element testCase, String tag) {
        return testCase.getElementsByTagName(tag).getLength() > 0;
    }

    /** A test that failed before it could be named, such as a file that does not import, has no class name. */
    private static String nameOf(Element testCase) {
        String className = testCase.getAttribute("classname");
        String name = testCase.getAttribute("name");
        return className.isEmpty() ? name : className + "." + name;
    }
}
