package org.mobicents.ussdgateway;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.mobicents.ussdgateway.rules.ScRoutingRule;
import org.mobicents.ussdgateway.rules.ScRoutingRuleType;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Verifies Jackson-based config loaders accept on-disk XML produced by javolution
 * in upstream RestComm USSD Gateway releases.
 */
public class LegacyJavolutionConfigCompatibilityTest {

    private File tempDir;

    @BeforeMethod
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("ussd-config-compat-").toFile();
        tempDir.deleteOnExit();
    }

    @DataProvider(name = "scRoutingRuleFixtures")
    public Object[][] scRoutingRuleFixtures() {
        return new Object[][] {
            {"legacy/config/scroutingrule-javolution.xml", "*519#", "*518#"},
            {"legacy/config/scroutingrule-jackson.xml", "*519#", "*518#"},
        };
    }

    @Test(dataProvider = "scRoutingRuleFixtures")
    public void loadScRoutingRulesFromLegacyAndJacksonFixtures(String resource, String httpSc, String sipSc)
            throws Exception {
        ShortCodeRoutingRuleManagement mgmt = ShortCodeRoutingRuleManagement.getInstance("CompatTest");
        mgmt.setPersistDir(tempDir.getAbsolutePath());
        writeFixture(resource, mgmt.getName() + "_scroutingrule.xml");

        mgmt.start();

        List<ScRoutingRule> rules = mgmt.getScRoutingRuleList();
        assertEquals(rules.size(), 2);

        ScRoutingRule httpRule = mgmt.getScRoutingRule(httpSc, 0);
        assertNotNull(httpRule);
        assertEquals(httpRule.getRuleType(), ScRoutingRuleType.HTTP);
        assertEquals(httpRule.getRuleUrl(), "http://127.0.0.1:8080/ussddemo/test");
        assertTrue(httpRule.isExactMatch());

        ScRoutingRule sipRule = mgmt.getScRoutingRule(sipSc, 0);
        assertNotNull(sipRule);
        assertEquals(sipRule.getRuleType(), ScRoutingRuleType.SIP);
        assertEquals(sipRule.getSipProxy(), "127.0.0.1:5080");
    }

    @DataProvider(name = "ussdPropertiesFixtures")
    public Object[][] ussdPropertiesFixtures() {
        return new Object[][] {
            {"legacy/config/ussdproperties-javolution.xml", true},
            {"legacy/config/ussdproperties-jackson.xml", true},
        };
    }

    @Test(dataProvider = "ussdPropertiesFixtures")
    public void loadUssdPropertiesFromLegacyAndJacksonFixtures(String resource, boolean expectNetworkGt)
            throws Exception {
        UssdPropertiesManagement mgmt = UssdPropertiesManagement.getInstance("CompatTest");
        mgmt.setPersistDir(tempDir.getAbsolutePath());
        writeFixture(resource, mgmt.getName() + "_ussdproperties.xml");

        mgmt.start();

        assertEquals(mgmt.getDialogTimeout(), 25000L);
        assertEquals(mgmt.getUssdGt(), "923330053058");
        assertEquals(mgmt.getUssdSsn(), 8);
        assertEquals(mgmt.getHlrSsn(), 6);
        assertEquals(mgmt.getMscSsn(), 8);
        assertEquals(mgmt.getMaxMapVersion(), 3);
        assertEquals(mgmt.getMaxActivityCount(), 5000);
        assertEquals(mgmt.getCdrSeparator(), ":");
        assertEquals(mgmt.getCdrLoggingTo(), UssdPropertiesManagement.CdrLoggedType.LocalRa);
        assertEquals(mgmt.getNoRoutingRuleConfiguredMessage(),
                "Not valid short code. Please dial valid short code.");

        if (expectNetworkGt) {
            assertEquals(mgmt.getUssdGt(2), "923330053058");
        }
    }

    @Test
    public void adapterConvertsJavolutionScRoutingRuleAttributes() throws Exception {
        String xml = readResource("legacy/config/scroutingrule-javolution.xml");
        String inner = LegacyXmlConfigAdapter.unwrapSingleRootElement(xml);
        String normalized = LegacyXmlConfigAdapter.normalizeScRoutingRuleListContent(inner);

        XmlMapper mapper = new XmlMapper();
        ArrayList<ScRoutingRule> rules = mapper.readValue(normalized,
                mapper.getTypeFactory().constructCollectionType(ArrayList.class, ScRoutingRule.class));

        assertEquals(rules.size(), 2);
        assertEquals(rules.get(0).getShortCode(), "*519#");
        assertEquals(rules.get(1).getRuleType(), ScRoutingRuleType.SIP);
    }

    @Test
    public void adapterWrapsJavolutionMultiRootPropertiesFile() throws Exception {
        String xml = readResource("legacy/config/ussdproperties-javolution.xml");
        String normalized = LegacyXmlConfigAdapter.normalizeUssdPropertiesXml(xml);

        assertTrue(normalized.startsWith("<HashMap>"));
        assertTrue(normalized.contains("<ussdgtlist><ArrayList>"));
        assertTrue(normalized.contains("<networkId>2</networkId>"));

        XmlMapper mapper = new XmlMapper();
        HashMap<?, ?> properties = mapper.readValue(normalized, HashMap.class);
        assertEquals(properties.get("dialogtimeout").toString(), "25000");
        assertEquals(properties.get("ussdgt"), "923330053058");
    }

    private void writeFixture(String resource, String fileName) throws Exception {
        File target = new File(tempDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(readResource(resource).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readResource(String resource) throws Exception {
        InputStream is = LegacyJavolutionConfigCompatibilityTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(is, "Missing fixture: " + resource);
        byte[] data = new byte[is.available()];
        int read = is.read(data);
        is.close();
        assertTrue(read > 0);
        return new String(data, StandardCharsets.UTF_8);
    }
}
