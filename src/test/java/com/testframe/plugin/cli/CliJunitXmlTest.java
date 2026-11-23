package com.testframe.plugin.cli;

import com.testframe.plugin.engine.Redactor;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;
import com.testframe.plugin.model.Step;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JUnit report (agents/specs/test-tour.md, "Headless CLI"): one {@code <testsuite>} per frame,
 * one {@code <testcase>} per card, a {@code <failure>} for an error card and {@code <skipped/>} for
 * a skipped or not-run one. Two things make the report unusable when they are wrong: a character
 * XML 1.0 cannot hold (picked up from a response body) makes the whole document unparsable, and a
 * card matched by the wrong name is reported as skipped when it actually failed.
 */
class CliJunitXmlTest {

    private static final String FRAME = "active-products";

    private static Card card(String varName, String title) {
        return new Card(varName, title, "openapi", "product-api", "restapi", null, "GET /products", "getProducts",
                null, "");
    }

    private static CardResult result(String varName, String status, String text, String output) {
        return new CardResult(varName, status, text, output, Instant.parse("2026-11-23T10:00:00Z").toString(),
                0, 12, null, null, Map.of());
    }

    private static Cli.FrameReport report(List<Step> steps, List<CardResult> results, Map<String, Integer> counts) {
        return new Cli.FrameReport(FRAME, steps, results, counts, 1234);
    }

    private static Map<String, Integer> counts(int success, int error, int skipped, int notRun) {
        return Map.of("success", success, "error", error, "skipped", skipped, "notRun", notRun);
    }

    // -------------------------------------------------------------------- the shape

    @Test
    void oneTestSuitePerFrameAndOneTestCasePerCard() throws Exception {
        String xml = Cli.junitXml(List.of(
                        report(List.of(new Step(1, List.of(card("getProducts", "Get products"))),
                                        new Step(2, List.of(card("assertProducts", "Assert products")))),
                                List.of(result("getProducts", CardResult.SUCCESS, "[Step 1] Get products: HTTP 200", "{}")),
                                counts(1, 0, 0, 1)),
                        report(List.of(new Step(1, List.of(card("other", "Other")))), List.of(), counts(0, 0, 0, 1))),
                Redactor.structural());

        Document doc = parse(xml);
        assertEquals("testsuites", doc.getDocumentElement().getTagName());
        NodeList suites = doc.getElementsByTagName("testsuite");
        assertEquals(2, suites.getLength());
        Element first = (Element) suites.item(0);
        assertEquals(FRAME, first.getAttribute("name"));
        assertEquals("2", first.getAttribute("tests"));
        assertEquals("0", first.getAttribute("failures"));
        assertEquals("1", first.getAttribute("skipped"), "skipped counts the not-run cards too");
        assertEquals("1.234", first.getAttribute("time"));

        NodeList cases = doc.getElementsByTagName("testcase");
        assertEquals(3, cases.getLength());
        Element success = (Element) cases.item(0);
        assertEquals(FRAME + ".step-1", success.getAttribute("classname"));
        assertEquals("getProducts", success.getAttribute("name"));
        assertEquals("0.012", success.getAttribute("time"));
        assertEquals(0, success.getChildNodes().getLength(), "a success card carries no child element");
        Element notRun = (Element) cases.item(1);
        assertEquals(FRAME + ".step-2", notRun.getAttribute("classname"));
        assertEquals("skipped", notRun.getFirstChild().getNodeName(), "a card that never ran is skipped");
    }

    @Test
    void anErrorCardCarriesItsTextAndOutputAsAFailure() throws Exception {
        String xml = Cli.junitXml(List.of(report(
                List.of(new Step(3, List.of(card("getProducts", "Get products")))),
                List.of(result("getProducts", CardResult.ERROR, "[Step 3] Get products: HTTP 500", "boom & <worse>")),
                counts(0, 1, 0, 0))), Redactor.structural());

        Element failure = (Element) parse(xml).getElementsByTagName("failure").item(0);
        assertEquals("[Step 3] Get products: HTTP 500", failure.getAttribute("message"));
        assertEquals("boom & <worse>", failure.getTextContent(), "the output is escaped, not lost");
        assertTrue(xml.contains("boom &amp; &lt;worse&gt;"), xml);
    }

    /**
     * The engine publishes a card under {@code RunEngine.varNameOf(card)} — the trimmed var-name, or
     * the var-name derived from the title when the card has none. Keying the result map by that and
     * looking it up by the raw field loses such a card's outcome, and an error would be reported as
     * skipped.
     */
    @Test
    void aCardIsMatchedByTheVarNameTheEnginePublishes() throws Exception {
        List<Step> steps = List.of(new Step(1, List.of(card("  ", "Get echo"), card(null, "Assert echo"))));
        List<CardResult> results = List.of(
                result("getEcho", CardResult.ERROR, "[Step 1] Get echo: HTTP 500", "boom"),
                result("assertEcho", CardResult.SKIPPED, "[Step 1] Assert echo: skipped", ""));
        String xml = Cli.junitXml(List.of(report(steps, results, counts(0, 1, 1, 0))), Redactor.structural());

        Document doc = parse(xml);
        NodeList cases = doc.getElementsByTagName("testcase");
        Element first = (Element) cases.item(0);
        assertEquals("getEcho", first.getAttribute("name"), "the var-name comes from the title");
        assertEquals(1, first.getElementsByTagName("failure").getLength(), "the error is reported, not skipped");
        assertEquals(0, first.getElementsByTagName("skipped").getLength());
        Element second = (Element) cases.item(1);
        assertEquals("assertEcho", second.getAttribute("name"));
        assertEquals(1, second.getElementsByTagName("skipped").getLength());
        assertEquals("1", ((Element) doc.getElementsByTagName("testsuite").item(0)).getAttribute("failures"));
    }

    // --------------------------------------------------------- characters XML cannot hold

    /** Every code point XML 1.0 refuses. A lone surrogate is tested on its own: two of them side by
     *  side would form a valid pair. */
    private static String illegalChars() {
        StringBuilder out = new StringBuilder();
        for (int codePoint : new int[]{0x0, 0x1, 0x7, 0x8, 0xB, 0xC, 0xE, 0x1F, 0xD800, 0xFFFE, 0xFFFF}) {
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    @Test
    void charactersXmlCannotHoldAreDroppedAndTheReportStillParses() throws Exception {
        String illegal = illegalChars();
        String text = "before" + illegal + " middle " + illegal + "after";
        String xml = Cli.junitXml(List.of(report(
                List.of(new Step(1, List.of(card("getProducts", "Get " + illegal + "products")))),
                List.of(result("getProducts", CardResult.ERROR, text, text)),
                counts(0, 1, 0, 0))), Redactor.structural());

        assertNoIllegalCodePoint(xml);

        Element failure = (Element) parse(xml).getElementsByTagName("failure").item(0);
        assertEquals("before middle after", failure.getTextContent());
        assertEquals("before middle after", failure.getAttribute("message"));
        assertEquals("getProducts", ((Element) parse(xml).getElementsByTagName("testcase").item(0)).getAttribute("name"));
    }

    @Test
    void aLoneSurrogateIsDroppedAndARealPairSurvives() throws Exception {
        String text = "a" + (char) 0xD800 + "b" + (char) 0xDFFF + "c\ud83c\udf89";
        String xml = Cli.junitXml(List.of(report(
                List.of(new Step(1, List.of(card("getProducts", "Get products")))),
                List.of(result("getProducts", CardResult.ERROR, text, text)),
                counts(0, 1, 0, 0))), Redactor.structural());

        assertNoIllegalCodePoint(xml);
        assertEquals("abc\ud83c\udf89",
                ((Element) parse(xml).getElementsByTagName("failure").item(0)).getTextContent(),
                "an emoji (a real surrogate pair) is kept");
    }

    private static void assertNoIllegalCodePoint(String xml) {
        assertTrue(xml.codePoints().allMatch(c -> c == 0x9 || c == 0xA || c == 0xD
                        || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD) || c >= 0x10000),
                "a character XML 1.0 cannot hold survived into the report");
    }

    @Test
    void legalXmlCharactersSurvive() throws Exception {
        String text = "linha 1\nlinha 2\tcom tab\r\nacento: ação — 日本語 🎉";
        String xml = Cli.junitXml(List.of(report(
                List.of(new Step(1, List.of(card("getProducts", "Get products")))),
                List.of(result("getProducts", CardResult.ERROR, text, text)),
                counts(0, 1, 0, 0))), Redactor.structural());

        Element failure = (Element) parse(xml).getElementsByTagName("failure").item(0);
        assertEquals(text, failure.getTextContent(), "a literal CR would be normalised away, so it is a reference");
        assertEquals(text, failure.getAttribute("message"), "whitespace in an attribute is a numeric reference");
    }

    @Test
    void quotesAndAmpersandsInAnAttributeAreEscaped() throws Exception {
        String xml = Cli.junitXml(List.of(report(
                List.of(new Step(1, List.of(card("getProducts", "Get products")))),
                List.of(result("getProducts", CardResult.ERROR, "said \"it's\" <bad> & worse", "x")),
                counts(0, 1, 0, 0))), Redactor.structural());

        Element failure = (Element) parse(xml).getElementsByTagName("failure").item(0);
        assertEquals("said \"it's\" <bad> & worse", failure.getAttribute("message"));
        assertTrue(xml.contains("&quot;it&apos;s&quot; &lt;bad&gt; &amp; worse"), xml);
    }

    // ------------------------------------------------------------------------ redaction

    @Test
    void theFailureIsRedactedBeforeItIsWritten() throws Exception {
        String secret = "Sup3rS3cretApiKey";
        String xml = Cli.junitXml(List.of(report(
                List.of(new Step(1, List.of(card("getProducts", "Get products")))),
                List.of(result("getProducts", CardResult.ERROR, "[Step 1] failed with " + secret,
                        "{\"apiKey\":\"" + secret + "\"}")),
                counts(0, 1, 0, 0))), new Redactor(List.of(secret)));

        assertFalse(xml.contains(secret), xml);
        Element failure = (Element) parse(xml).getElementsByTagName("failure").item(0);
        assertEquals("[Step 1] failed with ***", failure.getAttribute("message"));
        assertEquals("{\"apiKey\":\"***\"}", failure.getTextContent());
    }

    @Test
    void noFrameProducesAnEmptyButValidDocument() throws Exception {
        String xml = Cli.junitXml(List.of(), Redactor.structural());
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n</testsuites>\n", xml);
        assertEquals("testsuites", parse(xml).getDocumentElement().getTagName());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
