package org.mobicents.ussdgateway;

import org.mobicents.ussdgateway.rules.ScRoutingRule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.StringReader;
import java.util.ArrayList;

public class DeserializeUssdRoutingTest {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<scroutingrulelist>\n" +
            "  <ArrayList>\n" +
            "    <item>\n" +
            "      <ruleType>HTTP</ruleType>\n" +
            "      <shortcode>*519#</shortcode>\n" +
            "      <networkid>0</networkid>\n" +
            "      <ruleurl>http://127.0.0.1:8080/ussddemo/test</ruleurl>\n" +
            "      <exactmatch>true</exactmatch>\n" +
            "    </item>\n" +
            "  </ArrayList>\n" +
            "</scroutingrulelist>";

        // Strip root wrapper like ShortCodeRoutingRuleManagement.load() does
        String content = xml;
        if (content.startsWith("<?xml")) {
            int declEnd = content.indexOf("?>");
            if (declEnd >= 0) {
                content = content.substring(declEnd + 2);
            }
        }
        int startIdx = content.indexOf(">");
        int endIdx = content.lastIndexOf("<");
        if (startIdx >= 0 && endIdx > startIdx) {
            content = content.substring(startIdx + 1, endIdx);
        }

        XmlMapper xmlMapper = new XmlMapper();
        ArrayList<ScRoutingRule> rules = xmlMapper.readValue(content,
            xmlMapper.getTypeFactory().constructCollectionType(ArrayList.class, ScRoutingRule.class));

        System.out.println("Parse OK! rules size=" + rules.size());
        for (ScRoutingRule rule : rules) {
            System.out.println("  rule: " + rule.getShortCode() + " -> " + rule.getRuleType());
        }
    }
}
